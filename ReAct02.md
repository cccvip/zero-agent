# ReAct 读源码对照笔记（第二周）

> 方法：带着手搓版（`ReActAgent.java`）当地图，去 Spring AI 2.0 源码里找对应物，逐条对答案。
> 源码来源：`F:\maven\repository` 下的 sources jar（已解压关键包到 `target/sai-src/`）。

## 一、入口修正：ToolCallAdvisor 已废弃

- `ToolCallAdvisor` 标记 `@Deprecated(since = "2.0.0", forRemoval = true)`，是个空壳，全部逻辑在父类 **`ToolCallingAdvisor`**
- Spring 惯用的废弃方式：改名时保留旧类转发一个版本周期；javadoc 里 `in favor of` 指向替代者
- 经验：读源码见 `@Deprecated` 先看 javadoc 指向谁，直接跳过去

## 二、ToolCallingAdvisor 对答案

源文件：`org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java`

### 问题 1：循环结构

- **我的实现**：`ReActAgent.java:48` `while(true)` + 内部 break
- **框架实现**：`ToolCallingAdvisor.java:139-185` `do { ... } while (isToolCall)`
- **差异**：结构等价，do-while 更明确表达"至少问一次模型"

### 问题 2：步数上限 —— 重要发现：框架没有 maxStep

- **我的实现**：`maxStep=5`，超限兜底返回（`ReActAgent.java:52-55`）
- **框架实现**：**不存在**。循环唯一刹车是 `ToolExecutionEligibilityChecker`（`ToolCallingAdvisor.java:157`），默认实现仅判断 `chatResponse.hasToolCalls()`（第 75-76 行）——模型不喊停就不停
- **框架的思路**：把终止权做成**可插拔接口**（builder 第 462 行可注入自定义 checker，javadoc 第 458 行明示可覆盖 stop-reason 逻辑）。想要步数上限？自己实现带计数器的 checker
- **结论**：手搓版在这个维度比框架更防御。框架不提供主见（unopinionated）的限制，只提供扩展点
- 面试弹药："Spring AI 怎么防工具死循环？" → 默认靠模型自觉 + 可插拔 `ToolExecutionEligibilityChecker` 自定义上限

### 问题 3：历史消息从哪来

- **我的实现**：`run()` 内的局部 `List<Message>`，全量重发
- **框架实现**：`toolExecutionResult.conversationHistory()`，由 `DefaultToolCallingManager.executeToolCalls()` 组装（`ToolCallingAdvisor.java:161-162` 调用）
- `doGetNextInstructionsForToolCall`（第 190-202 行）**不生产历史，只做选择题**：
  - `conversationHistoryEnabled = true`（默认，第 429 行）：返回完整历史
  - `false`：只保留 system message + 最后一条（第 198 行），历史改由链上的 `MessageChatMemoryAdvisor` 补充（第三周会话持久化的挂点）

## 三、Advisor 链架构（本周最大认知收获）

### 本质

Servlet Filter 链 / Spring 拦截器链的 AI 版。每次调用穿过一串 Advisor（按 `getOrder()` 排序，请求正序、响应倒序），最后才到 `ChatModel`。

### 默认链组成

`DefaultChatClientBuilder.java:105-106`：**默认链上只有 `ToolCallingAdvisor` 一个**。

→ 这解释了第一周的"灵异现象"：只写 `.tools(tool).call()` 工具却自动执行了，就是默认链上的 ToolCallingAdvisor 替跑了 do-while。

可选顾问（显式注册才生效）：`MessageChatMemoryAdvisor`（会话记忆）、`SimpleLoggerAdvisor`（日志）、`SafeGuardAdvisor`（敏感词）等。

### 每次 LLM 调用都跑整个链路吗

是，且更彻底：`ToolCallingAdvisor.java:150` 的 `callAdvisorChain.copy(this).nextCall(...)` 在 do-while **内部**——工具循环的**每一轮迭代都重新穿越剩余顾问链**（`copy(this)` 把自己摘出防递归）。

一次"问时间"请求 = 2 次完整链路穿越 = 2 次 DeepSeek HTTP 调用。

### 设计动机（对比手搓版）

- 手搓版循环是**封闭的**：每轮想加日志/记忆/监控，只能改 `ReActAgent` 代码
- 框架把循环拆成"循环控制器 + 每轮可插拔的链"：每轮想加任何行为，注册一个 Advisor 即可，循环代码零改动
- javadoc 第 52-53 行自述动机："enables intercepting the tool calling loop by the rest of the advisors"
- **OCP（开闭原则）的教科书案例**：第三周加 Redis 持久化时，`MessageChatMemoryAdvisor` 挂链上即生效

## 四、任务 2 完成：DefaultToolCallingManager 对答案

源文件：`org/springframework/ai/model/tool/DefaultToolCallingManager.java`

### 问题 1：conversationHistory 组装顺序 ✅ 与我一致

`buildConversationHistoryAfterToolExecution`（第 251-257 行）：previousMessages → assistant **原样**入列 → toolResponseMessage 入列。与手搓版一字不差，协议理解验证通过。

