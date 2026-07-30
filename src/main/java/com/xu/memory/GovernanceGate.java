package com.xu.memory;

import java.util.List;

/**
 * 写入治理门 —— 防"记忆污染 / 幻觉自我强化回环":AGENT 自动沉淀的候选必须过门才可能落盘。
 *
 * 为什么需要:naive 的"任务结束就自动存",会把这次的幻觉存成"事实",下次检索又喂回给 agent,
 *            它把自己编的越强化越信。治理门是这个尖锐问题的工程答案。
 *
 * 决策四选一:
 *   COMMIT — 直接入库
 *   MERGE  — 与已有太像,视为已知,不新增
 *   DEFER  — 冲突/低置信 → 挂起进待确认队列,等人工扫一眼(不漏也不脏)
 *   REJECT — 精确重复/噪声 → 丢弃
 */
public interface GovernanceGate {

    enum Decision { COMMIT, MERGE, DEFER, REJECT }

    /**
     * @param candidate 待写入的候选
     * @param existing  同 scope+同仓库 的现有记录(供去重/冲突判断)
     */
    Decision evaluate(MemoryRecord candidate, List<MemoryRecord> existing);
}
