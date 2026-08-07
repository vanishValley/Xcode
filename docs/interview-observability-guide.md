# Coding Agent 全链路追踪面试手册

对应简历：

> 设计并实现 Coding Agent 全链路追踪能力：基于 OpenTelemetry Span + MDC 贯通 Plan、Agent、LLM、Tool/MCP 调用，解决线程池异步上下文传播，通过统一 trace_id、结构化日志及耗时、Token、异常统计支持故障定位。

本文只描述当前代码已经实现的能力，不把设计文档中的后续规划当成现状。

## 一、先记住面试主线

不要从 OpenTelemetry 的定义开始背。按照下面五步讲：

1. **场景**：一次 Coding Agent 任务会经过 Plan、并行子任务、ReAct 多轮、LLM、Tool、MCP 和 Reviewer，日志天然会交错。
2. **问题**：原来的控制台输出只能看到局部信息，无法回答“这些日志是否属于同一次任务、故障发生在哪一步、是否被后续恢复、成本主要花在哪里”。
3. **目标**：用一条 trace 串起整次任务，用 Span 表达父子结构，用结构化日志保存排障证据。
4. **难点**：Plan Worker 在线程池并行执行，而 OpenTelemetry Context 和 MDC 都是线程绑定的，默认不会跟随任务进入线程池。
5. **结果与边界**：当前可以按 trace_id 定位执行、依赖和成本类问题；默认本地落盘、无需部署 Jaeger。它不是 Prometheus/Grafana 监控平台，也没有完整保存 Prompt 正文。

## 二、30 秒讲法

我的 Coding Agent 同时支持普通 ReAct 和 Plan-and-Execute。一次复杂任务会拆成并行子任务，每个子任务内部又会多轮调用 LLM、Tool 和 MCP，原来分散的控制台日志很难关联。因此我引入 OpenTelemetry，把一次用户任务建模为一个 Trace，把 Agent、轮次、LLM、工具和 MCP 调用建模为父子 Span；同时把当前 trace_id 和 span_id 注入 MDC，让普通结构化日志自动携带链路标识。Plan Worker 跨线程时，我封装了 ContextAwareTasks，在提交线程捕获 OpenTelemetry Context 和 MDC，在工作线程恢复并在 finally 中清理，避免链路断裂和线程池污染。最终可以按 trace_id 查看整次任务，并结合耗时、Token、工具退出码和异常状态定位故障。

## 三、2 分钟讲法

### 1. 为什么做

这个项目不是一次 HTTP 请求对应一条简单调用链。Plan 模式会先调用模型规划 DAG，再把就绪任务并行提交到线程池；每个 Worker 是独立的 ReAct Agent，会继续调用 LLM、Java 本地工具或者 MCP 工具，之后还有 Reviewer 验收和可能的重做。

如果只使用 `System.out.println`，并行任务的输出会交错，也无法区分某个工具错误最终是导致任务失败，还是被 Agent 下一轮修复了。因此我需要同时记录：

- 整次任务属于哪条链路；
- 当前位于哪个 Plan Task 和哪个操作节点；
- 调用关系、耗时和最终状态；
- LLM Token、工具调用次数、退出码、超时和异常类型。

### 2. 怎么设计

我把模块分成四层：

- `Tracing`：进程级 OpenTelemetry SDK 和 Tracer，只初始化一次，通过构造器注入各组件；
- `TraceScope`：封装 Span、OpenTelemetry Scope、耗时计算和 MDC 同步，统一用 try-with-resources；
- `ContextAwareTasks`：在线程池边界同时传播 OTel Context 和 MDC；
- SLF4J + Logback：输出稳定的 key-value 结构化日志，并负责按项目隔离和滚动保存。

实际调用树是：

```text
普通 ReAct：
agent.run
└── agent.turn
    ├── llm.chat            CLIENT
    └── tool.execute        INTERNAL
        └── mcp.call        CLIENT，仅 MCP 工具

Plan 模式：
agent.run                   属性 agent.mode=PLAN
├── llm.chat                Planner 的模型调用
└── plan.task               可并行，每个 Task 一个
    ├── agent.run           Worker Agent
    │   └── agent.turn
    │       ├── llm.chat
    │       └── tool.execute
    │           └── mcp.call
    └── review
        ├── llm.chat
        └── tool.execute    Reviewer 的只读验证工具
```

