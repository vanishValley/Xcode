# Xcode Agent CLI — 记忆模块设计方案

## 一、设计目标：围绕真实痛点，不套学术框架

coding agent 的记忆系统要解决三个实际问题，每个对应一种状态：

| 痛点 | 对应的状态 | 活的形态 |
|---|---|---|
| 上下文失效 — 任务长了，早期关键信息被压掉 | **对话历史**（压缩） | 在 prompt 里，每轮带 |
| 目标偏移 — agent 跑着跑着忘了原始目标 | **任务状态**（目标锚点） | 在 prompt 里，每轮重贴 |
| 重复踩坑 — 同一个坑反复踩，知识不沉淀 | **长期知识**（踩坑/约定/偏好） | 落盘，按需捞进 prompt |

架构就三样状态。心智模型：**一条读路径 + 一条写路径**。

---

## 二、读路径：每轮怎么拼 prompt

### 2.1 核心概念：干净历史 vs 临时注入

- **干净历史**：只存真实对话（user / assistant / tool），落盘到 `session.jsonl`
- **临时注入**：目标、长期知识、plan 报告，每轮 `assemblePrompt()` 临时拼进去，**不进历史、不落盘**

改之前注入块混在历史里，落盘也跟着写进去，每轮靠魔法字符串 `removeIf("## 相关记忆")` 手工擦。分离后这个问题消失。

### 2.2 assemblePrompt() 的组装顺序

```
[system] SYSTEM_PROMPT                                   ← 构造函数写入历史，固定
[system] 【当前目标】...                                  ← 有则贴，无则跳过
[system] ## 相关记忆\n- ...                               ← 从 KnowledgeBase 按用户输入检索
[system] 【Plan 执行报告】...                              ← 有则贴（/plan 跑完回灌）
... 干净历史（user / assistant / tool 消息）...
```

- 目标放最前：利用开头的高注意力区，防 lost-in-the-middle
- 知识放历史之前：让模型在"看对话"之前就知道该避什么坑

### 2.3 检索：关键词匹配 + 时间衰减

入口：`MemoryManager.assemblePrompt()` → `KnowledgeBase.retrieve(query, projectKey, topK=3)`

```
KeywordRetriever.retrieve()：
  1. 用最近一条 user 消息做查询
  2. jieba 分词 → token 集合
  3. 从 KnowledgeStore.visible(projectKey) 拿候选（当前仓库 PROJECT + 所有 GLOBAL）
  4. 每条记忆打分：命中率 × 时间衰减
     命中率 = matched / tokens.size()
     时间衰减 = max(0.5, 1 - ageHours / 24)    ← 留下限，老约定不被"老"压到 0
  5. 取 top-3，800 字符上限
```

为什么不用向量？量小（≤50 条）够用。`Retriever` 接口已经留了，以后想换向量时实现同一个接口、不动上层代码。

---

## 三、写路径：长期知识怎么入库

### 3.1 两条入口

```
手动 /save ──→ 治理门(去重) → 直接入库     ← HUMAN 来源，高信任
自动沉淀 ────→ 信号闸门 → LLM提炼 → 入库   ← AGENT 来源，绕过治理门
```

**手动 `/save`**：`/save 内容` → PROJECT 作用域；`/save -g 内容` → GLOBAL 作用域。过治理门做去重。

**自动沉淀**：子任务（或 ReAct）执行完，事后扫 transcript，有"失败→修正→成功"模式 → 调 LLM 提炼一句话 → 入库。

### 3.2 治理门：手动 /save 的去重

手动 `/save` 走的路径：`GovernanceGate.evaluate()` 判去重（精确相同 → REJECT，近似 → MERGE），其余直接 COMMIT。不搞人工确认——用户自己敲的命令，不需要再确认。

> 之前的方案里治理门还有 AGENT 置信判断和 DEFER 挂起。后来发现：agent 无法判断自己是不是幻觉，让 agent 给自己的幻觉打分然后决定放行 = 形同虚设。所以 AGENT 自动沉淀**完全绕过治理门**，信任不靠置信打分，靠触发条件（见 3.3）。

### 3.3 自动沉淀：信号闸门 + LLM 提炼 + 入库存保险

**为什么不怕幻觉回环** — 信任不靠事后审查，靠**触发条件的自证性**：

agent 凭空编了一个幻觉 → 它不会经历"失败→修正" → 信号闸门不触发 → 连提炼都不进。只有 agent **真的碰到了问题、真的修正了、真的成功了**，才会触发。这种场景下产生的知识，可信度天然比凭空生成的高得多。

**具体流程**（`LessonExtractor.java`）：

