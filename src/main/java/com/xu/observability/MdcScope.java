package com.xu.observability;

import org.slf4j.MDC;

/**
 * 临时设置一个 MDC 字段，并在离开作用域时恢复原值。
 *
 * <p>适合 {@code task_id} 这类需要被当前线程内所有嵌套日志继承的业务标识。
 * 配合 try-with-resources 使用，避免线程池复用时发生上下文污染。</p>
 */
public final class MdcScope implements AutoCloseable {

    private final String key;
    private final String previousValue;
    private boolean closed;

    private MdcScope(String key, String value) {
        this.key = key;
        this.previousValue = MDC.get(key);
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    public static MdcScope put(String key, String value) {
        return new MdcScope(key, value);
    }

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
