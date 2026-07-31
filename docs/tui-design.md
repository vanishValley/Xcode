# Xcode Agent TUI 设计

本文记录 JLine inline TUI 的设计边界、线程模型、输出策略和降级方案。目标不是给现有 CLI 套颜色，而是在不改变 Agent / Tool 协议的前提下，建立一个可并发、可取消、可审计的终端交互层。

## 1. 设计目标

- 保留终端 scrollback，回答、关键操作和错误可以复制、搜索和复盘。
- 主 Agent 只允许一个 active run，保护非线程安全的会话历史。
- Plan Worker 可以并行，但不得直接写终端。
- SSE 文本可以增量显示，Tool Call 必须完整重组后才能执行。
- 危险操作在同一输入控制器中审批，多个并行审批一次只展示一个。
- TUI 初始化失败、输入被重定向或终端能力不足时自动回退 plain。
- 任何输出进入 UI 前完成脱敏与控制字符清理。
- Ctrl+C、Ctrl+D、异常和正常退出都能恢复终端并释放等待中的 Future。

## 2. 为什么选择 inline TUI

全屏 Dashboard 适合监控系统，但 Coding Agent 的主要产物是可阅读、可复制的对话和执行记录。alternate screen 退出后通常不保留内容，也不利于复制编译错误。

因此本项目把信息分成两类：

| 类型 | 位置 | 示例 |
| --- | --- | --- |
| 持久信息 | scrollback | 用户输入、工具摘要、Plan 状态、审批、最终回答、失败短因 |
| 瞬时信息 | Status 底栏 | spinner、当前阶段、轮次、耗时、模型、HITL 状态 |

## 3. 分层

```text
Agent / Plan / ToolExecutor / HITL
                  │
                  │ immutable UiEvent
                  ▼
          QueueUiEventSink
                  │
                  ▼
          TuiApplication
             │         │
             ▼         ▼
        TuiRenderer  LineReader
```

核心层不知道 JLine，也不携带 ANSI 字符串。旧构造器注入 `UiEventSink.noop()`，保证原有 API 与测试兼容。

主要组件：

- `UiEvent`：Agent、Assistant、Tool、Plan、Session、Notice 和 Approval 的不可变事件。
- `QueueUiEventSink`：线程安全队列；相邻 SSE delta 会合并，关键事件不丢弃。
- `SafeDisplay`：在入队前生成有界、递归脱敏的 display model。
- `SafeHistory`：拒绝持久化疑似凭据，并在加载时清理旧的敏感条目。
- `CommandProcessor`：TUI 与 plain 共用的 Slash Command 路由。
- `TuiHitlHandler`：工作线程发布审批请求并等待 Future。
- `TuiApplication`：唯一输入控制器、single-flight 调度、事件循环和生命周期。
- `TuiRenderer`：唯一富终端写入者。
- `PlainCliApplication` / `PlainUiEventSink`：无 ANSI 降级路径。

## 4. 线程与所有权

### 4.1 UI 主线程

- 空闲时调用 `LineReader.readLine()`。
- 任务运行时停止读取普通输入，轮询事件并刷新状态栏。
- 收到 `ApprovalRequested` 后临时读取审批选择，审批输入不写入历史。
- 只有该线程调用 `printAbove()`、`Status.update()` 和 renderer。

### 4.2 Agent executor

- 固定单线程，确保 `Agent.history` 和 `MemoryManager` 不会并发修改。
- 命令完成后才允许下一次 run。
- 中断时不立刻开放下一任务，要等工作线程真正退出。
- 两次 `readLine` 之间保持 terminal raw/no-echo；运行期 type-ahead 在审批
  展示前或恢复主提示符前丢弃，避免缓冲输入被误当作批准或下一条命令。

### 4.3 Plan Worker

- 最多四个 daemon Worker。
- 只执行子 Agent 并发布事件，不修改终端。
- orchestrator 按真实完成顺序从完成队列收集结果，但只有 orchestrator 修改 Plan。

### 4.4 HITL

- Tool Worker 可以同步等待 `CompletableFuture<ApprovalResult>`。
- UI 线程永远不等待审批 Future；它读取用户选择后完成 Future。
- 取消和关闭会完成所有 pending Future，避免 Worker 永久阻塞。

## 5. 流式输出

DeepSeek SSE 的 `content` 增量与 `tool_calls` 增量使用不同路径：

- `content` 发布为 `AssistantDelta`，队列合并相邻小块，renderer 只刷新完整行。
- `tool_calls[index]` 按 index 累积 `id`、函数名和 JSON 参数片段。
- 只有收到完整 Message 后，ReAct 循环才解析参数和执行工具。
- `reasoning_content` 不进入 UI。
- `AssistantCompleted(streamed=true)` 只 flush 尾部，不重复打印全文。

