package com.xu.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xu.ui.SafeDisplay;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有容量边界、尽力写入的大型诊断数据存储。
 *
 * <p>数据流式写入每条 Trace 的暂存目录，不在内存中长期保留。默认 {@code failure}
 * 模式会删除成功任务的数据，只保留失败或降级任务供排查。</p>
 */
public final class ExecutionArtifactStore {

    private static final Logger logger =
            LoggerFactory.getLogger(ExecutionArtifactStore.class);
    private static final int DEFAULT_ENTRY_LIMIT = 256 * 1024;
    private static final long DEFAULT_TRACE_LIMIT = 5L * 1024 * 1024;
    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final ExecutionArtifactStore DISABLED =
            new ExecutionArtifactStore(null, Mode.OFF,
                    DEFAULT_ENTRY_LIMIT,
                    DEFAULT_TRACE_LIMIT,
                    DEFAULT_RETENTION_DAYS);

    public enum Mode {
        OFF, FAILURE, ALWAYS
    }

    private final Path root;
    private final Mode mode;
    private final int entryLimitBytes;
    private final long traceLimitBytes;
    private final int retentionDays;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong sequence = new AtomicLong();

    private ExecutionArtifactStore(
            Path root,
            Mode mode,
            int entryLimitBytes,
            long traceLimitBytes,
            int retentionDays) {
        this.root = root;
        this.mode = mode;
        this.entryLimitBytes = entryLimitBytes;
        this.traceLimitBytes = traceLimitBytes;
        this.retentionDays = retentionDays;
    }

    public static ExecutionArtifactStore create(Path root) {
        String configured = firstNonBlank(
                System.getProperty("xcode.artifact.mode"),
                System.getenv("XCODE_ARTIFACT_MODE"),
                "failure");
        Mode mode = switch (configured.strip().toLowerCase()) {
            case "off", "none", "false" -> Mode.OFF;
            case "always", "all" -> Mode.ALWAYS;
            default -> Mode.FAILURE;
        };
        if (mode == Mode.OFF || root == null) return DISABLED;
        int entryLimit = positiveInt(firstNonBlank(
                System.getProperty("xcode.artifact.entry.max_bytes"),
                System.getenv("XCODE_ARTIFACT_ENTRY_MAX_BYTES")),
                DEFAULT_ENTRY_LIMIT);
        long traceLimit = positiveLong(firstNonBlank(
                System.getProperty("xcode.artifact.trace.max_bytes"),
                System.getenv("XCODE_ARTIFACT_TRACE_MAX_BYTES")),
                DEFAULT_TRACE_LIMIT);
        int retentionDays = positiveInt(firstNonBlank(
                System.getProperty("xcode.artifact.retention_days"),
                System.getenv("XCODE_ARTIFACT_RETENTION_DAYS")),
                DEFAULT_RETENTION_DAYS);
        ExecutionArtifactStore store = new ExecutionArtifactStore(
                root.toAbsolutePath().normalize(),
                mode,
                entryLimit,
                traceLimit,
                retentionDays);
        store.cleanupExpired();
        return store;
    }

    static ExecutionArtifactStore disabled() {
        return DISABLED;
    }

    public Mode mode() {
        return mode;
    }

    public Path root() {
        return root;
    }

    public void beginTrace(String traceId, String mode, String userInput) {
        if (!enabled(traceId)) return;
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("trace_id", traceId);
        manifest.put("mode", mode);
        manifest.put("started_at", OffsetDateTime.now().toString());
        manifest.put("user_input", userInput);
        writeJson(staging(traceId).resolve("manifest.json"), manifest, traceId);
    }

    public Operation beginOperation(
            String kind,
            String name,
            String requestContent) {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid() || this == DISABLED) return Operation.noop();
        String traceId = context.getTraceId();
        String prefix = String.format(
                "%06d-%s",
                sequence.incrementAndGet(),
                safeName(kind));
        Path directory = staging(traceId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trace_id", traceId);
        metadata.put("span_id", context.getSpanId());
        metadata.put("kind", kind);
        metadata.put("name", name);
        metadata.put("started_at", OffsetDateTime.now().toString());
        writeJson(directory.resolve(prefix + "-metadata.json"),
                metadata, traceId);
        writeText(directory.resolve(prefix + "-request.txt"),
                requestContent, traceId);
        return new Operation(this, traceId, prefix);
    }

