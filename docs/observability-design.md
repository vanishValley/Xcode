# Xcode Agent CLI - 全链路追踪与日志系统设计

> 状态：设计中，尚未落地  
> 适用范围：当前 Java 17 CLI 应用，包括 ReAct Agent、Plan-and-Execute、LLM、Tool、MCP、Memory、Reviewer 和会话持久化。

## 一、背景：需要解决什么问题

Xcode Agent 不是一次请求、一次响应的普通 CLI。一个用户任务可能经历多轮模型调用、工具执行、MCP 通信、上下文压缩、并行子任务、重试和持久化。

当前日志可以看到部分执行过程，但存在以下问题：

| 问题 | 当前表现 | 排查困难 |
|---|---|---|
| 日志是平铺的 | 只能按时间查看 `LLM -> Tool -> LLM` | 无法直观看出父子关系 |
| MDC 由 Agent 手工维护 | 使用 8 位 UUID，结束时 `MDC.clear()` | 嵌套调用和线程池中容易丢失或清错上下文 |
| 并行任务缺少上下文传播 | `CompletableFuture` 在线程池执行 | 子任务日志可能无法关联到原任务 |
| 外部调用缺少统一边界 | LLM、Tool、MCP 各自输出日志 | 不容易判断错误发生在哪一层 |
| 日志语义不稳定 | 有些地方用 Logback，有些地方用 `System.out` | 无法统一检索和治理 |
| 只有结果，没有决策原因 | 例如只看到压缩前后百分比 | 无法判断是执行、跳过、降级还是失败 |
| 内容记录缺少边界 | Prompt、工具参数和结果可能很大或敏感 | 存在日志膨胀和信息泄漏风险 |

本设计的核心目标不是建设监控大盘，而是让开发者在任务失败、结果异常、执行缓慢或上下文失控时，可以沿着一次任务的真实执行轨迹完成定位。

---

## 二、目标与非目标

### 2.1 目标

1. 一次用户任务使用一个 `trace_id`，贯穿同步调用、并行子任务和外部依赖。
2. 使用 Span 表达 Agent、LLM、Tool、MCP、Plan Task 等关键操作的父子关系和耗时。
3. 使用 MDC 将 `trace_id`、`span_id` 自动写入 Logback 日志。
4. 通过结构化字段记录状态、耗时、Token、结果大小、重试和错误原因。
5. 工具失败后即使被 ReAct 捕获并继续执行，仍保留失败节点和恢复过程。
6. 默认不记录完整 Prompt、模型回复、源码、网页快照、密钥等敏感内容。
7. 没有 Jaeger、Docker 或网络环境时，主业务仍可正常运行。

### 2.2 非目标

- 不建设 Grafana、Prometheus、Loki、Tempo 等完整监控平台。
- 不自研在内存中保存完整任务数据的 `RunRecorder`。
- 不记录模型隐藏推理或完整思维过程。
- 不为每一个 Java 方法创建 Span。
- 不把 OpenTelemetry Logs SDK 引入当前范围，应用日志继续使用 SLF4J + Logback。
- 不因为链路追踪而改变 Agent 原有的重试和错误恢复语义。

---

## 三、总体设计

系统分成三部分：

```text
OpenTelemetry Trace
  负责调用链、父子关系、耗时、状态和异常

MDC
  负责把当前 trace_id/span_id 传给日志框架

SLF4J + Logback
  负责记录事件、参数摘要、结果摘要和异常堆栈
```

关系如下：

```text
业务操作开始
  -> 创建 Span
  -> Span 成为当前 Context
  -> trace_id/span_id 写入 MDC
  -> 执行业务逻辑并输出日志
  -> 记录状态或异常
  -> 结束 Span
  -> 恢复父 Context 和父 MDC
```

Trace 回答“错误发生在哪个节点”，日志回答“这个节点具体发生了什么”。

---

## 四、任务链路模型

### 4.1 完整调用树

```text
agent.run
├── plan.create                         # 仅 Plan 模式
├── plan.task                           # 仅 Plan 模式，可并行
│   └── agent.run                       # 子 Agent
│       └── agent.turn
│           ├── context.prepare
│           │   ├── memory.retrieve     # 实际执行检索时创建
│           │   └── memory.compact      # 实际执行压缩时创建
│           ├── llm.chat
│           └── tool.execute
│               └── mcp.call            # 仅 MCP 工具
├── review                              # 实际执行 Review 时创建
├── checkpoint                          # 实际读写检查点时创建
└── session.persist                     # 实际持久化时创建
```

