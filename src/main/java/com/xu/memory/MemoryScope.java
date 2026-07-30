package com.xu.memory;

/**
 * 记忆作用域 —— 跨项目隔离的核心字段(不是单独的系统,就是知识身上的一个标)。
 *
 *   PROJECT — 只在写入它的那个仓库可见(如"这仓库跑测试要先设 JAVA_HOME")
 *   GLOBAL  — 跨项目通用(如"改完代码必须先跑测试再说完成")
 *
 * 检索时按"当前仓库 + GLOBAL"过滤;cd 换仓库,当前 key 一变,PROJECT 记忆自然换掉、GLOBAL 常驻。
 */
public enum MemoryScope {
    PROJECT, GLOBAL;

    /** 容错解析:除 "global"(忽略大小写)外一律当 PROJECT。 */
    public static MemoryScope from(String s) {
        return "global".equalsIgnoreCase(s) ? GLOBAL : PROJECT;
    }
}
