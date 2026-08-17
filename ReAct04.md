# RAG 问答闭环 + 官方实现源码对照（第四周）

> 目标：把混合检索接成真正的 RAG 问答（检索 → 拼接 prompt → 生成答案），然后对照 Spring AI 两代官方实现（1.x `QuestionAnswerAdvisor` / 2.0 `RetrievalAugmentationAdvisor`），定位手搓实现在官方抽象中的位置。
>
> 环境：Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 21 + DeepSeek。源码：`target/sai-1x/`（1.1.5）、`target/sai-src/`（2.0.0）。

## 最终成果

`src/main/java/com/ai/demo/rag/HybridRagDemo.java` 新增 `GET /hybrid/chat`：

- 复用混合检索（向量 + BM25 + RRF），融合后 limit 5
- 检索结果按 `[资料N]` 编号、`\n---\n` 分隔拼进 `RAG_TEMPLATE`（`PromptTemplate` 常量）
- 指令要求"仅根据资料回答 + 末尾标注 引用：[资料N]"
- 空索引早退（`corpus.isEmpty()`），不烧模型调用

调用链路：

```
POST /hybrid/index          → 向量库索引 + 内存 BM25 索引
GET  /hybrid/chat?query=... → 混合检索 → PromptTemplate 拼接 → DeepSeek 生成（带引用标注）
```

## 核心认知

### 1. PromptTemplate 是"字符串加工厂"，渲染发生在进 Prompt 之前

`new UserMessage("{content}...")` 里的花括号**只是普通字符**，`UserMessage` 不做任何替换。替换只发生在 `PromptTemplate.render(Map)`（`PromptTemplate.java:126`，源码在 `target/sai-src/org/springframework/ai/chat/prompt/`）。

- 三个出口粒度：`render()` → String（`:126`）、`createMessage()` → UserMessage（`:184`）、`create()` → Prompt（`:201`），内部全是先 render 再包装。
- 占位符语法 `{name}` 来自 ST 渲染器（`PromptTemplate.java:51` `StTemplateRenderer`）。
- render 只处理**模板串**，变量值原样插入、不二次解析——所以检索回来的文档原文（即使含 `{}`）直接塞 `{content}` 是安全的。

### 2. 1.x QuestionAnswerAdvisor：和手搓版同构，且对上下文长度零防护

`target/sai-1x/.../vectorstore/QuestionAnswerAdvisor.java`，`before()` 四步（`:108-135`）：检索（`:115`）→ join（`:121-123`）→ render（`:127-128`）→ 改写 user message（`:131-134`）。

- **超长上下文无防护**：`:121-123` 全量 join，无 token 计数/截断/压缩。topK 是数量控制不是长度控制。超窗口的下场是 API 直接报错，不是质量下降。长度责任全在调用方：切块大小 × topK 掐预算。
- **比手搓版差的两点**：无编号（没法核对引用）；空结果不兜底（空 context 照样渲染照样调模型）。
- **比手搓版好的一点**：`:119` + `:146` 把检索文档塞进 context 和 response metadata，调用方可观测"这次回答用了哪些资料"。

### 3. 2.0 RetrievalAugmentationAdvisor：RAG 管线化，每一步都是可插拔接口

`target/sai-src/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.java`，`before()` 七步（`:107-153`）：

| 步 | 干什么 | 对应手搓实现 |
|---|---|---|
| 1 QueryTransformer 链（`:118-122`） | 改写 query，1→1。Compression（多轮指代消解）/ Rewrite（口语→检索友好）/ Translation | 无 |
| 2 QueryExpander（`:124-126`） | 扩展 query，1→N。MultiQueryExpander 用 LLM 生成 N 个变体（模板 `:56-70`） | 无 |
| 3-4 并行检索 + join（`:129-138`） | 每个变体 `CompletableFuture` 并行检索，DocumentJoiner 合并 | 向量 + BM25 + RRF |
| 5 DocumentPostProcessor（`:140-143`） | 检索后处理（rerank / 过滤 / 压缩），**纯接口，2.0.0 无内置实现** | 无 |
| 6 QueryAugmenter（`:146-147`） | 拼 prompt | `RAG_TEMPLATE` |

- Step 1、2 解决"词面鸿沟"（用户措辞 vs 文档措辞），**BM25 在检索器层面兜底，query 改写在 query 层面进攻**，同一问题的两种解法。
- 增强不是免费的：Step 1、2 各多一次 LLM 调用，Step 2 多 N 倍检索量。所以 `:91`、`:125` 默认全关——**质量 vs 延迟/成本的交换由使用者决定**。

### 4. 官方默认 joiner 比手搓 RRF 糙

`ConcatenationDocumentJoiner.java`：按 DocID 去重保留首个（`:58`），再按原始 score 降序（`:61-62`）。