`agent.run`、`agent.turn`、`plan.task`、`review` 和 `tool.execute` 是应用内部操作，使用 INTERNAL Span；LLM 和 MCP 是外部依赖调用，使用 CLIENT Span。

### 3. 最难的点

OpenTelemetry 当前 Context 和 SLF4J MDC 本质上都与当前线程绑定。Plan 使用线程池时，提交线程中的 trace_id 不会自动出现在 Worker 线程。

我的处理是：

```text
提交任务时：
  捕获 Context.current()
  复制 MDC.getCopyOfContextMap()

工作线程执行时：
  保存线程原来的 MDC
  context.makeCurrent()
  设置捕获到的 MDC
  执行业务

finally：
  关闭 Scope
  恢复工作线程原来的 MDC
```

这里不能只 `MDC.clear()`，因为线程可能嵌套执行别的上下文；也必须在提交线程捕获，否则进入 Worker 后拿到的已经是空 Context。

### 4. 最终能解决什么

一次任务的日志都带有相同 trace_id；Plan 子任务再通过 task_id 区分。Span 保存父子关系、耗时、属性和状态，日志保存事件证据。可以定位：

- 哪个 Task 首先失败或降级；
- 某次 LLM 请求是否超时、Token 是否异常；
- 哪个工具失败，命令退出码是否非零；
- MCP 服务端是否返回错误或超时；
- Agent 是否达到最大轮数，以及工具错误是否被后续恢复。

默认 exporter 是 `none`，因此没有 Jaeger 也不影响业务，仍可检索项目独立日志。需要可视化时，通过标准 OTLP 环境变量接 Jaeger 或 Tempo，不改业务代码。

## 四、把简历上的每个词对应到代码

| 简历表述 | 当前实现证据 | 面试时怎么解释 |
|---|---|---|
| OpenTelemetry Span | `Tracing`、`TraceScope` | OTel 维护 Trace/Span 父子关系和状态 |
| MDC | `TraceScope.open/close`、`MdcScope` | 将当前 trace_id/span_id/task_id 注入普通日志 |
| Plan | `PlanExecuteAgent` 的根 `agent.run`、`plan.task` | Plan 模式根 Span 仍叫 agent.run，通过 `agent.mode=PLAN` 区分 |
| Agent | `Agent.runDetailed`、`agent.turn` | 一次任务和每轮 ReAct 分层建模 |
| LLM | `LlmClient` 的 `llm.chat` CLIENT Span | 记录模型、消息数量、HTTP 状态、Token、耗时和异常 |
| Tool | `ToolExecutor` 的 `tool.execute` Span | 统一覆盖本地工具、错误分类、结果长度、退出码和超时 |
| MCP | `McpClient` 的 `mcp.call` CLIENT Span | 当前追踪到 MCP 客户端边界，尚未把 trace context 注入 MCP Server |
| 异步传播 | `ContextAwareTasks` | 同时传播 OTel Context 和 MDC，并恢复线程原值 |
| 结构化日志 | SLF4J Fluent API + Logback `%kvp` | 当前是 key-value 日志，不是 JSON Encoder |
| Token 统计 | LLM API usage、`Agent.RunResult`、Plan Worker 汇总 | 单次 LLM 是真实 usage；Plan 总结目前主要聚合 Worker 数据 |

## 五、现场排障案例怎么讲

可以用下面这个例子，不需要编造性能提升百分比。

### 场景

用户执行一个 Plan 任务，表面现象是任务运行很久后降级完成。旧控制台只能看到大量模型和工具输出，无法判断是模型慢、工具失败还是 Agent 不收敛。

### 排查步骤

1. 从 `plan.run.completed outcome=DEGRADED` 获取 trace_id；
2. 按 trace_id 筛选整次任务，查看各个 `plan.task.completed`；
3. 通过 task_id 找到降级的子任务；
4. 查看其 `agent.run.max_turns`、`llm_calls`、`tool_calls` 和 Token；
5. 如果存在 `tool.execute.failed`，继续查看 `error_type`、`exit_code`、`timed_out`；
6. 判断该错误是否被恢复：工具 Span 可以是 ERROR，但如果后续 Agent 给出正确结果，根 `agent.run` 仍可能 SUCCESS，同时 `recovered_errors > 0`；
7. 如果 INFO 信息不足，针对复现临时开启 DEBUG，查看每次成功 LLM/Tool 的耗时摘要。

