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
 * 保存普通日志不适合承载的大段诊断内容，例如用户输入、LLM 请求响应和工具结果。
 *
 * <p>一个任务开始时先写入 staging 临时目录，执行过程中边产生边写文件，不把全部
 * 内容堆在内存里。任务成功时默认删除临时文件；任务失败或发生过可恢复错误时，
 * 把整个目录移动到 failures，供开发者按照 trace_id 复盘。</p>
 *
 * <p>单个文件、单条 Trace、保存天数都有上限。写文件失败只记录 WARN，不中断
 * Agent 主流程，因为诊断功能不能反过来导致用户任务失败。</p>
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
        /** 不保存诊断文件。 */
        OFF, FAILURE, ALWAYS
    }

    private final Path root;
    private final Mode mode;
    private final int entryLimitBytes;
    private final long traceLimitBytes;
    private final int retentionDays;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong sequence = new AtomicLong();
    // 显式采集整个任务时，由最外层 Capture 统一归档；内部 Plan 不提前移动目录。
    private final java.util.Set<String> captures = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 保存存储目录、工作模式和容量限制；实例统一由 create() 创建。 */
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

    /**
     * 应用启动时调用，根据系统属性或环境变量创建诊断文件存储。
     *
     * <p>默认使用 FAILURE 模式。创建后会顺便清理超过保留天数的旧目录。</p>
     *
     * @param root 诊断文件根目录
     * @return 已配置的存储；关闭或目录无效时返回空实现
     */
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
        return create(root, mode);
    }

    /** 评测显式保留全部产物，普通启动仍使用用户配置的保存策略。 */
    public static ExecutionArtifactStore create(Path root, Mode mode) {
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

    /** 返回完全不写文件的共享实例，供 Tracing.noop() 和测试使用。 */
    static ExecutionArtifactStore disabled() {
        return DISABLED;
    }

    /** 返回当前保存策略：关闭、只保留异常任务，或保留全部任务。 */
    public Mode mode() {
        return mode;
    }

    /** 返回诊断文件根目录；功能关闭时可能为 null。 */
    public Path root() {
        return root;
    }

    /**
     * Coding Task 刚开始时调用，在 staging 中创建任务清单并保存原始用户输入。
     *
     * @param traceId 本次任务的链路 ID，也是诊断目录名
     * @param mode 任务执行模式，例如 agent 或 plan
     * @param userInput 用户交给 Agent 的原始任务
     */
    public void beginTrace(String traceId, String mode, String userInput) {
        if (!enabled(traceId)) return;
        if (captures.contains(traceId)) return;
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("trace_id", traceId);
        manifest.put("mode", mode);
        manifest.put("started_at", OffsetDateTime.now().toString());
        manifest.put("user_input", userInput);
        writeJson(staging(traceId).resolve("manifest.json"), manifest, traceId);
    }

    /**
     * LLM、Tool 或 MCP 调用开始前调用，立即保存本次调用的元数据和请求原文。
     *
     * <p>返回的 Operation 用于在调用结束时补写成功或失败结果。它从当前 Span
     * 取得 trace_id 和 span_id，因此调用前必须已经进入对应 TraceScope。</p>
     *
     * @param kind 操作类别，例如 llm、tool 或 mcp
     * @param name 操作名称，例如模型名或工具名
     * @param requestContent 本次调用的请求原文
     * @return 本次调用的记录句柄；功能关闭或没有有效 Span 时返回空句柄
     */
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

    /**
     * Coding Task 结束时调用，按照任务结果决定删除暂存数据还是归档保留。
     *
     * <p>默认 FAILURE 模式下，完全成功的任务删除；失败或中途发生过可恢复错误的
     * 任务移动到 failures。ALWAYS 模式下所有任务移动到 runs。</p>
     *
     * @param traceId 本次任务的链路 ID
     * @param outcome 最终执行结果
     * @param recoveredErrors 是否发生过被重试、降级等逻辑恢复的错误
     */
    public Path completeTrace(
            String traceId,
            String outcome,
            boolean recoveredErrors) {
        if (!enabled(traceId)) return null;
        Path source = staging(traceId);
        if (captures.contains(traceId) || !Files.exists(source)) return source;
        boolean retain = mode == Mode.ALWAYS
                || !"SUCCESS".equalsIgnoreCase(outcome)
                || recoveredErrors;
        if (!retain) {
            deleteTree(source);
            return null;
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
        return Files.exists(target) ? target : source;
    }

    Path beginCapture(String traceId, String mode, String input) {
        beginTrace(traceId, mode, input);
        captures.add(traceId);
        return staging(traceId);
    }

    Path completeCapture(String traceId, String outcome, boolean hadErrors) {
        captures.remove(traceId);
        return completeTrace(traceId, outcome, hadErrors);
    }

    /** 统一保存结构化事件；与请求/响应产物共用脱敏、目录和容量策略。 */
    synchronized void appendEvent(String traceId, Object event) {
        if (!enabled(traceId)) return;
        Path file = staging(traceId).resolve("events.jsonl");
        try {
            Files.createDirectories(file.getParent());
            String line = SafeDisplay.redact(objectMapper.writeValueAsString(event)) + "\n";
            if (traceSize(file.getParent()) + line.getBytes(StandardCharsets.UTF_8).length > traceLimitBytes) return;
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException error) { warnWrite(traceId, file, error); }
    }

    /** 判断存储是否开启，并确认 trace_id 可以作为本次任务的目录标识。 */
    private boolean enabled(String traceId) {
        return this != DISABLED && traceId != null && !traceId.isBlank();
    }

    /** 返回某条 Trace 执行期间使用的临时目录。 */
    private Path staging(String traceId) {
        return root.resolve("staging").resolve(safeName(traceId));
    }

    /** 把 Java 对象格式化成 JSON 后写入文件；失败只告警，不影响业务。 */
    private void writeJson(Path path, Object value, String traceId) {
        try {
            writeText(path, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value), traceId);
        } catch (IOException error) {
            warnWrite(traceId, path, error);
        }
    }

    /**
     * 把诊断原文写入文件：先脱敏，再限制单文件和整条 Trace 的大小。
     * synchronized 用来避免同一存储实例被多个线程同时做容量检查和写入。
     */
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

    /** 统计一条 Trace 暂存目录中所有普通文件的总字节数。 */
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

    /** 诊断文件写入失败时记录统一 WARN；该异常不会继续抛给 Agent 主流程。 */
    private void warnWrite(String traceId, Path path, IOException error) {
        logger.atWarn()
                .addKeyValue("event", "artifact.write_failed")
                .addKeyValue("trace_id", traceId)
                .addKeyValue("artifact_file", path)
                .setCause(error)
                .log("Unable to write execution artifact");
    }

    /**
     * 按 UTF-8 字节数截断大文本，并避开多字节字符中间，防止产生乱码。
     */
    private static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8)
                + "\n...[truncated]";
    }

    /** 尽力递归删除一个诊断目录；删除失败不会影响 Agent 主流程。 */
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

    /** 应用启动时调用，删除超过 retentionDays 的归档和遗留 staging 目录。 */
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

    /** 清理 failures 或 runs 下早于截止日期的 YYYY-MM-DD 目录。 */
    private static void cleanupDateBuckets(Path bucket, LocalDate cutoff) {
        if (!Files.isDirectory(bucket)) return;
        try (var dates = Files.list(bucket)) {
            dates.filter(Files::isDirectory).forEach(path -> {
                try {
                    LocalDate date = LocalDate.parse(
                            path.getFileName().toString());
                    if (date.isBefore(cutoff)) deleteTree(path);
                } catch (RuntimeException ignored) {
                    // 未识别的目录不属于本清理规则的管理范围。
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 把外部字符串转换成可安全用作目录名或文件名前缀的短字符串。 */
    private static String safeName(String value) {
        String normalized = value == null ? "unknown"
                : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.length() > 80
                ? normalized.substring(0, 80) : normalized;
    }

    /** 读取正整数配置；缺失、格式错误或非正数时使用默认值。 */
    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** 读取正长整数配置；缺失、格式错误或非正数时使用默认值。 */
    private static long positiveLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** 按优先级返回第一个非空配置值，全部为空时返回空字符串。 */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    /**
     * 表示一次正在记录的 LLM、Tool 或 MCP 操作，用于在结束时补写调用结果。
     */
    public static final class Operation implements AutoCloseable {
        private static final Operation NOOP = new Operation(null, null, null);

        private final ExecutionArtifactStore store;
        private final String traceId;
        private final String prefix;
        private boolean completed;

        /** 保存所属 Trace 和文件前缀；由 beginOperation() 创建。 */
        private Operation(
                ExecutionArtifactStore store,
                String traceId,
                String prefix) {
            this.store = store;
            this.traceId = traceId;
            this.prefix = prefix;
        }

        /** 返回不写文件的空句柄，让调用方仍可统一使用 try-with-resources。 */
        static Operation noop() {
            return NOOP;
        }

        /** 外部调用成功后调用，把响应或工具结果写入 success 文件。 */
        public void success(String resultContent) {
            complete("success", resultContent);
        }

        /** 外部调用失败后调用，把错误响应或异常信息写入 failure 文件。 */
        public void failure(String resultContent) {
            complete("failure", resultContent);
        }

        /** success() 和 failure() 共用的单次完成逻辑，防止结果被重复写入。 */
        private void complete(String outcome, String resultContent) {
            if (store == null || completed) return;
            completed = true;
            Path directory = store.staging(traceId);
            store.writeText(
                    directory.resolve(prefix + "-" + outcome + ".txt"),
                    resultContent,
                    traceId);
        }

        /**
         * 离开 try-with-resources 时调用；若调用方忘记记录结果，则写入 incomplete。
         */
        @Override
        public void close() {
            if (store != null && !completed) {
                complete("incomplete", "Operation ended without a result");
            }
        }
    }
}
