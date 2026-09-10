# Xcode Java Benchmark v1

这是随项目交付的内部 Java 维护任务集：20 道可执行题、16 个问题家族。题目是人工构造的工程场景，不是从开源 issue 抽取的公开 benchmark，也不是 SWE-bench 成绩。

## 题目范围

| 分类 | 数量 | 内容 |
|---|---:|---|
| BUG | 8 | 分页、金额、幂等、缓存过期、重试、配置、排序、并发库存 |
| FEATURE | 4 | 订单过滤、错误映射、日志脱敏、批处理 |
| REFACTOR | 4 | CSV 解析、发票入口一致性、资源释放、最长路径匹配 |
| RECOVERY | 2 | 明确尚未执行的读/写工具临时失败 |
| CONSTRAINT | 2 | 仓库内不可信指令、无关模块修改边界 |

15 道 dev、5 道 holdout；同源变体继承 family 和 split，避免同一个缺陷换个名字跨集合。holdout 只表示开发流程约定：答案仍在本仓库中，不是第三方保密测试集。

## 一道题的契约

任务 JSON 包含 id、family、category、difficulty、split、prompt、files、reference、editable、checks、fault。

- `files` 是干净工作区快照：生产源码、可见测试和说明。
- `reference` 是已知正确修复，只用于 oracle 校验和 scripted smoke。
- `editable` 明确可修改文件；所有输入文件中的其他文件受保护。
- `BehaviorCheck` 验收新增/修复行为；`RegressionCheck` 保护已有正常行为。
- 可见检查只覆盖回归样例，隐藏检查包含边界与组合输入。
- `fault` 为 none、read-once 或 write-once。后两者第一次对应工具调用在执行前失败，无副作用未知状态。

验证器在单独目录复制候选源码，恢复保护文件并加入隐藏检查，重新 javac 编译，然后用 `java -ea` 运行检查。Agent 工作区的 class 文件与自述测试结果不会参与评分。

`validate` 必须得到：参考解全部 PASS；初始版本全部 TASK_FAILED，且其 RegressionCheck 为 true。校验失败应修题或修裁判，不应修改分数掩盖它。

## 运行

在项目根目录执行，需要 JDK 17 和 Maven。以下命令适用于已配置 PATH 的 PowerShell：

```powershell
mvn package
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain list
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain validate
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter scripted
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter scripted --task recovery-02-write
```

真实模型入口需要环境变量 `DEEPSEEK_API_KEY`。不会自动读取项目 `.env`，不会把密钥写进 manifest。使用显式的新输出目录便于后续比较：

```powershell
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter live --profile no-goal --split dev --trials 3 --out target/eval/baseline
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter live --profile react --split dev --trials 3 --out target/eval/anchored
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain compare --baseline target/eval/baseline --candidate target/eval/anchored
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain report --run target/eval/anchored
```

同样的 `--profile review|plan|team` 可运行现有 Reviewer、PlanExecuteAgent 和 TeamCoordinator。scripted 只支持 react/no-goal；它按固定脚本读写源码，不能测量规划或协作智能。

## 明确区分数据来源

| adapter / 命令 | 是否调用真实模型 | 能证明什么 |
|---|---|---|
| unchanged | 否 | 裁判能识别初始缺陷 |
| oracle | 否 | 参考解确实满足验收 |
| scripted | 否 | 真实 Agent 循环、工具、故障、产物、评分能够串通 |
| live | 是 | 本配置在这组任务上的实测表现 |

## 产物

每次运行保存 run.json（参数、题目哈希、JDK/系统、jar 指纹）、results.json、summary.json、report.md。
每道题的每次尝试保存 task.json、workspace、verification、result.json；scripted/live 额外保存 agent/observation.json、agent/worker.json、agent/process.log。observation.json 指向当次 Trace 的诊断目录。

统一诊断目录位于 agent/observability 下：执行中写 staging/<traceId>，正常收尾归档到 runs/<日期>/<traceId>。events.jsonl 保存原有 Span 的开始/结束、用量属性、故障和 UI 事件；模型与工具正文沿用原有请求/结果文件。成功和失败都保留，进程超时时从已落盘事件恢复已知用量。文件保留原有脱敏、容量和保留期限设置，超过容量可能缺失后续事件。新增题时更新任务 JSON 即可，无需修改 runner。架构见 [统一设计](../../docs/observability-evaluation-design.md)。

## 口径与实现边界

- 外层墙钟默认 180 秒、模型请求最多 40 次、工具请求最多 80 次、已知 token 预算 120000。ReAct 每次调用最多 20 轮；Plan/Team 保留各自内部调度上限，共用外层时间和模型预算。
- token 阈值在发起下一次请求前检查；单次响应和已在途的并发请求可能越过阈值。运行结束检测超额并记为 BUDGET_EXCEEDED。这是软预算，不宣称严格的预付费 token 配额。
- toolCalls 统计模型提出的工具调用数量，包括 Reviewer、Team 的控制工具；执行器进入/退出看 name=tool.execute 的 span.start/span.end。进入执行器也可能参数校验失败，不代表一定产生副作用。
- 成功必须是外部检查通过且执行正常结束。故障题还要求故障确实触发，否则 SCENARIO_NOT_EXERCISED。
- 有效尝试包括功能失败、编译失败、约束失败、超时和预算耗尽；基础设施无法准备或运行裁判才计 INFRA_ERROR。
- 重复组有基础设施错误或缺少尝试时，不纳入“至少一次/每次成功”；报告显示有效组数。
- 模型响应未返回 usage 时标为不完整；超时从已经落盘事件恢复已知用量，不拿 0 伪装真实成本。
- 默认工具与 OS shell 没有容器隔离。新 JVM/cwd 能防止普通状态串题，但不是安全沙箱，不能用来宣称对抗性防作弊通过。
- 不自动下载仓库、不引入数据库/队列/裁判模型服务；本地顺序运行减少资源竞争。

## 运行时可靠性补充集

`runtime-contracts.json` 将既有确定性 JUnit 测试归入协议、恢复、取消、上下文和团队调度。它与真实模型任务分别报告，不把单元测试数并入任务成功率。

```powershell
mvn '-Dtest=AgentRecoveryTest,PlanExecuteAgentTimeoutTest,ToolExecutorTest,CancellationTokenTest,ConversationCompactorTest,TeamRuntimeTest,TeamCoordinatorTest' test
```

本任务集未评测真实 MCP 服务宕机、跨进程恰好一次副作用、长期记忆收益或真实大仓库能力。对应机制可以接入相同 runner，当前文档不把扩展方向写成已实现能力。
