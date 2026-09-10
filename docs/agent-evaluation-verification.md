# 评测验证记录

构建与脚本链路验证日期：2026-09-11；题库双向校验日期：2026-09-10。验证环境：JDK 17、Maven、Windows。以下结果来自自动化测试与本地执行，不包含真实模型能力对照。

## 结果

| 验证 | 结果 | 范围 |
| --- | --- | --- |
| Maven 构建与 JUnit | 208 项测试，58 个测试类，0 失败、0 错误、0 跳过 | 运行时、Team、工具、记忆、协议和统一观测 |
| 参考解校验 | 20/20 PASS | 题目参考修复满足独立验收 |
| 初始版本校验 | 20/20 TASK_FAILED，回归检查通过 | 裁判能发现预置缺陷 |
| 脚本模型驱动真实 Agent | 20/20 PASS | Agent 循环、工具、子进程、采集和验收链路 |
| 故障恢复 | 两道恢复题均触发注入，最终 PASS | 执行前的确定性读写失败可继续处理 |
| 请求预算 | max-calls=1 时 BUDGET_EXCEEDED，仅一次模型调用 | 配额耗尽停止后续请求 |
| 超时与恢复 | 子进程截止、已知用量恢复及不完整标记测试通过 | 异常终止保留可用证据 |
| 统一采集 | 工具埋点、并行汇总、Trace 隔离、归档测试通过 | 评测与原有观测使用同一数据来源 |

参考解和初始版本双向检查验证题库与裁判。脚本模型按已知参考修复驱动 Agent，不代表模型自然解决任务的成功率。真实模型质量、时间和费用对照需使用 live 模式另行测量。

## 可追踪摘要

- [JUnit 汇总](eval-examples/junit-summary.json)
- [题库双向校验](eval-examples/fixture-validation.json)
- [脚本化链路摘要](eval-examples/scripted-summary.json)

完整执行产物默认生成在 `target/eval`，不纳入源码仓库。每次运行记录题目哈希、配置、JDK、系统和 JAR 指纹，逐次结果关联验收证据及 Trace 索引。

## 复现

```bash
mvn clean package
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain validate
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter scripted
java -cp target/Xcode-1.0-SNAPSHOT.jar com.xu.eval.EvalMain run --adapter scripted --task bug-01-pagination --max-calls 1
```

输出目录应为新目录，避免覆盖已有实验。脚本验证无需 API Key；live 入口从环境变量读取 `DEEPSEEK_API_KEY`。
