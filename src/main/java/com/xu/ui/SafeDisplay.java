package com.xu.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds bounded, redacted values that are safe to put on the UI event bus.
 *
 * <p>Raw tool arguments are deliberately never stored in UI events. This is a
 * second boundary in addition to log redaction: renderers, tests and future
 * front-ends can only observe the display model produced here.</p>
 */
public final class SafeDisplay {

    private static final int MAX_VALUE_CHARS = 240;
    private static final int MAX_COLLECTION_ITEMS = 8;
    private static final String HIDDEN = "••••";

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|token|"
                    + "secret|password|passwd|authorization|cookie|credential)");
    private static final Pattern BODY_KEY = Pattern.compile(
            "(?i)(content|body|source|patch|data|payload|stdin|input_text)");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)((?<![A-Za-z0-9])(?:api[_-]?key|access[_-]?token|"
                    + "refresh[_-]?token|token|"
                    + "secret(?:[_-]?access[_-]?key)?|password|passwd|"
                    + "authorization|cookie|credential)"
                    + "\\b\\s*[:=]\\s*)"
                    + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern COMMAND_SECRET = Pattern.compile(
            "(?i)((?:--?|/)(?:api[_-]?key|access[_-]?token|"
                    + "refresh[_-]?token|token|"
                    + "secret(?:[_-]?access[_-]?key)?|password|passwd|"
                    + "authorization|cookie|credential)"
                    + "(?:\\s+|=))"
                    + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)(https?://[^\\s/@:]+:)[^\\s/@]+(@)");
    private static final Pattern PROVIDER_KEY = Pattern.compile(
            "\\b(?:sk|rk|pk)-[A-Za-z0-9_-]{12,}\\b");
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
                    + "\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern ANSI = Pattern.compile(
            "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*"
                    + "(?:\\u0007|\\u001B\\\\))");
    private static final Set<String> SENSITIVE_QUERY_NAMES = Set.of(
            "key", "apikey", "api_key", "token", "access_token",
            "password", "secret", "signature", "sig");
    private static final Set<String> REGISTERED_SECRETS =
            ConcurrentHashMap.newKeySet();

    private SafeDisplay() {
    }

    public static Map<String, Object> arguments(
            Map<String, Object> rawArguments) {
        if (rawArguments == null || rawArguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        int count = 0;
        for (var entry : rawArguments.entrySet()) {
            if (count++ >= MAX_COLLECTION_ITEMS) {
                safe.put("…", rawArguments.size() - MAX_COLLECTION_ITEMS
                        + " more fields");
                break;
            }
            String rawKey = entry.getKey() == null ? "" : entry.getKey();
            safe.put(
                    text(rawKey),
                    safeValue(rawKey, entry.getValue(), 0));
        }
        return Collections.unmodifiableMap(safe);
    }

    public static String text(String value) {
        return truncate(redact(value == null ? "" : value), MAX_VALUE_CHARS);
    }

    public static String errorPreview(String value) {
        return truncate(redact(value == null ? "" : value), 320);
    }

    /** Registers an exact runtime credential as an additional display guard. */
    public static void registerSecret(String value) {
        if (value != null && value.length() >= 8) {
            REGISTERED_SECRETS.add(value);
        }
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String redacted = value;
        for (String secret : REGISTERED_SECRETS) {
            redacted = redacted.replace(secret, HIDDEN);
        }
        redacted = INLINE_SECRET.matcher(redacted)
                .replaceAll("$1" + HIDDEN);
        redacted = COMMAND_SECRET.matcher(redacted)
                .replaceAll("$1" + HIDDEN);
        redacted = URL_USER_INFO.matcher(redacted)
                .replaceAll("$1" + HIDDEN + "$2");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + HIDDEN);
        redacted = PROVIDER_KEY.matcher(redacted).replaceAll(HIDDEN);
        redacted = GITHUB_TOKEN.matcher(redacted).replaceAll(HIDDEN);
        redacted = JWT.matcher(redacted).replaceAll(HIDDEN);
        return cleanControls(redactUrlQuery(redacted));
    }

    public static String cleanControls(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String withoutAnsi = ANSI.matcher(value).replaceAll("");
        StringBuilder clean = new StringBuilder(withoutAnsi.length());
        withoutAnsi.codePoints().forEach(codePoint -> {
            boolean bidiControl =
                    (codePoint >= 0x202A && codePoint <= 0x202E)
                            || (codePoint >= 0x2066 && codePoint <= 0x2069)
                            || codePoint == 0x200E
                            || codePoint == 0x200F;
            if (bidiControl || codePoint == '\r' || codePoint == 0x7F) {
                return;
            }
            if (codePoint == '\n') {
                clean.append('\n');
            } else if (codePoint == '\t') {
                clean.append("    ");
            } else if (!Character.isISOControl(codePoint)) {
                clean.appendCodePoint(codePoint);
            }
        });
        return clean.toString();
    }

    private static Object safeValue(String key, Object value, int depth) {
        if (SECRET_KEY.matcher(key).find()) {
            return HIDDEN;
        }
        if (value == null) {
            return "null";
        }
        if (BODY_KEY.matcher(key).matches()) {
            int length = String.valueOf(value).length();
            return "<" + length + " chars hidden>";
        }
        if (depth >= 2) {
            return text(String.valueOf(value));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            int count = 0;
            for (var entry : map.entrySet()) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    nested.put("…", map.size() - MAX_COLLECTION_ITEMS
                            + " more fields");
                    break;
                }
                String nestedKey = String.valueOf(entry.getKey());
                nested.put(
                        text(nestedKey),
                        safeValue(nestedKey, entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            for (Object item : iterable) {
                if (values.size() >= MAX_COLLECTION_ITEMS) {
                    values.add("…");
                    break;
                }
                values.add(safeValue(key, item, depth + 1));
            }
            return List.copyOf(values);
        }
        return text(String.valueOf(value));
    }

    private static String redactUrlQuery(String value) {
        Matcher matcher = Pattern.compile("https?://[^\\s\"'<>]+")
                .matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String replacement = sanitizeUrl(original);
            matcher.appendReplacement(
                    output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String sanitizeUrl(String value) {
        try {
            URI uri = new URI(value);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return value;
            }
            StringBuilder safeQuery = new StringBuilder();
            for (String pair : query.split("&")) {
                if (!safeQuery.isEmpty()) {
                    safeQuery.append('&');
                }
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String normalized = name.toLowerCase().replace("-", "_");
                safeQuery.append(name);
                if (equals >= 0) {
                    safeQuery.append('=');
                    safeQuery.append(SENSITIVE_QUERY_NAMES.contains(normalized)
                            ? HIDDEN : pair.substring(equals + 1));
                }
            }
            return new URI(
                    uri.getScheme(),
                    uri.getRawAuthority(),
                    uri.getRawPath(),
                    safeQuery.toString(),
                    uri.getRawFragment()).toASCIIString();
        } catch (URISyntaxException ignored) {
            return value;
        }
    }

    private static String truncate(String value, int maxChars) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxChars) {
            return value;
        }
        int keep = Math.max(0, maxChars - 16);
        int end = value.offsetByCodePoints(0, keep);
        return value.substring(0, end)
                + "… (" + codePoints + " chars)";
    }
}
