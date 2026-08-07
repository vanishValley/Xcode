package com.xu.memory;

/**
 * 长期记忆的来源。人工保存的内容信任基线较高；Agent 自动提炼的内容必须经过
 * 置信度和重复性治理，避免未经验证的信息在后续检索中被反复强化。
 */
public enum MemorySource {
    HUMAN, AGENT;

    public static MemorySource from(String s) {
        return "agent".equalsIgnoreCase(s) ? AGENT : HUMAN;
    }
}
