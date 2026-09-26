# 多任务执行与集成管理设计

## 目标与边界

用户同时运行多个开发任务时，源码、暂存区和运行资源不相互污染；任务交付固定版本，集成验证针对组合后的代码；失败保留现场。模型负责拆解和实现，Java 运行时负责执行边界与交付状态。本文是代码重构的设计依据，验收清单及实现边界在文末维护。

## 执行模型

- `READ_ONLY`：保持现有调查成员及主 Agent 独占修改阶段。
- `ISOLATED_WRITE`：一成员一 worktree、一分支，绑定独立文件工具、Shell cwd、环境变量和临时目录。分支从固定提交创建，不复制用户未提交内容。
- `dependsOn` 引用本团队已交付的写成员。前置交付未就绪时拒绝启动，主 Agent 等待后重试；准备工作区时合入固定依赖提交，发生冲突则保留准备现场并返回失败，不启动模型。
- 工作区是并发隔离，不是操作系统沙箱。任意 Shell、外部数据库和未知 MCP 不会因为 cwd 改变而自动安全；写成员不继承未知 MCP，Shell 保留现有审批。应用端口、数据库通过项目启动命令显式采用任务环境。

## 模块与不变量

| 模块 | 职责 |
| --- | --- |
| TeamRuntime | 单成员串行执行、收件箱、安全边界注入、契约确认、取消与退出确认 |
| TeamWorkspaceService | worktree/分支、不可变交付、候选集成、验证记录、审查和保留 |
| WorkspaceGit | 有界、参数化 Git 子进程；不拼接 Shell；拒绝越界 ref 输入 |
| ToolRegistry.forWorkspace | 重新绑定内置工具；HitlToolRegistry 保留审批和成员取消令牌 |
| TeamEventStore / 工作流 manifest | 执行事件和工作区/成果状态；原子快照、失败保留 |

1. 所有写成员使用自己的工具注册表；不复用主工作区的 write_file/execute_command/glob_files。
2. 源码所有权在成员运行期间不移交；检查某工作区必须通过运行时持有该成员的静止租约，禁止同时续接。
3. 交付要求工作区清洁、分支正确、HEAD 包含任务基线；版本变化使旧交付失效。
4. 验证绑定候选 SHA、固定检查命令和退出码，验证后 HEAD/工作目录变化使结果失效。
5. 审查检查目标基线及所有输入交付仍有效；不自动更新用户分支或 push。
6. 契约消息区分投递、呈现和明确确认；消息不代表代码已经同步。

## 生命周期与工具协议

主 Agent 使用 `spawn_agent(profile=ISOLATED_WRITE, dependsOn=[...])` 创建成员。
写成员使用绑定的工具开发，通过 `submit_changes` 提交并冻结当前成果；后续写入或续接要重新交付。
主 Agent 使用 `list_workspaces` 查看证据，使用 `build_integration` 在独立分支组合固定交付。
文本冲突保留候选，使用受审批的 `integration_command` 修复，或重新构建候选；`verify_integration` 执行项目规定的命令，并核对验证前后的代码一致性。
`review_integration` 记录 Agent 审查结论与证据，只产生 REVIEWED 候选，不伪装成人工批准或 MERGED。
未纳入候选的成果必须通过 `defer_changes` 说明原因；报告区分可交付与部分完成。

验证策略来自 `.xcode/team-workflow.json` 的 `verificationCommands` 字符串数组；没有配置时不允许声称验证通过。Maven 项目配置 `["mvn -B verify"]`，`verificationTimeoutSeconds` 指定单命令超时（默认 600，范围 1..3600 秒），团队总截止时间仍生效。配置由协调器启动时捕获，候选中的修改不能替换本次检查策略。人工审查、远端 CI、目标分支保护和合并队列由现有仓库流程执行。

## 消息与公共契约

成员用 `report_to_parent(QUESTION, needsReply=true)` 提议契约变更。主 Agent 用 `send_contract_change` 向受影响成员发送带唯一 ID 的通知。成员在下一次模型调用前获得通知，使用 `acknowledge_contract` 明确接受或拒绝；未处理的契约阻止成果交付和成功结束。
确认表示理解并接受约定，不表示实现完成。代码通过固定提交交接，最终通过组合测试验证。空闲成员仍需 CONTINUE 唤醒，不把投递成功等同执行成功。

## 持久化、恢复与锁

团队目录保存工作流 manifest、工作区路径、任务/执行身份、基线、交付 SHA、候选状态和验证记录。工作流持有跨进程文件锁，同一目录不能被两套运行时接管；仓库管理操作使用 Git common-dir 下的短期跨进程锁，worktree 名称全局唯一。
不自动重放崩溃中的 Shell 或合并，不自动删除 worktree；异常后保留现场并报告需要核对。当前进程重新启动读取记录用于人工核对，不宣称透明续跑或事务覆盖 Git 与文件系统。锁释放也不等于所有后台进程退出。

## 外部设计参考（已核实公开资料，2026-09-26）

- [Claude Code worktree](https://code.claude.com/docs/en/common-workflows#run-parallel-sessions-with-worktrees)：并行 session 使用独立 checkout。借鉴环境隔离，不假设所有子成员默认隔离。
- [Codex worktree](https://learn.chatgpt.com/docs/environments/git-worktrees)：任务工作区及分支生命周期；本项目选择显式命名分支，方便交付和保留。
- [Pi](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/README.md)：扩展与 SDK；借鉴 Agent 核心和外层编排的分离，未证实默认自动集成机制。
- [DeepSeek Harness subagent](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/subagent/README.md)：委派/消息/控制与执行后端分层；不推断其提供相同的 Git 验收流程。
- [GitHub merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)：远端组合检查和目标更新应对接平台能力。

## 验收

真实临时 Git 仓库验证：并行工作区互不覆盖、原目录保持不变、依赖交付门禁、冲突保留、候选组合验证、目标或交付漂移拒绝、审批继承、消息确认门禁、失败现场持久化。已有只读团队测试继续通过。

本次实施验证：`mvn verify` 通过，221 项 JUnit 测试，0 失败、0 错误、0 跳过；包括脚本模型驱动的真实 Agent → 写成员 → Git 交付 → 候选验证 → 审查完整链路。不需要在线模型 API Key；这证明实现与协议行为，不代表真实模型的任务完成率。

## 当前交付边界

- 已接入 `/team`：隔离写成员、依赖交付检查、工具目录重绑定和审批继承、契约确认、固定提交、冲突现场、固定策略验证、版本漂移检查及组合审查门禁。
- 任务和候选临时目录、资源命名空间已分配；端口和数据库没有自动创建，项目需显式配置。
- 候选终态是 REVIEWED，不是 MERGED。最终报告提供记录路径，通过 `list_workspaces` 可获取候选分支和工作目录；后续人工/托管平台审查及目标分支合并按仓库规则完成。
- 工作区保留用于故障排查和成果审查；已有工作流目录需核对现场后处理，不自动恢复执行。
- 多个独立 `/team` 写任务使用唯一工作区，短期 Git 管理锁协调创建；普通 ReAct/Plan 会话和外部编辑器不自动加入此工作区所有权协议。
- Shell 仍在宿主权限下执行；未提供 OS 沙箱或对完全脱离管理的后台进程的强制所有权保证。