    public void completeTrace(
            String traceId,
            String outcome,
            boolean recoveredErrors) {
        if (!enabled(traceId)) return;
        Path source = staging(traceId);
        if (!Files.exists(source)) return;
        boolean retain = mode == Mode.ALWAYS
                || !"SUCCESS".equalsIgnoreCase(outcome)
                || recoveredErrors;
        if (!retain) {
            deleteTree(source);
            return;
        }

        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("trace_id", traceId);
        completion.put("outcome", outcome);
        completion.put("recovered_errors", recoveredErrors);
        completion.put("completed_at", OffsetDateTime.now().toString());
        writeJson(source.resolve("completion.json"), completion, traceId);

        String bucket = mode == Mode.ALWAYS ? "runs" : "failures";
        Path target = root.resolve(bucket)
                .resolve(LocalDate.now().toString())
                .resolve(traceId);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            logger.atInfo()
                    .addKeyValue("event", "artifact.trace.retained")
                    .addKeyValue("trace_id", traceId)
                    .addKeyValue("outcome", outcome)
                    .addKeyValue("artifact_dir", target)
                    .log("Retained execution diagnostic artifacts");
        } catch (IOException atomicFailure) {
            try {
                Files.move(source, target);
            } catch (IOException moveFailure) {
                logger.atWarn()
                        .addKeyValue("event", "artifact.trace_finalize_failed")
                        .addKeyValue("trace_id", traceId)
                        .setCause(moveFailure)
                        .log("Unable to finalize execution artifacts");
            }
        }
    }

    private boolean enabled(String traceId) {
        return this != DISABLED && traceId != null && !traceId.isBlank();
    }

    private Path staging(String traceId) {
        return root.resolve("staging").resolve(safeName(traceId));
    }

    private void writeJson(Path path, Object value, String traceId) {
        try {
            writeText(path, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value), traceId);
        } catch (IOException error) {
            warnWrite(traceId, path, error);
        }
    }

    private synchronized void writeText(
            Path path,
            String content,
            String traceId) {
        try {
            Files.createDirectories(path.getParent());
            if (traceSize(path.getParent()) >= traceLimitBytes) {
                return;
            }
            String sanitized = SafeDisplay.redact(
                    content == null ? "" : content);
            Files.writeString(
                    path,
                    truncateUtf8(sanitized, entryLimitBytes),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            warnWrite(traceId, path, error);
        }
    }

    private long traceSize(Path directory) throws IOException {
        if (!Files.exists(directory)) return 0L;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .sum();
        }
    }

    private void warnWrite(String traceId, Path path, IOException error) {
        logger.atWarn()
                .addKeyValue("event", "artifact.write_failed")
                .addKeyValue("trace_id", traceId)
                .addKeyValue("artifact_file", path)
                .setCause(error)
                .log("Unable to write execution artifact");
    }

    private static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8)
                + "\n...[truncated]";
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private void cleanupExpired() {
        if (root == null) return;
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
        cleanupDateBuckets(root.resolve("failures"), cutoffDate);
        cleanupDateBuckets(root.resolve("runs"), cutoffDate);

        Path staging = root.resolve("staging");
        if (!Files.isDirectory(staging)) return;
        long cutoffMillis = cutoffDate.atStartOfDay()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        try (var traces = Files.list(staging)) {
            traces.filter(Files::isDirectory).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toMillis()
                            < cutoffMillis) {
                        deleteTree(path);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void cleanupDateBuckets(Path bucket, LocalDate cutoff) {
        if (!Files.isDirectory(bucket)) return;
        try (var dates = Files.list(bucket)) {
            dates.filter(Files::isDirectory).forEach(path -> {
                try {
                    LocalDate date = LocalDate.parse(
                            path.getFileName().toString());
                    if (date.isBefore(cutoff)) deleteTree(path);
                } catch (RuntimeException ignored) {
            // 未识别目录不属于本清理规则的管理范围。
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String safeName(String value) {
        String normalized = value == null ? "unknown"
                : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.length() > 80
                ? normalized.substring(0, 80) : normalized;
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long positiveLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    public static final class Operation implements AutoCloseable {
        private static final Operation NOOP = new Operation(null, null, null);

        private final ExecutionArtifactStore store;
        private final String traceId;
        private final String prefix;
        private boolean completed;

        private Operation(
                ExecutionArtifactStore store,
                String traceId,
                String prefix) {
            this.store = store;
            this.traceId = traceId;
            this.prefix = prefix;
        }

        static Operation noop() {
            return NOOP;
        }

        public void success(String resultContent) {
            complete("success", resultContent);
        }

        public void failure(String resultContent) {
            complete("failure", resultContent);
        }

        private void complete(String outcome, String resultContent) {
            if (store == null || completed) return;
            completed = true;
            Path directory = store.staging(traceId);
            store.writeText(
                    directory.resolve(prefix + "-" + outcome + ".txt"),
                    resultContent,
                    traceId);
        }

        @Override
        public void close() {
            if (store != null && !completed) {
                complete("incomplete", "Operation ended without a result");
            }
        }
    }
}