### 结论表达

这套设计的价值不是“日志更多”，而是能区分故障位置和最终影响：同一个工具错误可能是可恢复错误，也可能是整次任务失败的根因，不能看到 ERROR 就直接下结论。

## 六、高频问题与参考回答

### A. 背景与方案选择

#### 1. 为什么不用 `System.out.println`？

`System.out` 只有文本和时间顺序，没有稳定字段、级别、滚动策略和上下文关联。Plan 并行后输出会交错，无法可靠判断某行属于哪个任务。SLF4J + MDC 可以自动携带链路字段，Logback 负责级别和持久化。

#### 2. 单体 CLI 项目为什么需要链路追踪？

分布式不是 Trace 的前提。这个单进程内部存在异步 DAG、多个 Agent、外部 LLM、子进程工具和 MCP 调用，复杂度已经形成调用图。我的目标是定位 Agent 执行链，而不是为了套微服务技术。

#### 3. 这是不是过度设计？

我控制了部署复杂度：默认只使用本地文件和 OTel SDK，不要求 Docker、Jaeger 或 Grafana；埋点只放在独立耗时、失败、外部调用和线程边界，不追踪普通方法。对于多轮、并行且有副作用的 Agent，这个粒度是必要的。

#### 4. 为什么不能只有日志？

日志擅长记录“发生了什么”，但不会天然保存严格的父子关系、Span 状态和统一耗时模型。并行场景仅靠时间排序容易误判。Trace 保存结构，日志保存证据，两者通过 trace_id/span_id 关联。

#### 5. 为什么不能只有 OpenTelemetry？

Span 属性适合有限、稳定的元数据，不适合承载大量异常上下文和人类可读事件。并且默认没有 Trace 后端时，本地日志仍然必须可排障。

#### 6. 为什么没有直接上 Jaeger/Grafana？

当前是本地 Coding Agent，首要目标是链路正确和排障可用，而不是搭监控平台。OTel 与后端解耦，默认 exporter 为 none；以后配置 OTLP 即可接 Jaeger/Tempo。Grafana 更适合聚合看板，不是单次问题定位的必要条件。

#### 7. “全链路”具体覆盖到哪里？

覆盖当前进程内的 Plan、Worker Agent、ReAct Turn、LLM、Tool、Reviewer，以及 MCP 客户端调用边界。当前没有把 W3C Trace Context 继续注入 MCP Server，所以不能声称已经追踪 MCP Server 内部实现。

#### 8. 为什么不用 AOP 或 OpenTelemetry Java Agent 自动埋点？

自动埋点能捕获 HTTP、线程池等通用调用，却不知道 `plan.task`、`agent.turn`、恢复错误和降级结果这些业务语义。项目也没有 Spring。当前使用显式埋点，并集中在 `LlmClient`、`ToolExecutor`、`McpClient` 等统一入口，边界更可控。

### B. Trace 与 Span

#### 9. Trace、Span、trace_id、span_id 分别是什么？

Trace 表示一次完整用户任务；Span 是其中一个操作节点。整条 Trace 共享一个 128-bit trace_id，每个 Span 有自己的 64-bit span_id，并保存 parent span，从而组成调用树。

#### 10. task_id 和 trace_id 是否重复？

不重复。trace_id 表示整次用户任务；同一条 Plan Trace 内可能并行执行多个业务子任务，task_id 用来区分具体步骤。task_id 是业务标识，不替代 Span 父子关系。

#### 11. 为什么不再设计 run_id？

当前一次前台任务就是一条 Trace，trace_id 已经承担 run_id 的关联职责。再维护一套 run_id 会增加映射和日志字段，收益不大。

#### 12. trace_id 是怎么生成的？

根 `agent.run` Span 创建时由 OpenTelemetry SDK 生成。子 Span 在当前 Context 下创建，会继承同一个 trace_id 并生成新的 span_id。

