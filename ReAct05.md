# 检索质量进阶：对比实验端点 + jieba 分词 + 持久化思辨（第五周）

> 目标：量化 BM25 收益的对比实验端点；中文分词从单字切升级到 jieba；论证"BM25 索引持久化"为什么不该做。
>
> 环境：Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 21 + jieba-analysis 1.0.2（注意：1.0.4 不存在，中央仓库最新就是 1.0.2）

## 最终成果

`src/main/java/com/ai/demo/rag/HybridRagDemo.java` 本轮变更多处：

- 新增 `GET /hybrid/compare`：同一 query 下 A 组（纯向量）vs B 组（混合检索）并排对比
- `tokenize()` 换 jieba（SEARCH 模式），签名不变，调用方零改动
- pom 新增 `com.huaban:jieba-analysis:1.0.2`

## 核心认知

### 1. 对比实验的设计要点（/hybrid/compare）

返回结构：`query / vectorOnly / hybrid / bm25OnlyNewDocs`，每条目 `{rank, source, preview}`。

- **可比性前提**：两组检索阶段 topK 必须一致（都是 5），否则对比无意义；
- **`bm25OnlyNewDocs` 是灵魂字段**：B 组有而 A 组没有的块 = BM25 的净贡献；
- **判"同一块"用正文文本做 key**——和 `rrfFuse` 的去重口径一致；两路检索返回的不是同一批 `Document` 对象，不能用对象相等判断；
- 空语料要兜底，否则 B 组静默退化成纯向量，实验白跑。

### 2. 单字切的结构性问题 & jieba 升级

- 单字切让"死循环"变成"死/循/环"三个独立 term——"环"在"循环/环路/耳环"里是同一个 term，**DF 虚高 → IDF 失真 → 区分度消失**；
- 旧实现中英文粒度逻辑是反的：中文拆单字、英文却按 `a-z0-9` 聚合成词；
- jieba 中文按词切、英文数字保持完整词，`tokenize()` 从 25 行缩到 6 行；
- 实测发现：jieba 输出**含空格 token**，必须 `filter(!isBlank())`，否则空格进索引成垃圾 term；
- **OOV（未登录词）兜底是单字**："手搓"不在词典里，被切成"手/搓"。jieba 支持用户自定义词典补新词（未做）；
- `JiebaSegmenter` 构造加载词典（实测 1.6s），必须全局单例；官方未承诺线程安全（**待查证**，并发场景需同步或 ThreadLocal）；
- SEARCH vs INDEX 模式：在短句上差异不明显，INDEX 对长词会再拆细。BM25 索引用的是 SEARCH。

### 3. "BM25 索引持久化"为什么不该做（本轮最值钱的结论）

持久化的动机是"重启后索引没了"，但成本倒挂：

- **不持久化的代价**：重启后重跑 `loadDocuments()` + `buildBm25Index()`——读本地 md + 内存算词频，秒级、纯本地、零网络；
- **持久化的代价**：几百 MB 的 JSON 副本 + 读写 IO + 与源文件的一致性问题。

关键判断：**语料源头（md 文件）本来就在磁盘上，它们才是 source of truth**。持久化 corpus 等于把磁盘上已有的数据复制一份再存回磁盘，白占空间还制造一致性问题。真正贵的环节是 embedding（每块调一次 Ollama），而向量索引 Milvus 已经持久化了。

> 架构判断原则：**持久化计算产物之前，先问两个问题——源数据能否重放？重建成本多少？**

真正需要持久化索引的场景（语料不可重放 + 重建成本极高），工业答案也不是自己序列化文件，而是**直接上 Elasticsearch/Lucene**——BM25 是 Lucene 的原生打分器，持久化/增量更新/分片全是现成的。手写 BM25 的价值在学习，不在生产。

### 4. 接口隔离的红利

`tokenize()` 签名不变，分词器整个换掉，`buildBm25Index` / `bm25Search` 一行没改——内部实现升级被接口完全隔离。

## Review 修正清单

| # | 问题 | 教训 |
|---|------|------|
| 1 | compare 里手写 for 循环"复制"列表 + `if(i>10) break` | 三重问题：topK=10 的结果循环等于全量复制（死代码）、`i>10` 放行了 11 条（off-by-one）、复制出的 list 没人用。要截断就 `.stream().limit(5)` |
| 2 | jieba 版本写了 1.0.4 | 凭印象写版本号翻车——中央仓库最新只有 1.0.2，且失败被本地仓库缓存（`-U` 强制更新才解决）。新依赖先查仓库再写 |