## 6. 输出策略

### 6.1 默认显示

- 启动摘要：模型、项目、工具数、Skill 数和 HITL。
- 工具动作、目标、成功/失败、退出码和耗时。
- Plan 创建、Task 开始/结束和重规划。
- 审批工具、风险级别与安全参数。
- 最终回答和 turns / tools / token / duration 汇总。
- 用户可以采取行动的配置警告。

### 6.2 默认隐藏

- system prompt、内部推理与 `reasoning_content`。
- Memory 注入正文和 Reviewer transcript。
- 原始 Tool JSON、MCP JSON-RPC。
- 成功 `read_file` / `web_fetch` / `load_skill` 的完整结果。
- `write_file.content` 和其他 body / payload。
- stack trace、Span ID 和 debug 日志。
- 每一帧 spinner。

失败结果只显示有界短预览；完整工具结果仅回灌模型，项目日志只记录长度、类型和必要堆栈。

## 7. 安全显示边界

`SafeDisplay` 在事件入队前处理：

- key 名包含 `apiKey`、`token`、`password`、`secret`、`Authorization`、`Cookie`；
- Bearer Token、DeepSeek/OpenAI 风格 key、GitHub PAT 和 JWT；
- URL 中的 token、key、password、signature 等 query；
- `content`、`body`、`payload`、`source` 等大正文字段；
- ANSI / OSC / BEL / CR、C0 控制符和 Unicode bidi 控制符；
- Map / List 递归深度、字段数、字符数与 Unicode-safe 截断。

renderer 只消费 safe arguments，不保留原始参数引用。

## 8. 取消语义

```text
Ctrl+C
  → 取消当前 generation，并传播到 Plan 子作用域
  → 解除 pending approvals
  → interrupt Agent worker
  → cancel OkHttp Call / destroy command process tree
  → 每次新 Tool 执行前再次检查 interrupt
  → 未执行工具时回滚；已执行工具时保留结果并补“状态未知”
  → Plan 中断/超时保留 checkpoint，禁止自动重规划
  → drain 关键终态事件
  → 恢复 prompt
```

`Future.cancel(true)` 本身不代表底层工作已停止，因此 UI 以 worker 的真实退出作为 single-flight 结束条件。

取消采用 generation-scoped token。开始下一次输入不会把仍在退出中的旧 Worker
重新激活；如果后台线程在宽限期内没有终止，当前进程会停止接收新任务，避免旧任务
与新任务同时触达可变工具。

## 9. 终端能力与降级

启动参数：

- `--ui=auto`：默认；JLine 检测为 dumb 时回退 plain。
- `--ui=tui` / `--tui`：尝试 TUI，初始化失败仍安全回退。
- `--ui=plain` / `--plain`：禁用 ANSI 和状态栏。

`NO_COLOR` 存在时关闭 Terminal color。Shade Plugin 合并 `META-INF/services`，保证 JNI Terminal Provider 在 fat JAR 中仍能被 ServiceLoader 找到。

## 10. 生命周期

所有退出路径最终执行同一套清理：

1. 停止接受新输入；
2. 取消 active run；
3. 拒绝 pending approvals；
4. `shutdownNow` Agent executor，并短暂等待退出；
5. 保存经过凭据过滤的 JLine history（或响应 `/history clear` 主动清除）；
6. flush streaming 尾部；
7. hide / close Status；
8. flush / close Terminal；
9. 关闭 Chrome MCP 与 Tracing。

正常退出与 JVM shutdown hook 共用这套幂等清理；无法拦截的强制终止
（例如操作系统直接杀死进程）仍由下次启动的 checkpoint 恢复策略兜底。

## 11. 测试重点

- 并发发布事件不丢失，相邻 delta 只在无边界事件时合并。
- 同一个 Tool 必须先 Started 后 Completed，且只有一个终态。
- HITL approve-all 只在当前会话生效，clear 后失效。
- 取消会释放所有等待中的 Approval Future。
- SSE 能重组跨 chunk 的函数名和 JSON 参数。
- Secret 不出现在事件 `toString()`、plain 输出和错误预览。
- 含凭据的输入不进入 JLine history，旧历史会在加载时净化。
- 工具产生副作用后再取消时，完整结果和未知状态仍留在 transcript。
- Plan Worker 超时后不触发重规划，也不会自动重放该步骤。
- ANSI、bidi 与 emoji 截断不会破坏终端。
- shaded JAR 的 plain 管道烟测可以在不联网时正常 `exit`。