直接 ReAct 模式没有 `plan.create` 和 `plan.task`，从根 `agent.run` 直接进入 `agent.turn`。

### 4.2 Span 与 Event 的边界

满足以下任一条件时创建 Span：

- 有独立耗时，需要分析性能；
- 有独立失败可能；
- 调用了外部服务或进程；
- 跨越线程边界；
- 对任务状态产生重要影响。

瞬时决策使用 Span Event，不额外创建 Span：

```text
retry.scheduled
compaction.skipped
review.replan_requested
tool.result_truncated
```

例如，真正调用 LLM 生成压缩摘要时创建 `memory.compact`；只检查后发现未达到阈值时，不创建 Span。

### 4.3 Span Kind

| Span | Kind | 原因 |
|---|---|---|
| `agent.run`、`agent.turn`、`context.prepare` | `INTERNAL` | 应用内部编排 |
| `plan.task`、`review`、`memory.compact` | `INTERNAL` | 应用内部任务 |
| `llm.chat` | `CLIENT` | 调用外部 HTTP 服务 |
| `mcp.call` | `CLIENT` | 调用外部 MCP Server |
| `tool.execute` | `INTERNAL` | Agent 内部工具抽象，底层可能再调用外部系统 |
| `session.persist` | `INTERNAL` | 当前为本地文件持久化 |

---

## 五、链路追踪模块

新增包：

```text
com.xu.observability
├── Tracing.java
├── TraceScope.java
└── ContextAwareTasks.java
```

### 5.1 `Tracing`

应用级追踪入口，整个进程只创建一个实例，通过构造器注入业务组件。

职责：

- 初始化 `OpenTelemetrySdk` 和 `Tracer`；
- 根据配置启用 `none`、`console` 或 `otlp` Exporter；
- 创建内部 Span 和 Client Span；
- 应用退出时刷新并关闭 SDK。

对外 API：

```java
TraceScope start(String spanName);
TraceScope startClient(String spanName);
void close();
```

`Tracing` 不记录 Agent、LLM、Tool 等业务字段，也不直接写业务日志。

### 5.2 `TraceScope`

代表一个正在执行的操作，封装 `Span + Scope + MDC`。

创建时：

```text
创建 Span
-> 调用 makeCurrent()
-> 保存父 MDC
-> 将当前 trace_id/span_id 写入 MDC
```

执行期间提供：

```java
TraceScope attribute(String key, String value);
TraceScope attribute(String key, long value);
TraceScope attribute(String key, boolean value);
void event(String name);
void fail(Throwable error);
String traceId();
String spanId();
```

关闭时：

```text
关闭 Scope
-> 结束 Span
-> 恢复父 trace_id/span_id
```

`TraceScope` 必须实现 `AutoCloseable`，业务代码统一使用 `try-with-resources`，保证提前 `return` 或抛出异常时 Span 也能结束。

### 5.3 `ContextAwareTasks`

负责包装提交到线程池的 `Runnable` 和 `Supplier<T>`。

调用线程中：

```text
捕获 Context.current()
-> 复制当前 MDC
```

工作线程中：

```text
保存工作线程原上下文
-> 恢复捕获的 OpenTelemetry Context 和 MDC
-> 执行任务
-> finally 恢复工作线程原上下文
```

`PlanExecuteAgent` 向 `CompletableFuture` 提交任务时必须使用该包装器。同步方法调用不需要额外处理，子 Span 会自动继承当前 Context。

---

## 六、业务模块接入

### 6.1 `Main`

```text
启动
-> 创建一个 Tracing
-> 构造 LlmClient、ToolExecutor、MemoryManager、Agent
-> 通过构造器注入同一个 Tracing
-> 运行 CLI
-> 退出时关闭 Tracing
```

不使用遍布代码的静态全局 Tracer，避免初始化顺序不清晰，也方便测试。

### 6.2 `Agent`

`Agent.run()` 创建 `agent.run`，每次 ReAct 循环创建 `agent.turn`。

根 Span 记录：

```text
agent.mode
agent.turn.count
agent.llm.call_count
agent.tool.call_count
agent.recovered_error_count
agent.outcome
```

任务状态定义：

| 状态 | 含义 |
|---|---|
| `SUCCESS` | 目标完整完成；允许存在已经恢复的中间错误 |
| `DEGRADED` | 返回了可用结果，但使用降级方案或结果不完整 |
| `FAILED` | 没有得到可用结果 |
| `CANCELLED` | 用户取消、拒绝或主动终止 |

