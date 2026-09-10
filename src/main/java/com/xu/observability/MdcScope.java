package com.xu.observability;

import org.slf4j.MDC;

/**
 * 在一段业务代码执行期间，临时给所有日志添加同一个 MDC 字段。
 *
 * <p>例如任务开始时放入 task_id，这段代码中的日志就会自动携带 task_id；
 * 任务结束时恢复原值，避免线程池复用线程后把上一个任务的 ID 带给下一个任务。</p>
 */
public final class MdcScope implements AutoCloseable {

    private final String key;
    private final String previousValue;
    private boolean closed;

    /** 保存原值并设置新值；对象关闭时会把原值恢复回来。 */
    private MdcScope(String key, String value) {
        this.key = key;
        this.previousValue = MDC.get(key);
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    /**
     * 进入需要携带业务标识的代码块时调用。
     *
     * @param key 日志字段名，例如 {@code task_id}
     * @param value 当前代码块使用的字段值
     * @return 必须通过 try-with-resources 关闭的 MDC 作用域
     */
    public static MdcScope put(String key, String value) {
        return new MdcScope(key, value);
    }

    /** 离开代码块时自动调用，把 MDC 字段恢复成进入前的值。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