#### 13. Span 父子关系怎么形成？

`TraceScope.open` 创建 Span 后调用 `span.makeCurrent()`。在 Scope 关闭前创建的新 Span 会从 `Context.current()` 获取当前 Span 作为父节点。线程池场景则先恢复捕获的父 Context。

#### 14. Span Kind 为什么区分 INTERNAL 和 CLIENT？

Agent、Plan、Review、Tool 抽象属于进程内部编排，使用 INTERNAL；LLM HTTP 和 MCP 是对外部依赖的调用，使用 CLIENT。这样接入后端后可以区分内部耗时和依赖耗时。

#### 15. Attribute、Event、Status 有什么区别？

Attribute 描述 Span 的稳定属性，例如模型、Token 和任务结果；Event 表示 Span 内某个瞬时动作；Status 表示最终成功或错误。当前异常使用 `recordException + ERROR`，无 Java 异常的协议错误使用 `scope.error`。

#### 16. 为什么 Span 名称不用 task ID 或工具名？

Span 名称要稳定、低基数，便于聚合，所以使用 `agent.run`、`tool.execute` 等固定名称。task ID、工具名放 Attribute；否则每个动态名称都会形成新的指标维度。

#### 17. 为什么封装 `TraceScope`？

直接散落使用 OTel API 容易忘记 `end()`、异常状态和 MDC 恢复。`TraceScope` 把 Span、Scope、MDC、耗时和异常处理统一起来，并通过 try-with-resources 保证退出路径一致。

#### 18. 为什么 Plan 模式根 Span 仍然叫 `agent.run`？

从用户视角它仍是一次 Agent 任务，使用 `agent.mode=PLAN` 区分执行模式；子步骤使用 `plan.task`。这样普通模式和 Plan 模式可以按同一个根操作查询。

#### 19. 工具失败后为什么根 Span 还可能成功？

ReAct 会把失败结果回灌给模型，下一轮可能换参数或换工具恢复。因此工具 Span 标记 ERROR，但 Agent 根 Span 根据最终结果决定 SUCCESS/DEGRADED/FAILED，同时记录 `recovered_errors`。这比“出现任何异常就判整次失败”更符合 Agent 语义。

### C. MDC、ThreadLocal 与异步传播

#### 20. MDC 是什么？

MDC 是日志框架提供的线程上下文 Map。把 trace_id、span_id、task_id 放入 MDC 后，业务日志不必每次手动传这些参数，Logback pattern 会自动输出。

#### 21. MDC 和 ThreadLocal 有什么关系？

MDC 的常见实现底层就是 ThreadLocal Map，但它是面向日志的标准 API，已经与 SLF4J/Logback 集成。它不适合保存任意业务状态，也不会自动解决跨线程传播。

#### 22. 为什么 OpenTelemetry Context 和 MDC 都要传播？

只传播 OTel Context，子 Span 父子关系正确，但普通日志没有 MDC 字段；只传播 MDC，日志 ID 看起来相同，但新 Span 不知道真正父节点。两者职责不同，必须一起传播。

#### 23. 线程池为什么会丢上下文？

任务提交线程和 Worker 线程不是同一线程，而且线程池中的线程通常早已创建。ThreadLocal 值不会随着 Runnable 自动复制，因此 Worker 的 `Context.current()` 和 MDC 默认是空的。

#### 24. 为什么不用 `InheritableThreadLocal`？

它只在线程创建时从父线程复制，线程池线程会被长期复用，创建时往往还没有本次任务上下文，而且容易串数据。它不适合 Executor 场景。

#### 25. `ContextAwareTasks` 具体做了什么？

在提交线程捕获 `Context.current()` 和 MDC 副本，包装 Runnable/Supplier；工作线程执行时通过 `makeCurrent()` 恢复 OTel Context、替换 MDC，最后恢复该线程原来的 MDC。

#### 26. 为什么必须在提交线程调用 `wrap()`？

进入 Worker 后再捕获，拿到的是 Worker 自己的空上下文，父 Span 已经丢失。传播的核心是提交时快照，而不是工作时查询。

#### 27. 为什么 finally 中是恢复旧值，而不只是 clear？

