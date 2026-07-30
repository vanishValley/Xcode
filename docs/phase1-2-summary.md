# Phase 1-2 总结：ReAct + Plan-and-Execute Agent CLI

> 面向面试复习的完整文档。每个模块讲清楚三个问题：**做了什么、为什么这么做、出了事怎么办**。

---

## 一、项目全景

```
Xcode Agent  v1.0
├── 14 个源文件（main）+ 7 个测试类（test）
├── 5 个内置工具 + LLM 客户端 + 两条执行路径
│
├── ReAct 模式（默认）：走一步看一步，适合简单任务
└── Plan 模式（/plan）：先规划再执行，适合复杂任务
```

### 文件职责一览

| 文件 | 职责 | 行数 |
|------|------|:--:|
| `cli/Main.java` | 入口：组装部件、交互循环、内置命令 | ~110 |
| `llm/LlmClient.java` | HTTP 通信：拼 JSON→POST DeepSeek→解析 JSON | ~130 |
| `agent/Agent.java` | ReAct 循环核心：Think→Act→Observe | ~140 |
| `agent/PlanExecuteAgent.java` | Plan 模式引擎：规划→DAG 循环→重规划 | ~260 |
| `plan/Planner.java` | 规划器：LLM 拆任务→生成 ExecutionPlan | ~175 |
| `plan/ExecutionPlan.java` | DAG 调度器 + 环检测 + 阻塞诊断 | ~210 |
| `plan/Task.java` | 步骤数据结构 | ~60 |
| `tool/Tool.java` | 工具接口 | ~15 |
| `tool/ToolRegistry.java` | 工具注册 + 转 OpenAI tools 格式 | ~55 |
| 5 个 Tool impl | 具体工具实现 | ~125×5 |

### 测试覆盖

| 测试类 | 用例数 | 覆盖场景 |
|--------|:-----:|---------|
| `ExecutionPlanTest` | 12 | DAG 调度 + 环检测 + 阻塞诊断 |
| `ToolRegistryTest` | 4 | 注册/查找/OpenAI 格式输出 |
| `ReadFileToolTest` | 3 | 读文件/不存在/缺参数 |
| `WriteFileToolTest` | 6 | 写入/父目录创建/路径逃逸/路径穿越/超大内容/缺参数 |
| `ListDirToolTest` | 4 | 列目录/空目录/不存在/默认路径 |
| `GlobFilesToolTest` | 5 | 模式匹配/子目录限定/跳过 target/无匹配/缺参数 |
| `ExecuteCommandToolTest` | 6 | 黑名单 4 条 + 正常 echo + 缺参数 |
| **合计** | **40** | **0 依赖外部服务，全单元测试** |

---

## 二、模块一：ReAct Agent CLI

### 2.1 架构

```
Main → Agent.run() → LlmClient.chatRaw() → 5 个 Tool
         ↑                    ↑                ↑
    对话历史累积         HTTP ↔ DeepSeek     ToolRegistry 管理
```

### 2.2 核心概念

#### ReAct 循环

```
while (turn < MAX_TURNS) {
    reply = llmClient.chatRaw(history, tools);
    if (reply.toolCalls == null) → 退出，返回 content
    else → 执行工具 → 结果回灌 history → 继续下一轮
}
```

**终止条件**：`toolCalls` 为空（LLM 认为任务完成）**且** `MAX_TURNS` 兜底（20轮强制退出）。

#### Tool Calling 协议

LLM **不执行工具**，只返回调用意图：

```json
// LLM 返回
{"tool_calls": [{"function": {"name": "read_file", "arguments": "{\"path\":\"pom.xml\"}"}}]}

// 程序执行：ToolRegistry.get("read_file").execute({"path": "pom.xml"})

// 结果回灌：Message("tool", 文件内容, tool_call_id = "call_xxx")
```

#### 对话历史管理

```java
// Agent 内部维护一个持续累积的 history：
List<Message> history;  // 成员变量，不是局部变量

run("问题1"):
  history = [system, user:"问题1", assistant{回答1}]

run("问题2"):
  history = [system, user:"问题1", assistant{回答1}, user:"问题2", ...]
  // LLM 能看到完整上下文，"这个文件"就知道指什么
```