## 验收记录

- jieba 分词实测（`main()`）：`手搓 ReAct 循环 maxStep 123` → `["手","搓","react","循环","maxstep","123"]` ✓ 英文/数字完整、"循环"成词、"手搓" OOV 单字兜底（符合预期）
- `/hybrid/compare` 端到端对比测试：已完成三类 query 实测（Milvus 集合 `react_rag`，jieba SEARCH 模式，BM25 weight=2.0 / vector weight=1.0 / RRF k=60）。结果如下：

| query 类型 | vectorOnly Top1/特征 | hybrid Top1/特征 | bm25OnlyNewDocs 净贡献 | 关键观察 |
|---|---|---|---|---|
| 关键词型 `maxStep` | 第 1~3、5 名：陷阱文档/未知；仅第 4 名标为「陷阱文档」 | 第 1 名：陷阱文档；第 2~5 名：ReAct01/02 真实笔记 | 5 条：ReAct01 的「验收记录」「第二周计划」「最终成果」 + ReAct02 的「ToolCallingAdvisor 对答案」+ 1 条「未知」 | 关键词稀有，BM25 靠精确匹配把真实笔记从向量盲区里拉回来；但陷阱文档仍因字面强相关占 hybrid Top1 |
| 语义型「怎么防止工具调用死循环」 | 第 1、2、4、5 名：未知；第 3 名：ReAct01 验收记录 | 全部 5 名均来自 ReAct01/02 真实笔记 | 4 条：ReAct01「最终成果」「第二周计划」+ ReAct02「ToolCallingAdvisor 对答案」「可观测性实战」 | 语义查询向量本就能命中，BM25 负责补全结构/章节标题等字面线索；无陷阱文档混入 |
| 陷阱型「手搓 ReAct 要不要自己实现 maxStep 兜底」 | 第 1~3、5 名：未知；第 4 名：陷阱文档 | 第 1 名：陷阱文档；第 2~5 名：ReAct01/02 真实笔记 | 4 条：ReAct02「ToolCallingAdvisor 对答案」×2 + ReAct01「最终成果」+ ReAct02「对答案」 | 与 ReAct04 结论一致：融合/加权压不住语义相似的陷阱块，最终靠生成侧 prompt 的「来源优先级」兜底；BM25 至少让真实笔记进入候选 |

- 测试口径说明：
  1. 部分返回条目的 `source` 为「未知」，说明 Milvus 集合里仍残留旧索引（建 `source` metadata 之前的数据）；建议 `POST /hybrid/index` 重建集合后再复测，以便每条都有明确来源。
  2. `vectorOnly` 严格取 `topK=5`，但 `hybrid` 走 `retrieve(query,5)` 时向量路内部写死 `topK=10`、RRF 输出未再截断，导致本次 hybrid 返回 6 条。严格对比应让两组检索阶段 topK 一致并截断到 5，否则口径不等价。
- 编译通过（JDK 21）

## 面试弹药

- "怎么量化混合检索的收益？" → A/B 对比端点：同 query 同 topK，纯向量 vs 混合并排，看 B 组独有的块（BM25 净贡献）、Top1 来源差异、陷阱文档排名变化。
- "中文分词对 BM25 有什么影响？" → 单字切让 DF 虚高、IDF 失真，词语义边界全毁；换 jieba 后 term 粒度正确，DF/IDF 才有意义。
- "检索索引怎么做持久化？" → 先质疑前提：源数据可重放 + 重建廉价就不该做；不可重放 + 大规模就直接 ES/Lucene，BM25 是其原生打分器。自研持久化是重新发明更差的 Lucene。
- "jieba 的坑？" → 输出含空格 token 要过滤；OOV 词兜底单字切，可配用户词典；实例加载词典开销大必须单例；线程安全官方未承诺。

## 待改进

- [x] `/hybrid/compare` 三类 query 实测，对比表已补进本文「验收记录」
- [ ] （可选）jieba 用户词典：把"手搓"等项目术语加进去
- [ ] （可选）jieba 线程安全性查证，结论补进本文
- [x] 新章节候选：Agentic RAG——骨架已实现，见 `docs/superpowers/specs/2026-08-18-agentic-rag-design.md` + `agent/`、`tool/`、`memory/`、`eval/` 包，核心 TODO 待补
