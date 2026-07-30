package com.xu.memory;

import java.util.List;

/**
 * 检索器 —— 从当前仓库可见的知识里,按相关性返回 top-K。
 *
 * 抽成接口是为了【向量-ready】:现在是关键词版 KeywordRetriever,量大了可换 VectorRetriever
 * 或 hybrid,上层(KnowledgeBase / 组装器)完全不变。
 *
 * 面试点:"为什么不直接上向量?" — 量小(≤50 条)关键词召回够,向量要接 embedding 模型 + 存储,
 * ROI 低;但留了接口,随时能平滑升级。知道边界在哪,比硬上向量更成熟。
 */
public interface Retriever {

    /**
     * @param query       用户当前输入
     * @param projectKey  当前仓库 key(scope 过滤:只看本仓库 PROJECT + 所有 GLOBAL)
     * @param topK        返回上限
     * @return 相关记录,按相关性降序;无相关返回空列表
     */
    List<MemoryRecord> retrieve(String query, String projectKey, int topK);
}
