# 混合检索实战（第三周）

> 目标：在 RAG 最小闭环基础上，加入 BM25 关键词召回，并用 RRF 与向量召回融合，验证是否能抵抗「语义相似但结论相反」的陷阱文档。
>
> 环境：Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 21 + Ollama(bge-m3, 1024 维) + Milvus

## 最终成果

`src/main/java/com/ai/demo/rag/HybridRagDemo.java`：手写混合检索闭环，包含：

- `tokenize()` 中英文极简分词（`HybridRagDemo.java:122-146`）
- `buildBm25Index()` 建立 BM25 索引（`HybridRagDemo.java:149-170`）
- `bm25Search()` 手写 BM25 打分（`HybridRagDemo.java:176-216`）
- `rrfFuse()` 倒数排序融合（`HybridRagDemo.java:219-249`）
- 一份故意写反的「陷阱文档」（`HybridRagDemo.java:106-112`）

调用链路：

```
POST /hybrid/index   → 向量库索引 + 内存 BM25 索引
GET  /hybrid/search?query=... → 向量召回 + BM25 召回 → RRF 融合 → Top-K
```

## 核心认知

### 1. 为什么要做混合检索？

向量检索只管语义相似，不管结论对错。陷阱文档如果和真相文档讨论的是同一个主题，向量很容易把它也排上来。

BM25 看的是精确关键词匹配。真相文档里同时命中了 `maxStep` 和 `实现` 等稀有词，陷阱文档只命中了 `maxStep`，所以 BM25 能把真相顶上去。

> 一句话：向量负责「找相关」，BM25 负责「纠偏」，RRF 负责「不打分直接融合排名」。

### 2. BM25 的三要素

| 要素 | 含义 | 代码对应 |
|---|---|---|
| TF（词频） | 词在当前文档出现几次 | `docTermFreqs.get(i).getOrDefault(term, 0)` |
| DF/IDF | 词在多少文档里出现 | `docFreqs.get(term)` → `Math.log(1 + (n - df + 0.5) / (df + 0.5))` |
| 文档长度归一化 | 长文档的 TF 要打折 | `len / avgdl` |

`docTermFreqs` 是「文档视角」：每个文档里每个词出现几次。  
`docFreqs` 是「语料视角」：每个词出现在几个文档里。两者不要混。

### 3. RRF 不需要校准分数

向量召回和 BM25 召回的分数量纲不同，不能直接相加。RRF 的做法是：

```
rrf_score(doc) = 1/(k + rank_vector) + 1/(k + rank_bm25)
```

只要排名，不要绝对分数。`k=60` 是常用常数，排名靠后的文档衰减更慢，给两种召回都出现但排名不高的文档一个反超机会。

### 4. 手写 vs 框架

这次没有直接调用 Spring AI 的混合检索组件，而是自己手写了 BM25 + RRF。好处是：

- 完全可控融合逻辑。
- 能直观感受 IDF、TF、长度归一化各自的作用。
- 面试时可以讲清楚为什么 RRF 比直接加权求和更稳。

代价是：

- 中文分词很糙（单字切），工业环境要换 jieba / HanLP / Elasticsearch。
- BM25 索引存在内存里，服务重启要重建。

## 错误清单

| # | 错误 | 根因 | 教训 |
|---|------|------|------|
| 1 | 用 `getBytes()` 迭代 byte 做分词 | 把中文字拆成了多个 byte，且 `UnicodeBlock.of(byte)` 会抛异常或得到错误结果 | 分词必须对 `char` 或 `codePoint` 操作，不能对 byte |
| 2 | `docFreqs` 在每次词出现时都 `+1` | 混淆了 TF 和 DF | `docFreqs` 只关心「文档是否包含」，一个文档里出现 100 次也只 +1 |
| 3 | `docLengths.add(text.length())` | 把字符数当成文档长度 | BM25 的文档长度是 token 数量，要用 `tokenize(text).size()` |
| 4 | 用 `Collections.emptyList()` / `emptyMap()` 做初始值 | 不可变集合，后续 `add`/`merge` 直接抛异常 | 需要可变集合时直接用 `new ArrayList<>()` / `new HashMap<>()` |
| 5 | 大写字母没被切进 token | 只判断了小写 `a-z`，但 `toLowerCase()` 后已全小写 | 逻辑上这次没触发问题，但要注意边界 |

## 验收记录

Query：

```
GET /hybrid/search?query=ReAct 循环的 maxStep 在哪里实现
```

返回 Top-5：

1. `ReAct02.md` —— 框架没有 maxStep，手搓版更防御 ✅
2. `ReAct02.md` —— 循环结构对照 ✅
3. `ReAct01.md` —— 手搓 ReAct 最终成果 ✅
4. `ReAct01.md` —— 第二周读源码计划 ✅
5. **陷阱文档** —— 说「框架自带 maxStep，不需要手写」❌

结论：陷阱文档被压到了第 5 名，没有干扰前 4 条。混合检索成功抵抗了语义陷阱。

## 面试弹药

- "RAG 里纯向量检索有什么风险？" → 语义相似但结论相反的文档会被召回上来。
- "怎么解决？" → 加入 BM25 关键词召回做补充，再用 RRF 融合，不需要校准两种分数的量纲。
- "BM25 和 TF-IDF 区别？" → BM25 对 TF 做饱和处理，并加入文档长度归一化。
- "RRF 的 k 取多少？" → 常用 60，越小对排名靠前的文档越敏感。

## 待改进

- [ ] 中文分词从单字切升级到更合理的分词器。
- [ ] BM25 索引持久化，避免服务重启重建。
- [ ] 对比纯向量检索和混合检索的 TOP-K 差异，量化 BM25 的收益。
- [ ] 把混合检索接入 `/chat`，做真正的 RAG 问答，而不只是返回文本块。
