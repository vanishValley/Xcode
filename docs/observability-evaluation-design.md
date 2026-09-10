# 可观测性与评测统一设计

可观测模块采集执行证据，评测模块在固定任务上运行 Agent 并独立验收。两者共用生产埋点、Trace 上下文和诊断产物存储。

## 数据流

```text
Agent / Plan / Team / Reviewer
              │
      LlmClient / ToolExecutor 等现有埋点
              │
      Tracing / TraceScope / 跨线程 Context
              │
       ┌──────┼──────────────────┐
   OTel Span  AgentMetrics       ExecutionArtifactStore
       │      聚合次数与耗时      请求、结果与异常正文
       │                         ▲
   ExecutionRecorder ────────────┘
       │                   同目录 events.jsonl
   ExecutionCapture
       │
   ExecutionSnapshot → 预算检查 / 单次尝试结果
       ▲
   超时后从相同 events.jsonl 恢复

题库 + 独立执行 + 外部编译/行为/回归验收 → EvalResult → 报告
```

## 职责划分

| 组件 | 职责 |
| --- | --- |
| `Tracing` / `TraceScope` | SDK 装配、父子 Span、属性、状态与作用域关闭 |
| `ContextAwareTasks` / `MdcScope` | 跨线程传播与恢复 OTel Context 和日志上下文 |
| `AgentMetrics` | 聚合模型、任务及工具的次数、耗时和用量指标 |
| `ExecutionArtifactStore` | 请求、结果与事件存储，脱敏、容量限制和归档保留 |
| `ExecutionRecorder` | 监听已有 Span，仅记录显式 Capture 所属 Trace |
| `ExecutionCapture` | 一次任务的订阅、快照和归档生命周期 |
| `ExecutionSnapshot` | 实时汇总与离线恢复共用的计数逻辑 |

评测的 `BudgetedClient` 负责请求准入与预算判断。并行请求先原子预留名额，最终报告从实际模型 Span 汇总调用数量。故障装饰器只注入工具失败，执行器继续使用原有日志和诊断路径。

## 采集模式

普通入口使用 `Tracing.create`，遵循既有采样、导出和诊断保留配置。默认不要求外部观测服务；标准 OTLP 配置可以将 Trace 发送到后端。

评测入口使用 `Tracing.forEvaluation`，启用完整采样并保留每次尝试的诊断目录。通过 `capture` 开启任务作用域，同一个 Tracing 注入 Agent、模型和工具链路。只有显式 Capture 才增加任务事件日志。

聚合 Metrics 与任务 Snapshot 服务于不同查询，不把父级 Agent 总计或聚合指标再次加进单次模型用量。Trace ID 用于追踪和文件索引，不作为高基数监控指标标签。

## 计数口径

| 字段 | 来源与含义 |
| --- | --- |
| `llmCalls` | `llm.chat` 的 `span.start`，失败请求也计入 |
| `inputTokens` / `outputTokens` | 模型 Span 结束时返回的 usage |
| `usageComplete` | 已开始的调用均结束且返回完整 usage；脚本模型为 false |
| `requestedTools` / 报告 `toolCalls` | 模型提出的工具数量，包括调度控制工具 |
| `executedTools` | `tool.execute` 的进入次数，可能在参数检查时失败 |
| `faultTriggered` | 已发生的故障注入事件 |

并行 Worker 通过上下文传播归入同一 Trace，快照同步累加；不同 Trace 分开统计。token 按已返回的 usage 检查，在途请求可能越过阈值，因此 token 限制是软预算。

## 产物与收尾

每个 trial 的 `agent/observation.json` 保存 traceId 和诊断目录。执行中指向 `observability/staging/<traceId>`；收尾后更新为 `observability/runs/<日期>/<traceId>`。

同一目录包含 `manifest.json`、`events.jsonl`、调用元数据、请求/结果正文和 `completion.json`。外层 Capture 负责归档，内部 Plan 完成时不会提前移动目录。关闭时先结束最外层 Span，再解除监听并归档。

子进程超时后，父进程用 `ExecutionSnapshot.recover` 读取已经落盘的事件，恢复已知用量，并将该次尝试的 usageComplete 标为 false。文件受容量与保留期限限制，写入失败、截断或进程终止可能造成证据不完整。

## 验证

测试覆盖原有工具埋点与正文、并行汇总、不重复累加父级总计、不同 Trace 隔离、嵌套任务归档、失败用量和截断事件恢复。

评测流程见 [评测模块设计](agent-evaluation-design.md)，结果见 [验证记录](agent-evaluation-verification.md)。
