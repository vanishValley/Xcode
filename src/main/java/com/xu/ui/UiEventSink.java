package com.xu.ui;

/** 用户界面事件的发布目标。 */
@FunctionalInterface
public interface UiEventSink {

    void emit(UiEvent event);

    /**
     * 仅富终端启用主 Agent 流式输出；plain CLI 和测试保留非流式 HTTP 路径以兼容旧行为。
     */
    default boolean supportsStreaming() {
        return false;
    }

    static UiEventSink noop() {
        return event -> {
            // 空实现用于不需要界面事件的调用方。
        };
    }
}