线程可能本来就处于另一个合法上下文，或者发生嵌套包装。直接 clear 会破坏外层环境；恢复进入前的值才满足作用域语义。

#### 28. 怎么避免线程池污染？

工作前保存线程自己的 MDC，工作后在 finally 恢复；OTel Scope 也通过 try-with-resources 关闭。测试使用同一个单线程执行器执行第二个任务，验证 trace_id/task_id 都为空。

#### 29. 为什么不用阿里的 TransmittableThreadLocal？

TTL 可以传播 ThreadLocal，但仍然需要处理 OTel Context，并引入额外依赖。项目的异步边界集中在 Plan 调度，用一个显式 wrapper 同时处理两套上下文更简单。线程模型扩大后可以再评估 TTL 或统一 Executor 包装。

#### 30. 如果以后使用 Reactor 怎么办？

不能继续依赖普通 ThreadLocal，因为响应式执行会频繁换线程。需要使用 Reactor Context，并通过 OpenTelemetry 的 Reactor instrumentation 做桥接。当前项目是 Java 17 + ExecutorService，不声称覆盖 Reactor。

### D. 结构化日志

#### 31. 你的“结构化日志”是 JSON 吗？

当前不是严格 JSON，而是 SLF4J 2 Fluent API 的稳定 key-value 字段，由 Logback `%kvp` 输出。它兼顾本地可读性和机器解析；接入 Loki/ELK 时可以换 JSON Encoder，而不改业务埋点。

#### 32. 日志里有哪些固定字段？

基础字段有 timestamp、level、trace_id、span_id、task_id、thread、logger、message 和 event；业务事件再附加 model、duration_ms、Token、tool_name、error_type、exit_code 等。

#### 33. 日志级别怎么划分？

INFO 保存 Agent/Plan/Task 生命周期和汇总；DEBUG 保存成功的每次 LLM/Tool 和轮次摘要；WARN 表示可恢复错误、降级、截断；ERROR 表示当前操作失败。默认 INFO，通过 `XCODE_LOG_LEVEL` 调整。

#### 34. 为什么成功的 LLM/Tool 放 DEBUG？

一次 Plan 可能有大量模型和工具调用，全部放 INFO 会淹没任务边界。INFO 应该让人快速看到任务状态，需要逐调用分析时再开启 DEBUG。

#### 35. 是否记录完整 Prompt、工具参数和结果？

核心链路默认只记录数量、字符长度、Token 和错误类型，不记录 Prompt、源码和工具正文，避免日志膨胀和敏感信息泄露。需要分析模型语义时，目前主要通过复现开启诊断；独立的失败快照仍是可改进项。

#### 36. 如何避免日志无限增长？

Logback 同时按日期和大小滚动：单文件 20MB，保留 7 天，总上限 500MB，历史 gzip 压缩。

#### 37. 多个项目的日志怎么隔离？

对标准化后的项目绝对路径计算 SHA-256 并截取 16 位，与项目名组成目录名。日志落在 `~/.xcode/projects/<name@hash>/logs/xcode.log`，同名但不同路径的项目不会混用。

#### 38. 并行日志顺序交错怎么办？

并行日志本来就不应该只靠行顺序理解。先按 trace_id 限定整次任务，再按 task_id/span_id 分组，最后结合时间和 Span 父子关系分析。

### E. 耗时、Token 与错误统计

#### 39. 耗时怎么计算？

`TraceScope` 使用 `System.nanoTime()` 计算持续时间，适合测 elapsed time，不受系统时钟回拨影响；日志时间戳仍由 Logback 输出墙上时间。

#### 40. Token 是估算的还是真实的？

请求后的统计读取模型 API 返回的 usage，是实际计费口径；上下文压缩前的预算判断仍使用本地估算，两者用途不同。usage 字段只保存在本地 Message 观测字段中，并用 `@JsonIgnore` 防止进入下一轮模型请求。

#### 41. Plan 汇总是否包含 Planner 和 Reviewer 的全部 Token？

每次 Planner/Reviewer 的 LLM 调用都有自己的 `llm.chat` Span 和 usage；当前 `plan.run.completed` 中明确使用 `worker_*` 字段，主要聚合 Worker Agent。若要做精确总成本，需要增加 Trace 级 Metrics Collector，这是现有边界，不能说已经完成。

