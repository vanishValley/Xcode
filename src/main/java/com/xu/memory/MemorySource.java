package com.xu.memory;

/**
 * 记忆来源 —— 治理门据此定信任基线,是打破"幻觉自我强化回环"的关键。
 *
 *   HUMAN — 用户手动 /save,高信任,只受去重约束
 *   AGENT — 任务结束后自动沉淀,低信任,需过治理门(置信不够就挂起等人工)
 *
 * 为什么要区分:agent 若把自己产生的幻觉存成"事实",下次检索又喂回给自己 → 越强化越信。
 * 给记忆打上 source,检索/裁决时就能区分"这是人说的"还是"agent 自己编的"。
 */
public enum MemorySource {
    HUMAN, AGENT;

    public static MemorySource from(String s) {
        return "agent".equalsIgnoreCase(s) ? AGENT : HUMAN;
    }
}
