package com.xu.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.http.OkHttpCallExecutor;
import com.xu.tool.Tool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 抓取静态正文；需要 JS、登录态或反爬验证时返回 browser_required。 */
public class WebFetchTool implements Tool {

    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 20_000;
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_REDIRECTS = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .callTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(false)
                // 连接时再次校验 DNS，防止 DNS rebinding 绕过预检。
                .dns(new PublicOnlyDns())
                .build();
    }

    @Override
    public String name() { return "web_fetch"; }

    @Override
    public String description() {
        return "读取已知 URL 的静态网页正文，返回结构化标题、最终 URL 和正文。" +
                "适合官方文档、博客、新闻等页面；不用于发现网页。" +
                "当结果 status=browser_required 时，不要重复调用，应切换 Chrome DevTools MCP。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "要读取的 http:// 或 https:// URL"
                        )
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        Object rawUrl = arguments.get("url");
        if (!(rawUrl instanceof String url) || url.isBlank()) {
            return failure("error", "INVALID_ARGUMENT", "缺少 url 参数", null);
        }
        url = url.strip();

        String validationError = validateUrl(url);
        if (validationError != null) {
            return failure("blocked", "UNSAFE_URL", validationError, url);
        }
        String currentUrl = url;
        String bodyText = null;
        String contentType = "";

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            // 每次重定向都重新检查，防止公网 URL 跳到内网。
            String targetError = checkPublicTarget(currentUrl);
            if (targetError != null) {
                return failure("blocked", "UNSAFE_TARGET", targetError, currentUrl);
            }

            Request request = new Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Xcode-Agent/1.0 (+web_fetch)")
                    .header("Accept",
                            "text/html,application/xhtml+xml,text/plain,"
                                    + "application/json;q=0.9,*/*;q=0.1")
                    .build();

            try {
                FetchedResponse response = OkHttpCallExecutor.executeInterruptibly(
                        httpClient.newCall(request),
                        WebFetchTool::bufferResponse);
                int code = response.code();

                if (isRedirect(code)) {
                    String location = response.location();
                    if (location == null || location.isBlank()) {
                        return failure("error", "INVALID_REDIRECT",
                                "服务器返回重定向但缺少 Location", currentUrl);
                    }
                    currentUrl = resolveRedirect(currentUrl, location);
                    continue;
                }

                if (code == 401 || code == 403 || code == 429) {
                    return browserRequired("HTTP_" + code,
                            "页面需要登录、触发访问限制或反爬验证", currentUrl);
                }
                if (code < 200 || code >= 300) {
                    return failure("error", "HTTP_" + code,
                            "网页请求失败：" + response.message(), currentUrl);
                }

                contentType = response.contentType();
                if (!isSupportedContentType(contentType)) {
                    return failure("error", "UNSUPPORTED_CONTENT_TYPE",
                            "只支持 HTML、纯文本和 JSON，收到：" + contentType,
                            currentUrl);
                }

                if (!response.hasBody()) {
                    return failure("error", "EMPTY_RESPONSE",
                            "网页响应体为空", currentUrl);
                }
                bodyText = response.bodyText();
                break;
            } catch (BodyTooLargeException e) {
                return failure("error", "BODY_TOO_LARGE",
                        "网页响应超过 5MB 上限", currentUrl);
            } catch (IOException e) {
                if (e instanceof java.io.InterruptedIOException
                        || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                return failure("error", "NETWORK_ERROR",
                        safeMessage(e), currentUrl);
            }
        }

        if (bodyText == null) {
            return failure("error", "TOO_MANY_REDIRECTS",
                    "网页重定向超过 " + MAX_REDIRECTS + " 次", currentUrl);
        }

        if (contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            return success(url, currentUrl, currentUrl, bodyText);
        }

        Document document = Jsoup.parse(bodyText, currentUrl);
        for (String tag : List.of(
                "script", "style", "nav", "footer", "header",
                "iframe", "noscript", "aside", "form")) {
            document.select(tag).remove();
        }

        String title = document.title();
        if (title.isBlank()) {
            Element h1 = document.selectFirst("h1");
            title = h1 == null ? "" : h1.text();
        }

        Element content = document.selectFirst("article");
        if (content == null) content = document.selectFirst("main");
        if (content == null) content = document.body();
        if (content == null) {
            return browserRequired("NO_BODY",
                    "页面没有可读取的正文节点", currentUrl);
        }

        String text = content.wholeText()
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        if (text.isBlank() || looksLikeJavascriptShell(document, text)) {
            return browserRequired("DYNAMIC_PAGE",
                    "静态响应没有有效正文，页面可能依赖 JavaScript 渲染",
                    currentUrl);
        }
        return success(url, currentUrl, title, text);
    }

    private String success(String requestedUrl, String finalUrl,
                           String title, String content) throws Exception {
        boolean truncated = content.length() > MAX_CONTENT_CHARS;
        String returnedContent = truncated
                ? content.substring(0, MAX_CONTENT_CHARS)
                : content;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "ok");
        output.put("requestedUrl", requestedUrl);
        output.put("finalUrl", finalUrl);
        output.put("title", title);
        output.put("content", returnedContent);
        output.put("truncated", truncated);
        if (truncated) output.put("originalCharacters", content.length());
        return mapper.writeValueAsString(output);
    }

    private String browserRequired(String reason, String message, String url)
            throws Exception {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "browser_required");
        output.put("reason", reason);
        output.put("message", message);
        output.put("url", url);
        output.put("nextAction",
                "如果 Chrome MCP 工具尚未注册，先调用 start_chrome_mcp；"
                        + "启动成功后的下一轮调用 mcp__chrome-devtools__navigate_page "
                        + "打开该 URL，再调用 mcp__chrome-devtools__take_snapshot 读取页面。");
        return mapper.writeValueAsString(output);
    }

    private String failure(String status, String code, String message, String url)
            throws Exception {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);
        output.put("errorCode", code);
        output.put("message", message);
        if (url != null) output.put("url", url);
        return mapper.writeValueAsString(output);
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303
                || code == 307 || code == 308;
    }

    private static boolean isSupportedContentType(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("text/html")
                || lower.contains("application/xhtml+xml")
                || lower.contains("text/plain")
                || lower.contains("application/json");
    }

    private static boolean looksLikeJavascriptShell(Document document, String text) {
        if (text.length() >= 120) return false;
        String html = document.html().toLowerCase(Locale.ROOT);
        return html.contains("id=\"root\"")
                || html.contains("id=\"app\"")
                || html.contains("__next_data__")
                || html.contains("enable javascript")
                || html.contains("javascript is required");
    }

    private static String validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("https"))) {
                return "只允许 http:// 和 https:// URL";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "URL 缺少有效主机名";
            }
            if (uri.getUserInfo() != null) {
                return "URL 不允许包含用户名或密码";
            }
            return null;
        } catch (Exception e) {
            return "URL 格式无效";
        }
    }

    private static String checkPublicTarget(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return "无法解析 URL 主机名";
            for (InetAddress address : Dns.SYSTEM.lookup(host)) {
                if (isBlockedAddress(address)) {
                    return "不允许访问非公网地址：" + host;
                }
            }
            return null;
        } catch (Exception e) {
            return "URL 或主机名解析失败：" + safeMessage(e);
        }
    }

    /** 拦截本机、内网、链路本地、组播、CGNAT 和 IPv6 ULA。 */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            return b0 == 0
                    || b0 == 10
                    || b0 == 127
                    || (b0 == 100 && b1 >= 64 && b1 <= 127)
                    || (b0 == 169 && b1 == 254)
                    || (b0 == 172 && b1 >= 16 && b1 <= 31)
                    || (b0 == 192 && (b1 == 0 || b1 == 168))
                    || (b0 == 198 && (b1 == 18 || b1 == 19))
                    || b0 >= 224;
        }

        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) return true; // IPv6 唯一本地地址段 fc00::/7。

            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            mapped &= (bytes[10] & 0xFF) == 0xFF
                    && (bytes[11] & 0xFF) == 0xFF;
            if (mapped) {
                try {
                    return isBlockedAddress(InetAddress.getByAddress(new byte[] {
                            bytes[12], bytes[13], bytes[14], bytes[15]}));
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class PublicOnlyDns implements Dns {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new UnknownHostException(
                            "安全拦截：主机解析到非公网地址 " + hostname);
                }
            }
            return addresses;
        }
    }

    private static String resolveRedirect(String currentUrl, String location) {
        try {
            return URI.create(currentUrl).resolve(location).toString();
        } catch (Exception e) {
            return location;
        }
    }

    private static String readBodyWithLimit(ResponseBody body)
            throws IOException, BodyTooLargeException {
        Charset charset = StandardCharsets.UTF_8;
        if (body.contentType() != null) {
            Charset declared = body.contentType().charset(StandardCharsets.UTF_8);
            if (declared != null) charset = declared;
        }

        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) throw new BodyTooLargeException();
                output.write(buffer, 0, read);
            }
            return output.toString(charset);
        }
    }

    private static FetchedResponse bufferResponse(Response response)
            throws IOException, BodyTooLargeException {
        int code = response.code();
        String contentType = response.header("Content-Type", "");
        ResponseBody body = response.body();
        boolean shouldRead = code >= 200
                && code < 300
                && isSupportedContentType(contentType)
                && body != null;
        if (shouldRead && body.contentLength() > MAX_BODY_BYTES) {
            throw new BodyTooLargeException();
        }
        String bodyText = shouldRead ? readBodyWithLimit(body) : null;
        return new FetchedResponse(
                code,
                response.message(),
                response.header("Location"),
                contentType,
                body != null,
                bodyText);
    }

    private record FetchedResponse(
            int code,
            String message,
            String location,
            String contentType,
            boolean hasBody,
            String bodyText) {
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : message;
    }

    private static final class BodyTooLargeException extends Exception {}
}