#### 42. 这些是 Prometheus Metrics 吗？

不是。当前是单次 Span 属性、结构化日志字段和 RunResult 汇总，适合单次任务排障；还没有 Micrometer/Prometheus 的时间序列指标、分位数和告警。

#### 43. 如何区分失败、降级和恢复？

FAILED 表示任务无法继续或结果不可信；DEGRADED 表示达到最大轮次、审查未完全通过等情况下保留部分结果；工具失败后被后续修复则根任务可 SUCCESS，并通过 recovered_errors 保留事实。

#### 44. 为什么命令退出码要结构化？

旧实现只返回“退出码：1”的字符串，上层仍可能把它当作工具成功。现在 `ToolExecutionResult` 显式包含 success、errorType、exitCode 和 timedOut，日志和 Agent 恢复逻辑不需要解析文本猜状态。

#### 补充：Task 和 Plan 汇总会不会额外调用 LLM？

不会。`Agent.RunResult` 在执行过程中直接累计 turns、llmCalls、toolCalls、recoveredErrors 和 Token，Plan 再确定性地汇总这些字段。生成观测日志本身不会增加模型成本，也不会让模型“自报”执行数据。

### F. OpenTelemetry 工程问题

#### 45. OpenTelemetry 是 AOP 吗？

不是。它是一套厂商中立的 Observability API、SDK、上下文传播协议和 Exporter 生态。它可以配合 Java Agent 自动埋点，但本项目主要使用手动 Span。

#### 46. 默认没有 Exporter，为什么仍有 trace_id？

SDK 仍会创建 Span 和 SpanContext，`none` 只是“不导出”。因此本地日志仍能带 trace_id；配置 OTLP 后，同一套 Span 才会被发送到后端。

#### 47. 如何接 Jaeger 或 Tempo？

设置标准环境变量，例如 `OTEL_TRACES_EXPORTER=otlp`、`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`。业务代码不直接依赖 Jaeger SDK。

#### 48. Exporter 故障会不会影响 Agent？

默认不导出，因此没有该依赖。接 OTLP 后应使用 SDK 的异步批量导出和有限队列，导出失败只丢观测数据，不应反向中断业务。当前没有做外部后端故障压测，面试中不要说“已经验证零影响”。

#### 49. 是否做了采样？

当前本地低流量项目没有自定义采样，主要保留完整 Trace。生产高流量场景会使用 ParentBased + TraceIdRatio，并对错误/高耗时链路做保留策略。

#### 50. 性能开销是多少？

当前通过固定低基数 Span、INFO 默认级别和默认不导出来控制开销，但没有做正式基准测试，不能给出虚构百分比。生产化前应压测 Span 创建、MDC copy 和 Exporter 队列成本。

#### 51. 为什么不用动态 Span 名？

动态名称会造成高基数，影响存储和聚合。操作名稳定，动态信息放 Attribute，是 OTel 的常规设计。

### G. 测试与可靠性

#### 52. 怎么验证父子关系正确？

使用 `InMemorySpanExporter` 创建测试 SDK，执行根 Span 和子 Span，断言 trace_id 相同、span_id 不同，并断言导出的子 Span parentSpanId 等于根 spanId。

#### 53. 怎么验证异常状态？

调用 `scope.fail(exception)` 后，断言 Span StatusCode 为 ERROR，并包含 exception Event。

#### 54. 怎么验证异步传播没有串任务？

用单线程 Executor 执行包装任务，断言 Worker 获得父 trace_id 和 task_id；任务结束后复用同一个线程执行第二个任务，断言 MDC 已恢复为空。

#### 55. 怎么验证统计字段不会污染模型协议？

Token 和 finishReason 是本地 Message 字段，使用 `@JsonIgnore`。单元测试序列化 Message，断言这些字段不会出现在发送给模型的 JSON 中。

### H. 限制、反问与生产演进

#### 56. 当前方案最大的不足是什么？

