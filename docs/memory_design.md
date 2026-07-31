# Xcode Agent CLI：记忆模块

## 1. 一句话架构

记忆模块只处理三件事：

1. `SessionStore + ConversationCompactor`：保存和压缩对话历史。
2. `MemoryManager`：冻结本次任务所需的信息，并按固定顺序组装 prompt。
3. `LongTermMemory`：保存、检索和治理跨会话知识。

`PlanStore` 保存的是任务执行进度，已经放到 `com.xu.plan`，不属于记忆模块。

## 2. 当前类及职责

| 类 | 职责 |
|---|---|
| `MemoryManager` | Agent 使用的统一入口；管理目标、Plan 上下文、冻结记忆和 prompt 组装 |
| `LongTermMemory` | 长期记忆的统一读写入口；内部完成 JSON 存储、关键词检索、去重和审核判断 |
| `LessonExtractor` | 从“工具失败→修正→成功”记录中提炼经验 |
| `SessionStore` | 将干净对话历史保存到 `session.jsonl` |
| `ConversationCompactor` | 将旧轮次压缩成滚动摘要，保留最近轮次原文 |
| `TokenBudget` | 估算上下文占用并判断是否需要压缩 |
| `MemoryRecord` | 一条不可变长期记忆 |
| `MemoryScope` | `PROJECT` 或 `GLOBAL` |
| `MemorySource` | `HUMAN` 或 `AGENT` |

没有提前保留只有一个实现的 `Retriever`、`GovernanceGate` 等接口。当前长期记忆最多
50 条，文件存储和关键词检索都是 `LongTermMemory` 的内部实现。以后真正接入向量库时再抽接口。

## 3. 每次 ReAct 任务如何请求模型

用户开始一个任务时，`Agent` 先做两次冻结：

```text
memory.beginTask(userInput)       冻结本次任务相关的长期记忆
toolRegistry.toOpenAiTools()      冻结本次任务的工具定义
```

后续无论发生多少轮 tool call，都复用这两份数据。工具结果只追加到历史中。

每次调用主模型前，`MemoryManager.assemblePrompt()` 按以下顺序组装 `messages`：

```text
1. 基础 system prompt + Skill 索引
2. 目标锚点                         可选
3. 本次任务冻结的长期记忆           可选
4. Plan 上下文                      可选
5. 原有 user / assistant / tool 历史
```

最终请求仍是一个 `ChatRequest`：

```text
ChatRequest
├── model
├── messages
├── stream
└── tools       本次 ReAct 任务内固定
```

这样排列有两个目的：

- 稳定的 system 和 Skill 索引始终位于消息前缀，更适合 prompt cache。
- 目标、记忆和 Plan 状态位于长历史之前，降低 lost-in-the-middle 的影响。

目标、长期记忆和 Plan 上下文只临时注入 prompt，不写进 `session.jsonl`。

## 4. 对话历史与压缩

`history` 保存基础 system 消息以及真实会话记录。每轮调用模型前：

```text
history
  → TokenBudget 判断是否达到阈值
  → ConversationCompactor 压缩旧轮次
  → MemoryManager 加入临时上下文
  → 发送给模型
```

压缩时按 user 消息划分完整轮次，避免拆开 assistant tool call 和对应的 tool result。
最近轮次保留原文，旧轮次压缩成滚动摘要。压缩失败时降级为保留最近若干轮。

## 5. 长期记忆读路径

`LongTermMemory.retrieve(query, projectKey)`：

1. 使用 jieba 对本次用户任务分词。
2. 只保留当前项目的 `PROJECT` 记忆和全部 `GLOBAL` 记忆。
3. 使用“关键词命中率 × 时间衰减”打分。
4. 取最相关的 3 条。
5. `MemoryManager.beginTask()` 将结果冻结，整个 ReAct 任务内不再重新检索。

长期记忆注入还受 800 字符上限约束，避免挤占主要对话上下文。

## 6. 长期记忆写路径

人工保存和 Agent 自动沉淀都调用：

```text
LongTermMemory.save(MemoryRecord)
```

入口统一，但根据 `MemorySource` 使用不同规则。

### HUMAN

```text
精确重复 → REJECT
近似重复 → MERGE
否则       → COMMIT
```

用户明确执行 `/save`，因此信任度高，只需要防止重复。

### AGENT

```text
精确重复     → REJECT
低置信且近似 → MERGE
低置信       → DEFER，进入 reviewQueue
高置信       → COMMIT
```

Agent 经验最多保留 25 条；与旧 Agent 经验高度相似时，用新经验替换旧经验。

`DEFER + reviewQueue + confidence` 是同一套低置信审核机制。目前队列仍是进程内状态，
也没有完整的人工批准命令；它被保留，但后续应当作为一个独立功能决定“补完整”还是“删除”。

## 7. 自动沉淀

`LessonExtractor` 只在 transcript 出现“工具先失败、之后成功”时调用 LLM 提炼经验：

```text
执行历史
  → 检测失败→成功信号
  → LLM 提炼一句经验
  → 创建高置信 AGENT 候选
  → LongTermMemory.save(...)
```

这里使用高置信候选，是因为经验并非凭空生成，而是有工具失败和成功记录作为信号。
它仍然经过统一写入口，继续执行去重、覆盖和容量限制。

## 8. 主 Agent 与子 Agent

```text
主 Agent
  └── MemoryManager
      ├── SessionStore
      ├── ConversationCompactor
      └── LongTermMemory

PlanExecuteAgent
  ├── PlanStore                    任务恢复，不属于记忆
  └── 多个子 Agent
      └── MemoryManager
          ├── 目标 = 总任务 + 当前步骤
          └── 共享同一个 LongTermMemory
```

子 Agent 不保存会话，也不压缩短历史，但会携带目标锚点，并共享长期知识。

## 9. 面试表达

可以按三层讲：

> 第一层是会话记忆，保存真实消息并在达到 Token 阈值时压缩旧轮次；第二层是任务状态，
> 每轮把目标锚点和 Plan 上下文重新注入，防止多轮工具调用后偏离目标；第三层是长期记忆，
> 将跨会话经验落盘，并在新任务开始时检索一次、整个 ReAct 循环复用。

然后补充两条主链路：

```text
读：用户任务 → 检索并冻结长期记忆 → 组装 prompt → LLM
写：人工 /save 或 LessonExtractor → LongTermMemory.save → knowledge.json
```

最后说明设计取舍：

> 当前只有文件存储和关键词检索，数据量也限制在 50 条，所以没有为未来可能出现的向量库
> 提前维护多层接口；等第二种实现真正出现时再抽象。