子工具失败但后续恢复时，工具 Span 为 `ERROR`，根任务仍可为 `SUCCESS`，同时增加 `recovered_error_count`。

### 6.3 `LlmClient`

所有模型请求最终经过 `chatRaw()`，因此在该方法内部创建 `llm.chat`。

记录：

```text
gen_ai.model
gen_ai.input_tokens
gen_ai.output_tokens
gen_ai.finish_reason
http.status_code
llm.message_count
llm.retry_count
```

需要扩展当前响应解析，读取 API 返回的 `usage` 和 `finish_reason`。字符估算仍用于请求发出前的预算判断，真实 `usage` 用于事后观测和校准。

### 6.4 `ToolExecutor`

新增统一工具执行组件，收敛 `Agent` 和 `Reviewer` 中重复的工具执行逻辑。

```text
接收 ToolCall
-> 根据名称查找 Tool
-> 解析 JSON 参数
-> 创建 tool.execute
-> 调用 Tool.execute()
-> 记录结果元数据
-> 返回 ToolExecutionResult
```

建议返回：

```java
record ToolExecutionResult(
        boolean success,
        String content,
        String errorType) {
}
```

普通工具异常转换为失败结果交还 LLM，保留 ReAct 自我修复能力；同时将当前 Span 标记为 `ERROR` 并输出异常日志。

记录字段：

```text
tool.name
tool.call_id
tool.category
tool.result_chars
tool.result_estimated_tokens
tool.result_truncated
tool.status
```

### 6.5 `ChromeMcpClient`

`ToolExecutor` 创建 `tool.execute` 后，MCP Tool 进入 `ChromeMcpClient.callTool()`，该方法创建 `mcp.call`。

记录：

```text
mcp.server
mcp.method
mcp.tool
rpc.request_id
mcp.timeout_ms
mcp.result_chars
```

`StdioJsonRpcClient` 保持 JSON-RPC 协议实现职责，不为每次 stdin/stdout 读写创建 Span。协议异常和超时由 `mcp.call` 记录，异常堆栈仍可定位到底层类。

### 6.6 `MemoryManager` 与 `ConversationCompactor`

每个 `agent.turn` 在调用模型前创建 `context.prepare`。

上下文准备记录：

```text
context.message_count
context.estimated_tokens
context.goal_injected
context.memory_match_count
context.external_context_chars
```

压缩检查必须返回明确结果，而不是只返回修改后的 List：

```java
record CompactionResult(
        Decision decision,
        String reason,
        long beforeTokens,
        long afterTokens,
        int beforeMessages,
        int afterMessages) {
}
```

决策包括：

```text
NOT_REQUIRED
APPLIED
SKIPPED
FALLBACK
FAILED
```

只有实际执行摘要或截断时创建 `memory.compact`；冷却、内容不足等跳过行为记录为 `context.prepare` 的 Event 和属性。

### 6.7 `PlanExecuteAgent`

每个 Plan 子任务创建 `plan.task`。提交线程池之前使用 `ContextAwareTasks.wrap()` 捕获父 Context。

记录：

```text
plan.task.id
plan.task.description_chars
plan.task.attempt
plan.task.dependency_count
plan.task.outcome
```

任务描述全文不作为 Span 属性，避免高基数和敏感内容进入追踪系统。

### 6.8 `Reviewer`、Checkpoint 与 Session

仅在实际执行时创建：

```text
review
checkpoint.load
checkpoint.save
session.load
session.persist
```

本地路径默认记录相对路径或不可逆哈希，不记录用户机器的完整绝对路径。

---

## 七、日志设计

### 7.1 公共字段

Logback Pattern 从 MDC 自动读取：

```text
timestamp
level
trace_id
span_id
task_id
thread
logger
event
message
```

业务字段使用 SLF4J 2 Fluent API 的 `addKeyValue()`：

```java
logger.atInfo()
        .addKeyValue("event", "tool.execute.completed")
        .addKeyValue("tool_name", toolName)
        .addKeyValue("result_chars", result.length())
        .log("工具执行完成");
```

禁止通过字符串拼接模拟结构化字段。

### 7.2 日志级别

| 级别 | 使用场景 |
|---|---|
| `INFO` | Agent/Plan/Task 生命周期和最终汇总、重要状态变化 |
| `WARN` | 可恢复异常、降级、截断、重试、跳过重要操作 |
| `ERROR` | 当前操作失败，需要保留完整异常堆栈 |
| `DEBUG` | 每轮 Agent、成功的 LLM/Tool 调用摘要和内部判断，不记录无限长原文 |