```
子任务 / ReAct 执行完
  │
  ├─ ① 信号闸门 hasFailureThenSuccess(history)
  │     扫 tool 消息，同一个工具先 error 后 success → 触发
  │     无信号 → 跳过（零成本，大多数平顺任务走这）
  │
  ├─ ② LLM 提炼 extract(history, llmClient)
  │     调一次 LLM："有值得记的经验吗？用一句话；没有说无"
  │     返回"无"或异常 → 静默跳过
  │
  └─ ③ 入库 KnowledgeBase.saveAgent(content, projectKey)
        三道保险：
          a. 精确重复 → 跳过
          b. 与已有 token 重叠 ≥ 0.7 → 覆盖旧的（知识更新，不是堆叠）
          c. AGENT 来源 ≤ 25 条，超了淘汰最老的 agent 条目（不动手动 /save 的）
```

**覆盖为什么安全**：两条知识 token 重叠 ≥ 0.7，说明说的是一件事。新知识是 agent 刚验证过的——覆盖旧的是更新，不是丢失。判断失误的损失是一条旧知识，可接受。

---

## 四、跨项目：scope 字段，不是单独系统

每条长期知识带一个 scope：

- `PROJECT` — 只在写入它的仓库可见
- `GLOBAL` — 到哪都可见（`/save -g`）

检索时按"当前仓库 + GLOBAL"过滤。**不做跨项目检索**——踩坑多是项目专属的，全局用 GLOBAL 就够了。

---

## 五、Agent 架构中的记忆

### 5.1 主 ReAct Agent

- 对话历史：有，落盘 + 压缩
- 长期知识：有（KnowledgeBase 检索 + 自动沉淀触发）
- 目标锚点：无（闲聊不需要）

### 5.2 Plan 模式的子 Agent

- 对话历史：有，空白起步，不落盘不压缩（最多 10 轮）
- 长期知识：有（**和主 Agent 共享同一个 KnowledgeBase**）
- 目标锚点：有（`setGoal("总任务 + 当前步骤")`，每轮重贴，防偏移）

### 5.3 共享关系

```
Main 的 ReAct Agent      ← MemoryManager(sessionStore, knowledgeBase, llm, projectPath)
PlanExecuteAgent(编排器)  ← 不跑 LLM 循环，只持有 KnowledgeBase
  └── 子 Agent 1          ← MemoryManager(knowledgeBase, projectPath) + setGoal(...)
  └── 子 Agent 2          ← 同上
```

共享的就一样：**KnowledgeBase**。子任务完成后扫 transcript 触发自动沉淀，写回的也是同一份 KnowledgeBase。

---

## 六、文件清单与职责

| 文件 | 职责 |
|---|---|
| `MemoryScope.java` | 枚举 PROJECT/GLOBAL |
| `MemorySource.java` | 枚举 HUMAN/AGENT |
| `MemoryRecord.java` | record，一条原子知识，不可变 |
| `KnowledgeStore.java` | 纯存储（存取+scope过滤+容量淘汰+落盘），不认识 jieba |
| `Retriever.java` | 检索接口，向量-ready |
| `KeywordRetriever.java` | jieba + 命中率×时间衰减，只读依赖 KnowledgeStore |
| `GovernanceGate.java` | 治理门接口（手动 /save 去重用） |
| `DefaultGovernanceGate.java` | 精确去重 + 近似去重，HUMAN 直通 |
| `KnowledgeBase.java` | 知识子系统门面，暴露读写 + saveAgent |
| `LessonExtractor.java` | 信号闸门 + LLM 提炼，自动沉淀入口 |
| `MemoryManager.java` | 记忆系统总门面：assemblePrompt / compact / persist / CRUD / tryAutoExtract |
| `SessionStore.java` | 会话持久化（session.jsonl） |
| `ConversationCompactor.java` | LLM 摘要压缩，按轮次切，双级降级 |
| `TokenBudget.java` | token 估算 + 压缩阈值（chars/2.5，80%触发） |
| `PlanStore.java` | Plan 断点 checkpoint（和记忆解耦） |

### 落盘布局

```
~/.xcode/projects/<项目名>@<hash>/
  ├── session.jsonl          ← 对话历史（干净转录）
  ├── knowledge.json         ← 长期知识
  └── plan_checkpoint.json   ← Plan 断点（不是记忆）
```

---

## 七、当前代码状态

### 已落地（94 测试全绿）

- [x] 长期知识子系统（KnowledgeStore / KeywordRetriever / GovernanceGate / KnowledgeBase）
- [x] 破环（Retriever → Store 单向依赖）
- [x] 修 global 不可达 bug（`/save -g`）
- [x] `assemblePrompt()` 统一组装，删除魔法字符串
- [x] 子 Agent 共享 KnowledgeBase + 目标锚点
- [x] 自动沉淀：信号闸门 + LLM 提炼 + 去重覆盖入库（LessonExtractor / saveAgent）
- [x] **Multi-Agent（Planner / Worker / Reviewer）**：Reviewer 只读工具 + 轻量 ReAct 验证产物，不通过打回重做（最多 2 次），审察者故障宽容放行
- [x] ReviewResult 解析防御层（JSON 容错：字段缺失/类型变换/markdown 包裹）
- [x] Reviewer 控制流 + ReviewResult 解析单元测试（10 个）