第一，默认不保存完整失败现场，模型选错工具这类语义问题可能需要复现；第二，Plan 根汇总主要是 Worker 成本，不是完整 Trace 成本；第三，没有部署 Trace 后端，单靠日志不能精确可视化并行 Span 的 parent 关系；第四，尚未把 Trace Context 传播进 MCP Server 内部。

#### 57. 如果继续完善，优先做什么？

先做按 trace_id 保存的、限大小且可脱敏的失败快照；再做 Trace 级 Metrics Collector，统一聚合 Planner、Worker、Reviewer；需要多人或多实例运行时再接 OTLP + Tempo/Jaeger 和日志集中检索。

#### 58. 没有 Jaeger 时能否看调用链？

可以按 trace_id 查看同次任务日志，并结合 event、task_id、span_id 和时间定位；但日志没有完整 parentSpanId，复杂并行树无法像 Jaeger 那样精确可视化。因此应说“可以排障”，不要说“本地日志等价于 Jaeger”。

#### 59. 为什么不把 task_id 放到 OTel Baggage？

当前是单进程业务字段，MDC + plan.task Attribute 已经足够。Baggage 会沿远程边界传播，还要考虑大小、敏感信息和信任边界；真正跨服务时再选择性使用。

#### 60. 新增业务路径会不会漏埋点？

手动埋点确实有遗漏风险。我通过统一入口降低风险：所有模型走 LlmClient、工具走 ToolExecutor、MCP 走 McpClient；再通过 Span 单测和集成场景验收。规模扩大后可叠加 Java Agent 捕获通用依赖。

#### 61. 面试官说“你这不就是日志加 trace_id”怎么办？

如果只是日志加 ID，不会有真实 parentSpanId、Span Kind、异常 Status、跨线程 OTel Context，也不能通过 OTLP 导出调用树。这个模块同时解决结构、上下文传播和日志关联，MDC 只是最后一公里。

#### 62. 这个模块最体现技术深度的点是什么？

不是引入依赖，而是把 Agent 的业务语义映射为合理 Span 边界，并正确处理并行线程池传播、恢复错误和最终失败的区别。可以重点展示 `ContextAwareTasks` 和 `ToolExecutionResult` 的错误语义。

#### 63. 你从改造中发现过什么真实问题？

一个典型问题是命令工具原先把包含非零退出码的文本当作成功返回，日志会显示“工具完成”。改成结构化 ToolExecutionResult 后，非零退出、超时和普通异常有明确状态，Agent 也能正确计入 recovered_errors。

#### 64. 如果系统拆成多个服务怎么办？

使用 OTel Instrumentation 在 HTTP/gRPC 请求中注入和提取 W3C `traceparent`，每个服务继续创建子 Span；日志仍从当前 SpanContext 写入 MDC。当前 MCP 协议层没有完成这一步。

## 七、面试时不要说错的内容

- 不要说“已经部署 Jaeger/Grafana”，当前只是支持通过 OTLP 接入。
- 不要说“OpenTelemetry 相当于 AOP”，本项目是显式手动埋点。
- 不要说“已经有 Prometheus 指标和告警”，当前是单次运行统计。
- 不要说“Plan 总 Token 包含所有 Planner/Reviewer”，根汇总字段明确是 `worker_*`。
- 不要说“完整 Prompt 和工具正文都进入日志”，核心链路默认只记录摘要。
- 不要说“已经追踪 MCP Server 内部”，当前只到 `mcp.call` 客户端边界。
- 不要说“日志能完整还原所有 Span 父子树”，没有 Exporter 时主要用于关联和排障。
- 不要说存在 `plan_id`；当前稳定字段是 trace_id、span_id 和 task_id。
- 不要把设计文档里的 `plan.create`、`context.prepare` 等规划 Span 当作已全部实现。
- 不要给没有压测过的性能提升或开销百分比。

## 八、最后的收尾话术

我对这个模块的定位不是搭一套重型监控平台，而是给 Agent 执行引擎建立一套可演进的观测协议：业务代码只依赖 OpenTelemetry 标准和结构化日志，本地模式零外部服务也能排障，需要团队化部署时再接标准 OTLP 后端。当前最关键的工程收益，是并行 Plan、LLM 和工具错误不再是互相割裂的输出，而是能够放回同一条任务链中判断故障位置、恢复过程和最终影响。