### 7.3 输出位置

```text
控制台
  只保留 CLI 提示、最终回答、Plan 进度和 HITL 审批

~/.xcode/projects/<project-name@path-hash>/logs/xcode.log
  按项目隔离，面向排错，按时间和大小滚动
```

建议 Logback 策略：

```text
单文件上限：20 MB
保存时间：7 天
总大小上限：500 MB
历史文件压缩：gzip
Root 默认级别：INFO
```

需要排查单条链路的内部步骤时，临时设置
`XCODE_LOG_LEVEL=DEBUG` 并重启；排查结束后恢复 `INFO`。

`System.out/System.err` 只用于 CLI 用户交互。Compactor、MCP、Memory 等内部诊断统一使用 logger。

---

## 八、内容安全与大小控制

### 8.1 默认不记录

- 完整用户输入；
- 完整 System Prompt 和模型回复；
- 完整源码、网页快照和工具结果；
- API Key、Authorization、Cookie、密码和环境变量值；
- 模型隐藏推理；
- 用户机器完整绝对路径。

### 8.2 默认记录

- 字符数、消息数和 Token 数；
- 模型名、工具名和 MCP 服务名；
- 相对路径、行范围、退出码；
- 参数的安全摘要；
- 结果大小、是否截断；
- 错误类型和异常堆栈。

### 8.3 诊断模式

为难以复现的语义问题预留本地诊断开关。开启后只保存：

- 脱敏后的 Prompt 头尾片段；
- 脱敏后的工具结果头尾片段；
- 上下文压缩摘要；
- 固定字符上限内的命令输出。

诊断模式默认关闭，不用于长期运行。

---

## 九、错误处理语义

每层只处理自己负责的错误：

```text
LlmClient
  记录 HTTP/解析异常 -> 标记 llm.chat ERROR -> 向上抛出

ChromeMcpClient
  记录 RPC/超时异常 -> 标记 mcp.call ERROR -> 向上抛出

ToolExecutor
  标记 tool.execute ERROR -> 生成失败 ToolExecutionResult
  -> 将错误交还 LLM，允许 ReAct 恢复

Agent
  决定继续、重试、降级或终止

agent.run
  记录最终任务结果，不用最后一个子 Span 的状态机械决定根状态
```

一个异常只在最了解语义的边界输出一条 ERROR 日志，避免每层重复打印同一堆栈。上层如果只是补充任务状态，应记录结构化字段而不是重复打印异常。

---

## 十、配置与运行

建议支持：

```text
OTEL_SERVICE_NAME=xcode-agent
OTEL_TRACES_EXPORTER=none|console|otlp
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
XCODE_DIAGNOSTIC_CAPTURE=false
```

运行方式：

| 场景 | 配置 | 外部服务 |
|---|---|---|
| 普通本地运行 | `OTEL_TRACES_EXPORTER=none` | 不需要 |
| 开发验证 Span | `OTEL_TRACES_EXPORTER=console` | 不需要 |
| 图形化调用链 | `OTEL_TRACES_EXPORTER=otlp` | 可选 Jaeger |

Jaeger 和 Docker 都不是应用启动的前置条件。

---

## 十一、代码注释规范

注释解释生命周期、线程上下文和设计原因，不复述代码。

需要注释：

```java
// 必须在提交线程中捕获 Context；进入工作线程后再获取将得到空上下文。
Context captured = Context.current();
```

```java
// 子 Span 结束后恢复父 MDC，不能使用 MDC.clear()，
// 否则会破坏仍在执行的外层链路。
restoreMdc(previousTraceId, previousSpanId);
```

```java
// 工具异常属于可恢复错误：保留失败 Span 后将错误交还模型，
// 由下一轮 ReAct 决定重试、换工具或终止。
return ToolExecutionResult.failure(message);
```

不需要注释：

```java
// 设置工具名
span.setAttribute("tool.name", toolName);

// 结束 Span
span.end();
```

公共基础类需要 JavaDoc，说明职责、生命周期、线程安全性和关闭要求。

---

## 十二、测试方案

### 12.1 单元测试

`TraceScopeTest`：

- 根 Span 能生成有效 `trace_id/span_id`；
- 子 Span 与父 Span 使用同一个 `trace_id`；
- 子 Scope 关闭后 MDC 恢复为父 Span；
- 异常时 Span 状态为 `ERROR`；
- 重复调用 `close()` 不产生副作用。

`ContextAwareTasksTest`：

