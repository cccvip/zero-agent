# zero-agent

不依赖 Agent 框架，手搓一个 ReAct Agent，再逐行对照 Spring AI 2.0 源码验证自己的实现。

这个仓库不是要做框架，而是把 Agent 的机制亲手实现一遍。学习过程、踩过的坑、源码对照结论都记在 ReAct01~05 里，仓库的主要内容是这些记录。

## 为什么不用现成框架

`ChatClient.tools(x).call()` 三行就能跑通工具调用，但学不到东西：工具为什么能被调用、循环什么时候终止、上下文为什么会爆，全是黑盒。

所以我站在 Spring AI 最底层（`ChatModel`）手写整个 ReAct 循环，写完再读框架源码对答案。先手搓过一遍，读源码就是验证答案，不是看天书。

## 手搓实现（约 100 行）

核心在 [ReActAgent.java](src/main/java/com/ai/demo/react/ReActAgent.java)：

```
消息列表（唯一状态）→ 全量重发给模型 → 模型返回文本则结束
                                    → 返回 toolCall 则查表执行 → 结果回喂 → 继续循环
```

实现的机制：

- 消息列表驱动对话历史（模型是无状态的，历史由客户端全量维护）
- 工具按名查表执行（`ToolCall.name()` → `ToolCallback.call()`）
- 工具异常回喂（错误信息作为工具结果返回，模型据此自我纠正）
- 幻觉工具名兜底（回喂"工具不存在"而不是崩溃）
- maxStep 死循环兜底

## 读源码对答案

手搓完成后逐一对照 Spring AI 2.0 源码，结论摘要：

| 对照点 | 我的实现 | 框架实现 | 结论 |
|---|---|---|---|
| 循环结构 | `while(true)` + break | `ToolCallingAdvisor` do-while（:139-185） | 等价 |
| 步数上限 | maxStep=5 硬兜底 | 不存在，靠可插拔 `ToolExecutionEligibilityChecker` | 我的更防御 |
| 历史组装 | assistant 原样入列 → ToolResponseMessage 入列 | `DefaultToolCallingManager.buildConversationHistoryAfterToolExecution`（:251-257） | 一字不差 |
| 幻觉工具名 | 回喂"工具不存在"，模型自我纠正 | 直接 `IllegalStateException` 让请求失败（:206） | 策略 trade-off |
| 工具异常 | `catch(Exception)` 回喂错误文案 | 只 catch `ToolExecutionException`，走可插拔 processor 回喂（:233-238） | 同策略，框架更工程化 |
| 可观测性 | 无 | Micrometer Observation 包裹每次工具执行 | 框架免费能力 |

完整对照记录（含行号）：[ReAct02.md](ReAct02.md)

## RAG 问答与对抗性案例

核心在 [HybridRagDemo.java](src/main/java/com/ai/demo/rag/HybridRagDemo.java)：

- 向量检索（Milvus + bge-m3）+ BM25 关键词检索 + RRF 融合
- 检索结果按 `[资料N]` 编号、以 `\n---\n` 分隔拼进 Prompt
- 指令要求模型仅根据资料回答，并在末尾标注 `引用：[资料N]`
- per-query 空结果兜底，资料无关时不调模型

陷阱文档实验：故意写入一段与笔记结论相反的干扰文档。`/hybrid/search` 会把它排在 Top1，
单纯提高 BM25 在 RRF 中的权重也拉不下来。最后的解法是给资料打 `source` metadata +
prompt 显式优先级，让模型在生成侧区分并忽略错误资料。

完整记录与源码对照（含 Spring AI 1.x / 2.0 RAG 管线）：[ReAct04.md](ReAct04.md)

## 实测发现（可观测性）

接入 actuator + Prometheus 后的两个真实指标：

- input/output token 悬殊（1453 vs 202）。"对话历史全量重发"的数字证据：token 成本主要烧在重复发送历史上，这是上下文压缩策略的价值锚点。
- 工具调用指标缺失。手搓版绕过了 `DefaultToolCallingManager`，框架埋点不执行——获得控制权的同时也失去了框架的免费能力。

## 错误清单

学习过程中犯了 10 个典型错误，全部记了根因和教训，例如：

- 把模型的"调用请求"（ToolCall 数据）当成"工具实现"去包装——概念混淆
- 建了 toolCallbacks 却没装进请求 options——模型根本看不见工具，且不报错，最阴险的一类 bug
- catch 异常只 printStackTrace 不回喂——下一轮协议校验直接 400

完整清单：[ReAct01.md](ReAct01.md)

## 关键认知

1. 模型是失忆的，消息列表是 Agent 唯一的记忆载体，每轮全量重发。
2. 工具是模型"决定"、代码"执行"。模型只输出结构化意图，碰不到任何资源。
3. 智能在决策层不在流程层。手搓版没有任何重试逻辑，但工具报错后系统表现出了重试行为——那是模型的自主决策。
4. 防线是分层的。防死循环的第一道防线是模型自己的判断力，maxStep 只是最后的安全网。
5. 框架 = 循环控制器 + 每轮可插拔的 Advisor 链。每轮工具迭代重走顾问链，加能力只需注册 Advisor。

## 技术栈

Spring Boot 4.1 · Spring AI 2.0 · JDK 21 · DeepSeek · Milvus + Ollama(bge-m3) · Redis · Micrometer + Prometheus

## 路线

- [x] 第一周：手搓 ReAct 循环 + 故意踩坑（工具异常/死循环/上下文膨胀）
- [x] 第二周：读 Spring AI 2.0 源码对答案 + 可观测性接入
- [x] 第三周：混合检索实战 —— BM25 + RRF + 陷阱文档（[ReAct03.md](ReAct03.md)）
- [x] 第四周：RAG 问答闭环 + 官方两代 RAG 源码对照 + 陷阱文档压制（[ReAct04.md](ReAct04.md)）
- [ ] 第五周：完整演示项目 —— ReActAgent 升级版 + 工具注册表 + Redis 会话持久化（骨架与核心实现已落地，进行中，见 [ReAct05.md](ReAct05.md)）