### 待实现

- [ ] Planner 规划时也喂长期知识（让计划提前绕开已知坑）
- [ ] 并行执行

---

## 八、面试怎么讲

### 一句话

> "记忆系统就三样状态——对话历史做压缩、任务状态做目标锚点、长期知识做跨会话复用。核心是读写两条路径：读的时候 assemblePrompt 每轮拼目标和相关知识；写的时候手动 /save 直接进，自动沉淀靠信号闸门——只在 agent 真踩坑真修好后才触发提炼，不靠置信打分，靠触发条件自证。"

### 关键面试点

1. **自动沉淀的信任模型**：不靠 agent 给自己打分（能打分就不会幻觉），靠"踩坑→修正→成功"这个行为模式自证可信
2. **干净历史 vs 临时注入**：分离后落盘无污染、删掉魔法字符串
3. **覆盖而非堆叠**：自动沉淀的同类知识覆盖旧的，保持库小且新鲜
4. **关键词 vs 向量**：量小够用，留接口随时升级——知道边界在哪
5. **子 Agent 共享知识**：同一份 KnowledgeBase 引用
6. **跨项目靠 scope 字段**：不是单独系统

---

## 九、简历写法

> **记忆系统**：为 Agent CLI 设计分层记忆架构，覆盖短期上下文管理、长期知识复用、父子 Agent 记忆共享三类场景，带会话持久化与任务断点续跑。

- 针对上下文溢出：实现 Token 预算 + LLM 摘要压缩，最近轮次保留原文、早期轮次压缩为摘要，配合降级兜底控制窗口不超限。
- 针对目标偏移：实现分层 prompt 组装，父子 Agent 共享长期知识、子任务锚定总目标，保证多轮执行不偏航。
- 针对重复踩坑：实现关键词检索 + 双路径沉淀，知识跨会话复用，入库去重防膨胀。

---

## 十、测评方案

> 三个维度各设对照实验，指标可直接从执行日志统计，不需要外部 benchmark。

### ① 上下文溢出 — 窗口占用率

**对照组**：关闭压缩器，跑 20 轮工具调用任务。每轮产生 user/assistant/tool 三条消息，到第 15 轮左右超出上下文窗口，API 返回 400，任务中断。

**实验组**：开启压缩器。第 12 轮 Token 占用触及 80% 阈值自动触发：消息按 user 切分轮次，最近 3 轮保留原文，更早的 9 轮发送 LLM 压缩成摘要。压缩后字符数从约 15,000 降到约 1,500，窗口占用率回落至 50%，后续继续正常执行，20 轮跑满不超限。

**追问点**：为什么按 user 切分不按条数？—— tool_call/tool_result 必须配对，中间切断 API 报错，user 消息是唯一安全边界。压缩失败怎么办？—— LLM 异常或压缩后仍超限，降级为强制截断保留最近 N 轮。

**指标**：有压缩时 20 轮窗口占用率 < 80%，无压缩时约第 15 轮超限中断。压缩后占用率降低约 40-60%。

### ② 目标偏移 — 无关工具调用率

**对照组**：给一个 8 步 Plan 任务，子 Agent 只拿到子任务描述，不注入总目标。前几步正常，到中后段 Agent 开始执行与当前步骤无关的操作——读取未被依赖的文件、执行无关命令。

**实验组**：子 Agent 创建时注入总目标，每轮拼在 prompt 最前。Agent 每轮都看到自己服务于哪个总任务。事后扫子 Agent 的 history，标记工具调用参数与子任务描述的相关性，不相关的记为"无关调用"。

**追问点**：为什么不在 system prompt 里多写一行？—— system prompt 在对话中段会被 attention 衰减（lost-in-the-middle），每轮拼在最前注意力最高。

**指标**：有目标锚点时无关调用率趋近于 0%，去掉锚点后中后段子任务出现 15-30% 无关调用。

### ③ 重复踩坑 — 同类错误复发率

**对照组**：Agent 首次构建项目，mvn test 报错 JAVA_HOME 未设 → 修正成功，但没记录。重启新会话，同样任务，再次报错 → 再次修正。同一个坑每次新会话都重踩。

**实验组**：首次修正成功后，信号闸门在 transcript 里检测到"同工具先 error 后 success"模式，触发 LLM 提炼：`"这仓库跑测试前要设 JAVA_HOME=D:/jdk/jdk17"`，入库。重启新会话，用户说"帮我构建项目"，assemblePrompt 用"构建"做 jieba 分词 → 命中这条知识 → 注入 prompt。Agent 第一次就设对 JAVA_HOME，直接成功。

**追问点**：自动沉淀怎么防幻觉？—— 触发条件是"失败→修正→成功"，Agent 凭空编的幻觉不会经历这个模式，信号闸门根本不触发。信任不靠 agent 自评，靠行为自证。

**指标**：入库后同类错误复发次数降为 0。构造 5 个已知坑做样本，首次踩坑后全部跨会话复用。