**为什么必须累积？** LLM 是无状态的——每次请求只看 messages 数组。不维护 history，就丢失上下文。

### 2.3 5 个工具

| 工具 | 给 Agent 的能力 | 安全设计 |
|------|----------------|----------|
| `read_file` | 读文件 | 100KB 上限 |
| `write_file` | 创建/修改文件 | `projectRoot.resolve().toRealPath().startsWith()` 防逃逸 + 5MB 上限 |
| `list_dir` | 看目录 | 最多 200 条，只列一级 |
| `glob_files` | 按模式搜文件 | 跳过 target/.git 等 + 500 条上限 |
| `execute_command` | 跑 shell 命令 | 13 条黑名单 + 60s 超时 + 8K 输出截断 |

### 2.4 面试素材

**问：你的 Agent 安全模型是什么？**

> ReAct 层 + 路径校验 + 命令黑名单 + 审计。对标 Claude Code/Cursor/Aider 的本地 Agent 安全实践——不是沙箱，不提供进程隔离。HITL 审批是真正的防线（第 6 期）。

**问：写文件怎么防路径穿越？**

> 不是"拒绝绝对路径"（太粗暴，测试时 @TempDir 给的也是绝对路径）。真正做法：
> `projectRoot.resolve(path).toRealPath().startsWith(projectRoot.toRealPath())`。
> `toRealPath()` 会解析符号链接，防止 `/tmp/link → /etc` 这类绕过。另外 `normalize()` 消除 `..`。

**问：命令执行怎么防注入？**

> 黑名单是**快速拒绝**，不是安全防线。真的安全在 HITL 审批。面试时要区分 "辅助层" 和 "防线"——黑名单是辅助，审批是防线。另外 ProcessBuilder 传 `List<String>` 而非 `Runtime.exec(String)`，避免 shell 注入时参数被拼接。

**问：LLM 返回的 JSON 你遇到过什么问题？**

> 三个坑：
> 1. markdown 代码块包裹 → extractJson 去掉 `\`\`\`json ... \`\`\``
> 2. 中文双引号未转义（`"包含"Hello"的代码"`）→ repairInnerQuotes 逐字符扫描修复
> 3. 不认识字段抛 UnrecognizedPropertyException → 所有数据类加 `@JsonIgnoreProperties`

---

## 三、模块二：Plan-and-Execute

### 3.1 为什么需要 Plan 模式

**问题**：ReAct 走一步看一步，复杂任务容易漏步骤、走弯路。

**解法**：先让 LLM 拆步骤 → 按 DAG 依赖顺序执行 → 失败时基于已完成进度重规划。

### 3.2 架构

```
用户: /plan 创建一个 Spring Boot 项目
        │
   阶段 1：Planner.plan()        ← 调 LLM 拆任务，生成 Task + DAG 依赖
        │
   阶段 2：PlanExecuteAgent 循环
        │   getReadyTasks()  ← 动态找"依赖已满足"的 Task
        │   每个 Task → 独立子 Agent（独立 history）
        │   Task 失败 → replan() 基于已完成进度重新规划（最多 2 次）
        │
   阶段 3：buildReport()         ← 汇总 ✅❌⬜
```

### 3.3 核心设计决策

#### Task 不绑定工具

Task 的 `description` 是自然语言目标，不是工具调用。因为规划阶段 LLM 不知道文件具体内容，不可能准确写出 `read_file("src/main/java/Book.java")`。

#### 动态就绪集，不做拓扑排序

```java
// 每轮动态计算，不一次性排序整张图
getReadyTasks():
  对每个 PENDING task：
    if (它的所有依赖都是 COMPLETED) → 加入就绪列表
```

**好处**：失败 Task 可以重试、支持重规划追加新 Task、代码简单（两层 for 循环）。

#### Task 独立子 Agent + 上下文注入

