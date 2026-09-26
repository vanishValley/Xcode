# Xcode Agent

使用 Java 17 实现的模块化终端编程助手，支持代码阅读与修改、命令执行、任务规划、多 Agent 协作、上下文管理和自动化评测。

![Java](https://img.shields.io/badge/Java-17-ED8B00)
![Build](https://img.shields.io/badge/build-Maven-C71A36)
![Tests](https://img.shields.io/badge/JUnit-208_tests-25A162)

## 功能概览

| 模块 | 已实现能力 |
| --- | --- |
| ReAct 执行 | 多轮模型与工具交互、SSE 流式输出、Tool Call 增量重组、异常结果回灌 |
| Plan-and-Execute | DAG 规划、依赖与环检测、并行 Worker、Reviewer、checkpoint 与失败重规划 |
| Team 协作 | 独立上下文、契约消息确认、只读调查或 worktree 并行开发、固定提交交付与组合验收 |
| 工具系统 | 文件读写、目录搜索、Shell、网页搜索与抓取、统一参数解析和错误结果 |
| 记忆管理 | 会话持久化、上下文压缩、目标锚定、项目/全局长期记忆、经验提炼 |
| Skills | 内置/全局/项目三级发现、按需加载、任务内去重、任务结束释放正文 |
| MCP | 懒加载、工具动态注册、stdio 与 Streamable HTTP 传输 |
| 执行控制 | HITL 审批、任务级取消、进程树终止、写入路径检查、网络请求与显示边界 |
| 可观测性 | OpenTelemetry、结构化日志、MDC、跨线程上下文、诊断产物与任务用量快照 |
| Agent 评测 | 20 道 Java 任务、独立编译验收、预算控制、故障注入、重复运行与配置对照 |
| 终端交互 | 共享命令路由、JLine TUI、plain 模式、非交互环境自动降级 |

## 系统架构

```text
TUI / Plain CLI
      │
CommandProcessor
      ├── 普通任务 → ReAct Agent
      ├── /plan    → Planner → DAG → 并行 Worker → Reviewer
      └── /team    → 主 Agent → TeamRuntime → 独立成员
                              │
                  共享 Agent 执行与工具协议
                              │
       ┌──────────────────────┼────────────────────┐
   MemoryManager          LlmClient           ToolExecutor
   历史/目标/压缩          DeepSeek/SSE        注册/审批/结果
       │                                           │
   SessionStore                          本地工具 / Web / MCP
   LongTermMemory

横向能力：CancellationToken · Skills · Tracing / MDC / Metrics
评测入口：EvalRunner → 现有 Agent + ExecutionCapture → 外部验收 → 报告
```

| 包 | 职责 |
| --- | --- |
| `agent` / `plan` | ReAct 循环、执行钩子、规划、调度、审查和计划持久化 |
| `team` | 成员生命周期、消息、任务续接、预算和工作区访问阶段 |
| `tool` / `hitl` | 工具协议、执行器、内置工具和人工审批 |
| `memory` / `skill` | 会话与知识管理、上下文压缩、Skill 注册和任务作用域 |
| `llm` / `http` / `mcp` | 模型请求、可取消 HTTP、MCP 客户端与传输 |
| `observability` | Trace、指标、诊断文件、任务采集与用量恢复 |
| `eval` | 题目加载、隔离运行、独立验收和报告 |
| `cli` / `ui` / `util` | 启动装配、交互适配、显示处理和取消控制 |

## 核心设计

### 三种执行模式

**ReAct** 根据每轮工具结果决定下一步，适合连续的代码阅读和修改。流式文本即时展示，工具名称和参数在响应完整后统一解析执行。每个 Tool Call 都对应一条 Tool Result；执行中断时保留已发生的操作结果，补齐未完成调用的状态。

**Plan-and-Execute** 先生成有依赖关系的任务图，再选择就绪任务并行执行。Worker 提交前保存 `IN_PROGRESS` checkpoint，步骤结束后更新状态。普通失败可以重规划；超时、取消或副作用状态未知时停止自动重放，保留现场供检查。

**Team** 由主 Agent 动态分工。`READ_ONLY` 成员并行调查，主 Agent 在共享读成员停止后集中修改；`ISOLATED_WRITE` 成员使用独立 worktree、分支和工具环境开发。依赖必须已有固定交付，运行时准备包含依赖提交的基线。同一成员的续接串行执行，公共契约消息需要明确确认。写任务交付后在独立候选分支组合，通过项目检查策略和 Agent 审查后交付；不会自动修改用户分支或推送远端。运行时限制并发、创建数量、预算和总时间，并阻止提前结束或使用过期验证。

详见 [Team 协作使用](docs/team-mode-implementation.md) 和 [多任务执行与集成设计](docs/multi-task-workspace-design.md)。

### 工具与扩展

`Tool` 声明名称、描述、JSON Schema 和执行方法；`ToolRegistry` 统一管理本地与 MCP 工具。`HitlToolRegistry` 包装需要审批的操作，`ToolExecutor` 负责参数解析、取消检查、异常归一化、观测和 UI 事件。

Skills 采用 `builtin < ~/.xcode/skills < <project>/.xcode/skills` 的覆盖顺序。模型先获得轻量索引，再通过 `load_skill` 加载完整规则。同一任务内避免重复注入；任务结束后将正文替换为短标记，保留 Tool Call 配对关系。仓库提供 [Prompt 修改评测 Skill](.xcode/skills/prompt-change-evaluation/SKILL.md) 示例。

MCP 首次使用时才连接和发现工具。stdio 与 Streamable HTTP 共享握手、分页发现及工具适配逻辑，外部工具以 `mcp__<server>__` 命名空间进入统一注册表。

### 记忆与上下文

会话历史保存真实消息，任务目标、计划和检索到的长期记忆在请求时注入。长期记忆按项目范围检索，并在单次任务开始时冻结；历史压缩保留完整消息轮次，避免拆开工具请求和结果。

人工保存与自动经验提炼共用长期记忆写入入口，进行去重、合并、置信度和容量管理。详见 [记忆模块设计](docs/memory_design.md)。

### 可观测性与评测

生产执行与评测复用同一套 OpenTelemetry 埋点。`ContextAwareTasks` 传播 Trace 和 MDC；模型及工具调用保存耗时、状态和用量，正文进入单独的诊断存储，沿用脱敏、容量与保留策略。

评测通过 `ExecutionCapture` 订阅单次任务的 Trace，使用 `ExecutionSnapshot` 汇总调用与 token。子进程超时后从已落盘事件恢复已知用量，缺失 usage 明确标为不完整。外部裁判重新编译最终源码，检查任务行为、既有回归和修改范围，不以 Agent 自述完成作为通过依据。

详见 [统一观测与评测设计](docs/observability-evaluation-design.md) 和 [评测模块设计](docs/agent-evaluation-design.md)。

### 取消、审批与恢复

每次任务使用独立的取消 generation，取消向模型请求、Worker、待审批操作、工具和 Shell 子进程传播。已经执行的写入与状态未知操作不会因重试而被静默重放。写文件工具检查项目路径边界，网页抓取校验目标与重定向地址，显示层处理凭据和终端控制字符。

工具在宿主系统权限下运行；当前没有容器或独立系统账户沙箱。只读成员不能使用 Shell；独立写成员的 Shell 绑定任务目录并保留审批，未知 MCP 不继承。worktree 不隔离操作系统权限、端口或外部数据库，项目命令需使用任务资源命名空间配置这些资源。

## 快速开始

需要 JDK 17+、Maven 3.8+ 和 DeepSeek API Key。Chrome MCP 可选，需要 Node.js、npm/npx 与 Chrome。

```bash
git clone https://github.com/vanishValley/Xcode.git
cd Xcode
```

将 `.env.example` 复制为 `.env`，填写配置：

```dotenv
DEEPSEEK_API_KEY=your_deepseek_api_key_here
DEEPSEEK_MODEL=deepseek-chat
HITL_ENABLED=true
CHROME_MCP_ENABLED=false
```

其他 MCP、联网搜索与日志选项见 [.env.example](.env.example)。`.env` 不纳入版本控制。

```bash
mvn clean package
java -jar target/Xcode-1.0-SNAPSHOT.jar
```

程序以当前工作目录为项目根。在其他项目使用时，进入目标目录后通过绝对路径启动 JAR。

```bash
java -jar /path/to/Xcode/target/Xcode-1.0-SNAPSHOT.jar --ui=plain
```

`--ui=auto` 为默认模式，另支持 `plain` 和 `tui`。

### 常用命令

| 命令 | 作用 |
| --- | --- |
| `/help`、`/status`、`/tools` | 帮助、运行配置与工具列表 |
| `/plan <任务>` | 规划、并行执行和审查 |
| `/team <任务>` | 并行调查或隔离开发、契约协调与组合验收 |
| `/hitl on/off` | 切换危险工具审批 |
| `/skills`、`/skill reload` | 查看与重新加载 Skills |
| `/skill on/off <name>` | 启停指定 Skill |
| `/save <事实>`、`/save -g <事实>` | 保存项目或全局长期记忆 |
| `/memory`、`/memory clear` | 查看或清空长期记忆 |
| `/history clear`、`/clear` | 清理终端历史或当前会话 |
| `exit`、`quit` | 退出 |

## 测试与评测

JUnit 测试覆盖执行协议、规划恢复、Team 并发与权限、真实 Git worktree 与集成门禁、记忆和 Skills、MCP、取消、UI 以及观测采集。以本次 Surefire 报告为实际测试数量依据。

```bash
mvn test
```

内部 Java 任务集包含 **20 道任务、16 个问题家族**，覆盖缺陷修复、功能开发、重构、工具故障恢复和修改约束。

```bash
# 校验参考解通过、初始缺陷被发现
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain validate

# 无 API 调用：脚本模型驱动真实 Agent、工具和外部验收
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter scripted

# 真实模型：需在环境变量中设置 DEEPSEEK_API_KEY
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter live --profile react --trials 3
```

已验证参考解 20/20 通过、初始缺陷 20/20 被识别，以及脚本化 Agent 链路 20/20 通过。这些结果验证题库和运行链路；当前未发布真实模型能力或配置收益成绩。

使用方式见 [Benchmark 说明](benchmarks/java-v1/README.md)，结果口径见 [验证记录](docs/agent-evaluation-verification.md)。

## 文档

- [Team 协作设计与使用](docs/team-mode-implementation.md)
- [多任务工作区、交付与集成设计](docs/multi-task-workspace-design.md)
- [记忆与上下文管理](docs/memory_design.md)
- [TUI 交互层设计](docs/tui-design.md)
- [可观测性与评测统一设计](docs/observability-evaluation-design.md)
- [Agent 评测模块设计](docs/agent-evaluation-design.md)
- [Java Benchmark](benchmarks/java-v1/README.md)
- [评测验证记录](docs/agent-evaluation-verification.md)

## 后续方向

- 扩展真实仓库任务和长期记忆评测，积累真实模型对照结果。
- 增加 Patch / Diff 编辑、变更预览与细粒度审批。
- 支持可配置工具权限、多个模型 Provider 和 MCP 声明式配置。
- 扩展操作系统沙箱、外部服务资源分配和远端合并队列接入。
