# ReAct 手搓总结（第一周）

> 目标：不依赖任何 Agent 框架，基于 Spring AI 2.0 的 `ChatModel` 层手搓一个 ReAct Agent，理解 Agent 的核心机制。
>
> 环境：Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 21 + DeepSeek

## 最终成果

`src/main/java/com/ai/demo/react/ReActAgent.java`：约 100 行的手写 ReAct 循环，具备：

- 消息列表驱动的完整对话历史管理
- 工具调用的查表匹配与执行（`TimeTool`：取时间、时间加算、故意会抛异常的加法器）
- 工具异常回喂（错误信息作为工具结果返回给模型）
- 幻觉工具名兜底（回喂"工具不存在"）
- maxStep 死循环兜底（超限返回明确提示）

调用链路：`ChatController → ReActAgent.run(messages, prompt) → DeepSeekChatModel`

## 核心认知（这一周真正学到的东西）

### 1. 模型是失忆的，消息列表是唯一的记忆

LLM API 是无状态的：每次调用都是独立事件，服务器不记得上一轮。所谓"对话"是客户端每轮把**全量历史重发**制造出来的幻觉。消息列表就是病历本，模型是每次看完诊就失忆的医生。

推论：token 费用随轮次超线性增长（每轮重发全部历史）；上下文爆炸是必然问题，所以才有截断 / 摘要压缩 / 滑动窗口这些策略。

### 2. 工具是模型"决定"、你的代码"执行"

注册工具 = 把工具的 JSON Schema（名称、描述、参数结构）随请求发给模型。模型"调工具"只是返回一段结构化数据 `{name, arguments}`，它碰不到你的任何资源。执行、结果回喂（带 `tool_call_id` 关联）、再调模型——全是客户端代码的活。

### 3. 智能在决策层，不在流程层

手搓的循环里**没有任何重试逻辑**，但工具报错后系统表现出了重试行为——那是模型读到错误回喂后自主决定的。ReAct 的 "Re-" 就体现在这：每轮根据 Observation 重新推理下一步。流程层（我的代码）只是忠实的执行器 + 传话筒。

### 4. 防线是分层的

以死循环为例：第一道防线是**模型自己的判断力**（实测 DeepSeek 重试两次后自主放弃，正常情况 maxStep 根本不会被触发）；maxStep 是最后的安全网，防的是病态情况。踩坑的目标不是"让每个坑都发作"，而是理解每道防线在什么条件下生效。

### 5. 协议细节

- assistant 消息（含 toolCalls）必须**原样入列**，工具结果消息通过 `tool_call_id` 与之配对，缺一个下一轮直接 400
- 一轮多个 tool call 的结果要打包成**一条** `ToolResponseMessage`（信封）入列，单个 `ToolResponse`（信纸）不是 Message
- 判断分支要用结构（`hasToolCalls()`）而非字符串（finishReason 各厂商不统一）

## 错误清单（按发生顺序）

| # | 错误 | 根因 | 教训 |
|---|------|------|------|
| 1 | 看到 `toolCalls` 为空以为失败 | 不懂 ChatClient 默认由 `ToolCallAdvisor` 内部跑完循环，最终响应本来就没有 toolCalls | 先理解框架分层（ChatClient → Advisor → ChatModel），再看现象 |
| 2 | 每轮循环 new 只含 UserMessage 的 Prompt | 不理解模型无状态 | 消息列表是全量重发的"病历本"，这是 Agent 唯一的状态 |
| 3 | 只处理 STOP 分支，无 tool call 分支 | 对 tool calling 流程理解不完整 | 分支判断用结构（hasToolCalls），不用字符串（finishReason） |
| 4 | `ToolCallbacks.from(toolCall)` | **概念混淆：把模型的"调用请求"（数据）当成"工具实现"（代码）** | ToolCall 是菜单上点的菜，ToolCallback 是后厨的厨师；靠 name 查表关联 |
| 5 | `messages.add(toolResponse)` | 没看清类型层级 | ToolResponse 是信纸、ToolResponseMessage 是信封，列表只收信封 |
| 6 | 建了 toolCallbacks 却没装进 options | 不理解同一份 callback 身兼两职 | `getToolDefinition()` 给模型看菜单，`call()` 给自己执行。**此 bug 不报错**——模型会假装回答，是最阴险的一类 |
| 7 | catch 异常只 printStackTrace | 不知道回喂的必要性 | 异常必须回喂为工具结果，否则下一轮协议校验 400；且模型能利用错误信息自我纠正 |
| 8 | `messages` 作为 `@Component` 单例的实例字段 | 忘了 Spring bean 的生命周期 | 对话状态必须是请求级的；会话持久化（Redis）要按会话 ID 存取，不是挂字段 |
| 9 | 超限时把提示塞进 messages 但返回值仍是空串 | 混淆"历史记录"和"返回值" | 塞入的消息永远不会被发出；调用方拿到空串无法区分"没话说"和"跑飞了" |
| 10 | 用 `ToolCallingChatOptions` 替换 `DeepSeekChatOptions` 导致 ClassCastException | 听信了"通用接口可移植"的建议 | 反编译证实 `DeepSeekChatModel` 对 options 是**硬强转**。直调 ChatModel 层就必须接受厂商绑定；通用 options 只在 ChatClient 层有效 |

> 注：#10 的建议来自 AI，通过"跑一下、看异常、反编译验证"的方式证伪——先信运行结果，再去源码找证据，这个流程本身是最大的收获之一。

## 已验证的行为（验收记录）

- 单轮 tool call：问"现在几点"，工具真实执行，返回真实时间 ✅
- 错误回喂：故意 `1/0` 的工具报错后，模型自主重试两次、然后放弃并给出解释 ✅（重试是模型的决策，不是代码的）
- 死循环防线：返回"查询失败请重试"的工具，模型两次后自主放弃，未触发 maxStep ✅（模型判断力是第一道防线）

## 环境备忘

- 系统默认 `mvn` 使用 JDK 17，本项目要求 21；命令行构建需先指定 `JAVA_HOME` 到 jdk-21
- Windows 控制台 GBK 编码导致中文日志乱码，与框架无关

## 第二周前置发现：SAA 兼容性

- Spring AI Alibaba 官方版本对照表：SAA 1.0.0.x 对应 **Spring AI 1.0.0 + Spring Boot 3.4.x**（[FAQ](https://java2ai.com/en/docs/1.0.0.2/faq/)）
- 社区已有讨论询问 Spring AI 2.x / Boot 4.x 支持时间线（[Discussion #3894](https://github.com/alibaba/spring-ai-alibaba/discussions/3894)），尚无明确结论
- 结论：当前项目（Spring AI 2.0 + Boot 4.1）**大概率无法直接引入 SAA**。第二周路线倾向：不引依赖，直接读 SAA / Spring AI 2.0 源码"对答案"

## 第二周计划（读源码对答案清单）

带着手搓的经验去读框架源码，找以下对应物：

1. 我的 maxStep 循环 → `ToolCallAdvisor`（Spring AI 2.0 的工具循环在顾问层）
2. 我的查表 + 执行 + 回喂 → `DefaultToolCallingManager.executeToolCalls()`
3. 框架比我多处理了什么 → 流式、观测、工具上下文（ToolContext）、事件回调
4. 上下文压缩策略 → 框架的 ChatMemory / Advisor 体系怎么做的

## 待办

- [ ] git 提交第一周代码（提交历史是作品集的一部分）
- [ ] 读 `ToolCallAdvisor` 与 `DefaultToolCallingManager` 源码，逐条对答案
- [ ] RAG：Milvus（Docker）+ BGE（Ollama）+ 混合检索