每个 Task 启动独立的 `subAgent`（独立 history），完成后通过 `injectContext()` 把结果注入主 Agent。解决了"子 Agent 干了什么主 Agent 不知道"的问题。

#### 重规划：保留已完成的，重做失败的

```
task_0 ✅ → task_1 ❌ → 触发重规划
  → 保留 task_0(COMPLETED) + task_1(FAILED)
  → 移除 task_2, task_3...(PENDING)
  → 发给 LLM："task_1 失败了，原因：javac 不在 PATH。请基于已完成进度规划替代方案"
  → LLM 返回 task_r0, task_r1...（新 id 避免冲突）
  → 继续执行
最大 2 次重规划，防止死循环
```

### 3.4 环检测：visited + recStack DFS

```java
// 面试标准写法
hasCycle():
  visited = new HashSet<>()    // 所有访问过的节点
  recStack = new HashSet<>()   // 当前 DFS 路径上的节点

  dfs(node):
    visited.add(node)
    recStack.add(node)          // 入栈
    for (邻居)：
      if (recStack.contains(邻居)) → 有环（邻居还在路径上 = 回边）
      if (!visited.contains(邻居)) → dfs(邻居)
    recStack.remove(node)       // 回溯：出栈
```

**复杂度** O(V+E)，LLM 规划的任务通常 ≤10 个，瞬时完成。

### 3.5 面试素材

**问：ReAct 和 Plan-and-Execute 怎么选？**

> 简单任务（单步操作、读改文件）用 ReAct——省一次规划调用（2-5K token）。复杂多步任务（创建项目、重构）用 Plan——先谋后动，不容易漏步骤。生产环境主流是混合模式：简单走 ReAct，复杂切 Plan。

**问：Plan 执行中某一步失败了怎么办？**

> 不是直接报错。保留已完成的步骤，把失败原因+剩余目标发给 LLM 重规划。最大 2 次。2 次全失败则输出精确阻塞诊断（谁在等谁、为什么等），建议人工干预。这样做的好处是：不用从头开始，已完成的磁盘操作不会浪费。

**问：为什么不用拓扑排序？**

> 拓扑排序需要一次性确定全图顺序，但 Agent 场景下 Task 可能失败需要重试，动态就绪集 (`getReadyTasks()`) 更灵活。每轮只看"谁可以干了"，天然支持重试和重规划。面试时强调"灵活性 vs 一次性排序"的取舍。

**问：子 Agent 之间怎么保证不互相影响？**

> 每个子 Agent 有独立的 history（独立 `Agent` 实例）。通过 `buildTaskPrompt()` 只传递已完成 Task 的**摘要**（ID + 描述 + 截断结果），不共享工具调用历史。这样做的好处是：子 Agent 聚焦当下任务，不会被其他 Task 的中间过程干扰。

---

## 四、面试能用的一条线串讲

> "我手写了一个 Java Agent CLI，对标 Claude Code。
>
> **第一版**做了 ReAct 循环——LLM 看到用户输入后，在 Think→Act→Observe 循环里决定调用哪些工具。我实现了 5 个内置工具（读文件、写文件、列目录、glob 搜索、执行命令），每个都有安全边界——写文件用 `projectRoot.resolve().toRealPath()` 防路径穿越，命令执行有黑板+超时+输出截断。
>
> 做 LLM JSON 解析时踩过坑：中文双引号不做转义导致 Jackson 解析崩溃，我写了 `repairInnerQuotes` 做逐字符扫描修复。
>
> **第二版**加了 Plan-and-Execute，因为发现复杂任务只靠 ReAct 容易漏步骤。做法是先调 LLM 拆成 DAG 步骤，再按依赖顺序执行。依赖检测用 visited+recStack DFS，执行时每个步骤独立子 Agent。如果某一步失败，不做全盘废弃——保留已完成进度，让 LLM 基于当前状态重规划替代步骤，最大 2 次。做过实际测试：3 个步骤的任务，javac 路径找不到，Agent 自己探索出了绝对路径，7 轮工具调用后成功编译运行。"
