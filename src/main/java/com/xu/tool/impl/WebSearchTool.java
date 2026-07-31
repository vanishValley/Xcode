package com.xu.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.tool.Tool;
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** 腾讯 WSA 搜索工具，只返回候选 URL；正文由 web_fetch 读取。 */
public class WebSearchTool implements Tool {

    private static final String API_URL = "https://api.wsa.cloud.tencent.com/SearchPro";
    private static final int MAX_RESULT_LENGTH = 200;   // 每条摘要截断

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebSearchTool(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("WSA_API_KEY 未配置");
        }
        this.apiKey = apiKey.strip();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(15))
                .callTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public String name() { return "web_search"; }

    @Override
    public String description() {
        return "搜索互联网，返回结构化的标题、URL、摘要和来源。" +
                "当用户要求搜索、查找最新信息或尚不知道目标 URL 时使用。" +
                "搜索摘要只用于筛选，选定 URL 后使用 web_fetch 或浏览器核验正文。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "搜索关键词，支持自然语言"
                        ),
                        "site", Map.of(
                                "type", "string",
                                "description", "可选，只搜索指定域名，例如 docs.oracle.com"
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return error("INVALID_ARGUMENT", "缺少查询关键词");
        }
        query = query.strip();
        if (query.length() > 500) {
            return error("INVALID_ARGUMENT", "查询关键词不能超过 500 个字符");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Query", query);
        Object siteArg = arguments.get("site");
        if (siteArg instanceof String site && !site.isBlank()) {
            body.put("Site", site.strip());
        }

        String json = mapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        String respBody;
        try {
            SearchResponse response = InterruptibleHttp.execute(
                    httpClient.newCall(request),
                    raw -> {
                        var bodyResponse = raw.body();
                        String bodyText = raw.isSuccessful()
                                && bodyResponse != null
                                ? bodyResponse.string() : null;
                        return new SearchResponse(
                                raw.code(), raw.isSuccessful(), bodyText);
                    });
            if (!response.successful()) {
                return error("HTTP_" + response.code(),
                        "搜索服务请求失败");
            }
            if (response.body() == null) {
                return error("EMPTY_RESPONSE", "搜索服务响应为空");
            }
            respBody = response.body();
        } catch (IOException e) {
            if (e instanceof java.io.InterruptedIOException
                    || Thread.currentThread().isInterrupted()) {
                throw e;
            }
            return error("NETWORK_ERROR", safeMessage(e));
        }

        JsonNode root = mapper.readTree(respBody);

        JsonNode error = root.path("Response").path("Error");
        if (!error.isMissingNode()) {
            return error(error.path("Code").asText("SEARCH_ERROR"),
                    error.path("Message").asText("搜索服务返回错误"));
        }

        JsonNode response = root.path("Response");
        JsonNode pages = response.path("Pages");
        if (!pages.isArray() || pages.isEmpty()) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", "no_results");
            output.put("query", query);
            output.put("results", List.of());
            return mapper.writeValueAsString(output);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode page : pages) {
            String objStr = page.isTextual() ? page.asText() : page.toString();
            JsonNode item;
            try {
                item = mapper.readTree(objStr);
            } catch (Exception e) {
                continue;
            }

            String title = item.path("title").asText("无标题");
            String url = item.path("url").asText("");
            String passage = item.path("passage").asText("");
            String site = item.path("site").asText("");
            String date = item.path("date").asText("");
            double score = item.path("score").asDouble(0);

            if (passage.length() > MAX_RESULT_LENGTH) {
                passage = passage.substring(0, MAX_RESULT_LENGTH) + "...";
            }

            if (url.isBlank()) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", passage);
            if (!site.isBlank()) result.put("site", site);
            if (!date.isBlank()) result.put("publishedAt", date);
            result.put("score", score);
            results.add(result);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", results.isEmpty() ? "no_results" : "ok");
        output.put("query", query);
        output.put("results", results);
        output.put("nextAction",
                "选择可信且相关的 URL，使用 web_fetch 获取正文；"
                        + "如 web_fetch 返回 browser_required，则改用 Chrome DevTools MCP。");
        return mapper.writeValueAsString(output);
    }

    private record SearchResponse(
            int code,
            boolean successful,
            String body) {
    }

    private String error(String code, String message) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "status", "error",
                    "errorCode", code,
                    "message", message));
        } catch (Exception ignored) {
            return "{\"status\":\"error\",\"errorCode\":\"SERIALIZATION_ERROR\"}";
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : message;
    }
}