### 问题 2：名字匹配 —— 也是线性扫描，但有第二道检索

- 第 196-199 行：`stream().filter(name 比对).findFirst()`——**没有 Map，同为 O(n)**（之前猜错了）
- 关键差异在后半句 `.orElseGet(() -> this.toolCallbackResolver.resolve(toolName))`：本地查不到会问可插拔的 `ToolCallbackResolver` 链（可从 Spring 容器、MCP server 动态解析工具）
- 我的版本：查不到即判死刑；框架：本地列表只是第一来源

### 问题 3：幻觉工具名 —— 框架直接抛异常

- 第 206 行：`throw new IllegalStateException("No ToolCallback found for tool name: ...")`，整个请求失败，**不回喂模型**
- 对比：我回喂"工具不存在"让模型自我纠正（线上更韧性）；框架选择暴露失败（开发期更易发现 bug）
- trade-off：幻觉工具名是开发期 bug 还是运行期常态？两种策略各有立场
- 附带细节：第 203-204 行会先打 warn 日志，提示"LLM 可能改写了工具名"（名字被截断等场景）

### 问题 4：工具异常 —— 与我相同的回喂策略，但做成扩展点

- try-catch 在 `DefaultToolCallingManager` 第 233-238 行（**不在 Advisor 里**，第一次读时判断错了位置）
- 语义：catch 后经 `ToolExecutionExceptionProcessor.process(ex)` 转成错误文案，作为工具结果回喂模型——**和手搓版的异常回喂是同一策略**
- 差异 1：`ToolExecutionExceptionProcessor` 是接口，可注入自定义实现（统一格式、脱敏、引导语）
- 差异 2：只 catch `ToolExecutionException`，其他 RuntimeException 直接穿透；我的 `catch(Exception)` 更宽但会掩盖编程错误（NPE 也被当工具失败回喂）

### 框架比手搓版多做的（生产级差距清单）

| 能力 | 位置 | 说明 |
|---|---|---|
| 空参数兜底 | 第 185-194 行 | arguments 为 null/空时用 `"{}"` 兜底（流式模式常见） |
| null 结果处理 | 第 243-244 行 | 工具返回 null 转成 `""`，避免 "null" 字符串进历史 |
| returnDirect | 第 209-214 行 | 工具结果可跳过模型直接返回用户（如查余额直接展示） |
| 可观测性 | 第 227-241 行 | 执行包在 Micrometer `Observation` 里，工具调用耗时/成功率可进监控 |
| ToolContext | 第 147-156 行 | 可向工具传递请求级上下文（不透给模型的数据，如用户身份） |

### 本任务的方法论收获

- 读源码第一遍的判断要标注置信度——"try-catch 在 Advisor 里"这个结论实际在 Manager 里，位置判错导致语义完全理解反
- 框架 vs 手搓的对照不是"框架全对"，本次就发现两处手搓版更防御（maxStep、幻觉工具名回喂）

## 五、可观测性实战（actuator + Prometheus 端点）

### 接入方式

- 依赖：`spring-boot-starter-actuator` + `micrometer-registry-prometheus`，零 Java 代码
- 坑：actuator 端点分"存在"和"暴露"两层，默认只暴露 `health`，`prometheus` 需显式配置：
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,info,prometheus
  ```
- `/actuator/prometheus` 是 Prometheus 抓取的机器接口（`指标名{标签} 数值`），人读用 `/actuator/metrics`

### 实测数据与发现

问一次"现在几点"后的指标解读：

```
gen_ai_client_operation_seconds_count 2        ← 模型调了 2 次（toolCall + 回喂后再问）
gen_ai_client_token_usage_total{type="input"}  1453
gen_ai_client_token_usage_total{type="output"} 202
```

**发现 1：input/output 悬殊（1453 vs 202）是"病历本全量重发"的直接证据。**
第一轮的消息在第二轮又完整发了一遍，输入 token 是输出的 7 倍。第一周的机制理解，在监控里变成了可见的数字——token 成本主要烧在"重复发送历史"上，这就是上下文压缩策略的价值锚点。

**发现 2：工具调用指标缺失——绕过框架的代价。**
输出里只有模型调用（`gen_ai_client_operation`）和 HTTP 层（`http_client_requests`）指标，**没有工具调用指标**。原因：工具埋点在 `DefaultToolCallingManager` 里，而手搓 Agent 绕开了它（自己查表、自己 `callback.call()`），框架的 Observation 没机会执行。模型指标有，是因为它在 `DeepSeekChatModel` 内部，绕不开。

→ 对照案例：**绕开框架获得控制权的同时，也失去了框架的免费能力。** 第二周"框架比我多做的：可观测性"一条得到实锤。

### 面试素材

- "为什么 Agent 需要可观测性"：线上排查"模型变慢/变蠢"只能靠每次模型调用、工具调用的耗时与成功率指标
- "token 成本主要在哪"：用实测的 input/output 悬殊说明全量重发机制，引出压缩策略
- 第三周演示：Docker 起 Prometheus + Grafana 出"工具调用耗时"图，作为作品集素材