- `CompletableFuture` 中能够读取父 Context；
- 并行任务共享 `trace_id`、使用不同 `span_id`；
- 任务结束后线程池原有 MDC 被恢复；
- 一个任务的 MDC 不泄漏到下一个任务。

`ToolExecutorTest`：

- 成功工具返回成功结果；
- 工具不存在、参数解析失败和执行异常均产生失败结果；
- 工具错误交还 Agent，不直接破坏 ReAct 循环；
- 日志和 Span 不包含完整敏感参数。

`CompactionResultTest`：

- 未达到阈值、冷却、执行、降级和失败均返回明确结果；
- `beforeTokens/afterTokens` 与实际 history 修改一致。

### 12.2 集成验收

至少覆盖：

1. 正常读取、修改代码并执行测试；
2. 工具第一次失败、下一轮修复成功；
3. LLM 请求超时；
4. MCP 调用超时或服务进程退出；
5. Plan 模式并行执行多个任务；
6. 长上下文触发压缩；
7. Reviewer 触发重试或重新规划；
8. 会话持久化失败；
9. Agent 达到最大 ReAct 轮数。

验收标准：

```text
能够通过 trace_id 关联整次任务日志；
调用树父子关系正确；
能够定位第一个未恢复错误；
能够区分任务失败、降级和已恢复错误；
敏感内容不进入默认日志；
关闭 Trace 导出后不影响业务执行。
```

---

## 十三、实施顺序

```text
1. Tracing + TraceScope + MDC
2. Logback 格式和滚动策略
3. agent.run + agent.turn
4. llm.chat
5. ToolExecutor + tool.execute
6. mcp.call
7. PlanExecuteAgent 异步上下文传播
8. context.prepare + Memory + CompactionResult
9. Review、Checkpoint、Session
10. 脱敏、诊断模式和集成测试
```

每一步应保持项目可编译、原有测试通过，并新增对应测试。先使用 Console Exporter 验证父子关系，再决定是否接入 Jaeger。

---

## 十四、风险与取舍

### 14.1 手动埋点遗漏

当前项目没有 Spring AOP，采用显式 `TraceScope`。优点是边界清楚、易于理解；风险是新增业务路径时可能遗漏。通过统一组件入口（`LlmClient`、`ToolExecutor`、`ChromeMcpClient`）降低遗漏概率。

### 14.2 Span 数量过多

不追踪每个普通 Java 方法；只有独立耗时、错误、外部调用、线程边界和重要状态变化才创建 Span，其余使用属性或 Event。

### 14.3 日志与 Trace 重复

Trace 保存结构和耗时，日志保存证据。正常操作原则上只输出一条完成摘要，不同时输出大量开始、结束日志。

### 14.4 Token 统计不准确

请求前继续使用字符估算保证安全余量；请求后读取模型 API 的真实 `usage`。两者用途不同，不互相替代。

### 14.5 Exporter 影响主流程

Trace 导出必须异步、可关闭、失败不影响业务。没有后端时使用 `none`，不能因为 Jaeger 未启动导致 Agent 无法工作。

---

## 十五、排错流程

```text
用户反馈问题
-> 获取 trace_id
-> 查看 agent.run 最终状态
-> 沿调用树找到最早的未恢复 ERROR 或最长 Span
-> 使用 span_id 检索对应结构化日志
-> 使用 tool.call_id 关联 LLM 决策与工具执行
-> 判断是业务决策、外部依赖、上下文、并发还是持久化问题
-> 默认信息不足时，针对单次复现开启诊断模式
```

没有 Jaeger 时，仍可按 `trace_id` 检索当前项目目录下的
`~/.xcode/projects/<project-name@path-hash>/logs/xcode.log`，根据
`span_id` 和事件时间还原轨迹；Jaeger 只是让父子关系和耗时展示得更直观。

---

## 十六、待评审问题

正式编码前需要确认：

1. 是否统一抽取 `ToolExecutor`，让 Agent 和 Reviewer 共用；
2. `context.prepare` 是每轮固定创建，还是仅在发生记忆检索/压缩时创建；
3. 本地文件日志使用稳定的 `key=value`，还是额外引入 JSON Encoder；
4. 诊断模式是否在本次范围内实现，还是只预留配置；
5. Jaeger 是否作为项目演示能力提供单独启动说明；
6. 当前 ConversationCompactor 的 ReAct 轮次切分问题是否与可观测性改造一起修复，还是单独提交。

这些问题不影响总体架构，但会影响首批代码的改造范围和提交拆分。