跨来源直接比原始分数**量纲不同没有意义**（向量余弦 0~1 vs BM25 任意正数）。RRF 只用排名（`1/(k+rank)`）绕开量纲。官方这个默认实现是为"同构召回"（多个 query 变体打同一个向量库）设计的，不适合异构召回源。**多源融合维度上，手搓 RRF 比官方默认实现讲究。**

### 5. 空结果处理：三代实现三种策略

2.0 的空上下文逻辑：`ContextualQueryAugmenter.java:112-114` 检索为空 → `:126-133` 判断 `allowEmptyContext`（默认 false，`:77`）→ `:132` **把用户原始 query 整个替换**为"礼貌拒绝"指令（`DEFAULT_EMPTY_CONTEXT_PROMPT_TEMPLATE`，`:72`）。常量→构造器赋值字段（`:98-99`）→ 字段在 `:132` 使用，所以直接搜常量名找不到渲染点。

| 实现 | 策略 | 粒度 | 代价 |
|---|---|---|---|
| 1.x QuestionAnswerAdvisor | 不管，空 context 照样拼 | — | 模型可能靠内部知识瞎编 |
| 2.0 ContextualQueryAugmenter | 替换 query 为"礼貌拒绝"指令 | 每次请求 | 多烧一次模型调用，回答自然 |
| 手搓 /hybrid/chat | 控制器早退，不调模型 | 全局（索引建没建） | 最省钱，但消息硬编码 |

手搓版的洞：索引建了但某条 query 什么都没召回时，会走到空 content 渲染。官方 2.0 的 per-query 判空恰好补这个。

## Review 修正清单（本轮踩过的坑）

| # | 问题 | 教训 |
|---|------|------|
| 1 | 资料块之间无分隔符，编号和正文粘连 | 拼给模型看的文本，边界符号（`[资料N]` + `\n---\n`）和指令措辞必须一字不差地对齐 |
| 2 | chat 端漏了 limit，融合结果全量进 prompt | topK 截断要在拼 prompt 前做，否则 token 暴涨且陷阱文档更容易混入 |
| 3 | prompt 写了"返回JSON"但没定义 schema | 无 schema 的 JSON 要求 = 模型自由发挥；要么定义结构，要么别要求 |
| 4 | 编号拼了但指令没让模型标注引用 | 格式（`[资料N]`）和指令（"引用：[资料N]"）是配套的，缺一半等于没做 |
| 5 | 空语料 guard 放进了公共 search()，导致 /hybrid/search 静默降级为纯向量 | 兜底要放在感知得到它的地方；静默降级最容易埋坑 |
| 6 | 为判空引入 commons-collections4（传递依赖） | 传递依赖哪天就没了，`list.isEmpty()` 足够 |

## 验收记录

**待自测**（当时环境依赖不可用，Milvus/Ollama 在 192.168.100.118）：

```
POST /hybrid/index
GET  /hybrid/chat?query=手搓ReAct要不要自己实现maxStep兜底
```

验收点：答案站笔记结论（需要自己 maxStep）还是陷阱结论（框架自带）；返回是否标注 `引用：[资料N]`；编号对应的块是否真实资料。

## 面试弹药

- "RAG 检索到的东西超长怎么办？" → 框架 1.x 完全不管，超窗口直接 API 报错；工程上靠切块大小 × topK 掐预算；2.0 留了 `DocumentPostProcessor` 钩子（压缩/rerank/过滤），但本体无实现，得自己挂。
- "query 改写和混合检索是不是一回事？" → 都解词面鸿沟：BM25 在检索器层兜底（词面匹配），query 改写/扩展在 query 层进攻（LLM 换措辞）。代价都绕不开延迟和 token。
- "多路召回结果怎么融合？" → 官方默认 ConcatenationDocumentJoiner 按 DocID 去重 + 原始 score 排序，跨源比分量纲不同有问题；RRF 只用排名，量纲无关，异构源更稳。
- "检索为空怎么处理？" → 三种策略对比（不管 / 替换为拒绝指令 / 控制器早退），权衡点在"是否多烧一次模型调用"和"判空粒度（per-query vs 全局）"。
- "手搓 RAG 和官方管线的差异？" → 官方把 RAG 拆成 preretrieval（改写/扩展）→ retrieval（多路+join）→ postretrieval → augmentation 的接口管线，每步可插拔；手搓版是一个方法全包，赢了可控性和 RRF，输了 query 理解和后处理能力。

## 待改进

- [ ] `/hybrid/chat` 端到端自测（环境恢复后）
- [ ] 对比纯向量 vs 混合检索 Top-K 差异，量化 BM25 收益（`/hybrid/compare` 端点）
- [ ] 手搓版补 per-query 空结果兜底（对齐 2.0 粒度）
- [ ] 响应里带上引用的检索文档（对齐官方 context/metadata 可观测性）
- [ ] 中文分词升级（jieba / HanLP）
- [ ] BM25 索引持久化
