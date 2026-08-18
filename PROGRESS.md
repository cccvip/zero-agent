# 学习进度交接（持续更新）

> 用途：跨会话续接。新会话开场说"读 PROGRESS.md 继续"即可。
> 最后更新：2026-08-18 - 第五周：/hybrid/compare 端点（测试待补）+ jieba 分词落地 + 持久化需求移除，ReAct05.md 已写

## 当前状态

**已完成（第四周）**：
1. `/hybrid/chat` RAG 问答闭环：混合检索 → `PromptTemplate` 拼接（`[资料N]` 编号 + `\n---\n` 分隔 + 引用标注指令）→ DeepSeek 生成；空索引早退兜底。**端到端自测已完成**。陷阱文档压制方案落地：给资料打 `source` metadata + prompt 显式优先级，模型能明确区分错误资料。验收口径见 ReAct04.md「验收记录」。
2. 官方源码对照：1.x `QuestionAnswerAdvisor`（`target/sai-1x/`）+ 2.0 `RetrievalAugmentationAdvisor` 七步管线（`target/sai-src/org/springframework/ai/rag/`）。结论全部进 `ReAct04.md`（含行号）。

**已完成（第五周）**：
1. `/hybrid/compare` 对比端点：A 组纯向量 vs B 组混合（topK=5 一致），`bm25OnlyNewDocs` 标出 BM25 净贡献。**测试待补**（三类 query 口径见 ReAct05.md）。
2. 中文分词升级：`tokenize()` 换 jieba（SEARCH 模式，全局单例），签名不变调用方零改动，已实测。
3. "BM25 索引持久化"需求**论证后移除**：源文件可重放 + 重建秒级 → 持久化是负收益；真正的场景答案是 ES/Lucene。判断过程进 ReAct05.md。

**commit 情况**：已全部 push（2026-08-18，`main` 与 `origin/main` 同步）。
**未跟踪**：`src/main/java/com/ai/demo/rag/RagDemo2.java`——已不存在（此前已删除）。
**遗留小项**：已完成——清理 HybridRagDemo 过期 TODO、删除多余 CollectionUtils guard、补充 per-query 空结果兜底、`/hybrid/chat` 返回 `answer + sources` JSON。

## 本次会话历程（第四周）

1. **卡点**：知道 RAG 问答要"检索→拼 prompt→调模型"，但不知道 `{content}` 占位符怎么填——`new UserMessage("{content}...")` 的花括号只是普通字符，替换只发生在 `PromptTemplate.render(Map)`。
2. **三轮 review** 修掉：资料块无分隔符、chat 漏 limit、"返回JSON"无 schema、编号拼了但指令没让模型标注引用、空语料 guard 放错层（导致 /hybrid/search 静默降级）。
3. **读 1.x**：结论"简单粗暴"——全量 join 无长度防护、无编号、空结果不兜底；但把检索文档塞进 context/response metadata 做可观测。
4. **读 2.0**：疑问点在 Step 1/2（QueryTransformer 改写 1→1 / QueryExpander 扩展 1→N）——都是用 LLM 解"词面鸿沟"，与 BM25 是同一问题的两种解法，默认全关（质量 vs 成本交换）。发现：官方默认 joiner 按 DocID 去重 + 原始 score 排序（跨源量纲不同，不如 RRF）；`DocumentPostProcessor` 是纯接口无内置实现（"超长上下文"的官方答案=留钩子自己实现）；空上下文时 `ContextualQueryAugmenter` 把 query 整个替换为"礼貌拒绝"指令（`:132`，常量经字段间接使用所以搜不到渲染点）。

## 本次会话历程（第五周）

1. **/hybrid/compare**：用户写 A/B 检索 + 判同块（正文做 key，答对）；review 抓掉一段死代码（for 循环复制列表 + `i>10` off-by-one + 复制结果没人用）；组装与兜底由用户要求代写完成。
2. **jieba 升级**：三个设计决定（单例 / 英文完整词 / 签名不动）用户自己答对后要求代写；实测发现 jieba 输出含空格 token（需过滤）、"手搓" OOV 兜底单字切。坑：jieba 版本 1.0.4 不存在，中央仓库最新 1.0.2，失败缓存要 `-U`。
3. **持久化思辨**：用户质疑"为什么要做持久化，几百 M 文件怎么存"——命中需求软肋。结论：源文件可重放 + 重建秒级 → 不做；语料不可重放 + 大规模 → 直接 ES/Lucene，不自研。待办移除。

## 本次会话进展（续接，第四周末段）

- 外部环境已恢复（`192.168.100.118:19530` / `:11434` 可连通）。
- 完成 `HybridRagDemo` 清理：
  - 删除过期 TODO 注释与多余 `CollectionUtils` guard；
  - 将检索逻辑抽为 `retrieve(query, topK)`，`/hybrid/search` 与 `/hybrid/chat` 共用；
  - 增加 per-query 空结果兜底；
  - `/hybrid/chat` 改为返回 JSON，包含 `answer`（模型回答）和 `sources`（引用文档原文），便于核对模型是否被陷阱文档带偏。
- API TEST 工具超时调整为 5 分钟，并重新编译打包 `api-tester.exe` 和 `api-tester-wails.exe`。
- 完成 `/hybrid/chat` 端到端自测：模型答案站真实笔记，正确区分陷阱文档。
- 陷阱文档压制实验记录进 `ReAct04.md`：
  - 加权 RRF（BM25 weight 1.5 → 2.0）无法把陷阱文档拉下 Top1；
  - 最终采用来源标签 + prompt 优先级，生成侧明确压制错误资料。
- 编译通过（`mvn compile`，JDK 21.0.12）。

## 下一步

- [ ] **代码已完成，测试待补**：`/hybrid/compare` 端点（A 组纯向量 vs B 组混合，topK=5 一致；`bm25OnlyNewDocs` 正文判同块标出 BM25 净贡献）。测试口径：三类 query（关键词型 `maxStep` / 语义型"怎么防止工具调用死循环" / 陷阱型），产出对比表进笔记
- [x] **（环境已恢复）** `/hybrid/chat` 自测：`POST /hybrid/index` → `GET /hybrid/chat?query=手搓ReAct要不要自己实现maxStep兜底`，答案站笔记结论，正确区分陷阱文档，带 `引用：[资料N]`
- [x] 手搓版补 per-query 空结果兜底 + 响应带引用文档（对齐 2.0，见 ReAct04 待改进）
- [x] 陷阱文档压制：来源标签 + prompt 优先级
- [x] 中文分词升级：jieba（SEARCH 模式）替换单字切，`tokenize()` 签名不变，已实测
- [x] ~~BM25 索引持久化~~ **移除了**：源文件（md）本就在磁盘可重放，重建秒级，持久化计算产物是负收益；语料不可重放 + 大规模的场景应直接上 ES/Lucene（BM25 是其原生打分器），不自研
- [x] 完成 push（2026-08-18 已推送，README.md 随之确认上去）
- [x] 删除或处理 RagDemo2.java 草稿（文件已不存在）

## 项目现状

- 仓库：https://github.com/cccvip/zero-agent （main 分支）
- 技术栈：Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 21 + DeepSeek（实际模型 deepseek-v4-flash）
- 端口：8090；actuator + prometheus 已接入（需 `management.endpoints.web.exposure.include` 显式暴露）

### 代码结构

```
src/main/java/com/ai/demo/
├── DemoApplication.java
├── ChatController.java          # /chat 端点 → ReActAgent
├── react/ReActAgent.java        # 手搓 ReAct 循环（ChatModel 层，~100行）
├── react/Context.java           # 空壳，未用
├── tool/TimeTool.java           # getCurrentTime / addTime / sum(故意1/0抛异常)
├── splitter/MarkDownWordSplitter.java  # md 结构切分器（三层策略，含 main 测试入口）
└── rag/
    ├── RagDemo.java             # RAG 最小闭环：向量索引 + 向量召回
    ├── HybridRagDemo.java       # 混合检索 + /hybrid/chat RAG 问答 + /hybrid/compare 对比端点（jieba 分词）
```

### 文档

- `ReAct01.md`：第一周总结（核心认知 5 条 + 错误清单 10 条 + 验收记录 + SAA 兼容性结论）
- `ReAct02.md`：第二周源码对照（ToolCallingAdvisor / DefaultToolCallingManager / Advisor 链 / 可观测性实测）
- `ReAct03.md`：第三周混合检索实战（BM25 + RRF + 陷阱文档 + 错误清单 + 面试弹药）
- `ReAct04.md`：第四周 RAG 问答 + 官方两代实现源码对照（PromptTemplate 机制 / 1.x 无长度防护 / 2.0 七步管线 / RRF vs 官方 joiner / 空结果三策略）
- `ReAct05.md`：第五周检索质量进阶（/hybrid/compare 对比端点 / jieba 分词 / "持久化为什么不该做"的架构判断）
- `README.md`：面试导向的学习档案（**最后一次 push 时被取消，可能未推送，下次先确认 `git status`**）

## 关键环境备忘（重要！）

- **Maven 本地仓库在 `F:\maven\repository`**（settings.xml 自定义，非默认 ~/.m2）
- **命令行 mvn 默认 JDK 17，项目要求 21**：必须 `JAVA_HOME='C:\Program Files\Java\jdk-21' mvn ...`
- **中文乱码**：运行加 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`；IDEA 里配 Run Configuration 的 VM options
- **源码解压位置**：`target/sai-src/`（2.0.0：client-chat、model 包 PromptTemplate、rag 全包 28 文件）、`target/sai-1x/`（1.1.5 QuestionAnswerAdvisor）、`target/sai-commons/`
- **RAG 相关类不在主依赖里**：`QuestionAnswerAdvisor` 在独立模块 `spring-ai-advisors-vector-store`；2.0 的 RAG 管线在 `spring-ai-rag` 模块。读源码不用引依赖；要用才加 `org.springframework.ai:spring-ai-rag`（BOM 管版本）
- **⚠️ application.yml 里又有明文 DeepSeek key**（sk-1e17...）。此前已建议吊销重建，仍未处理
- 外部依赖：Ollama(bge-m3) + Milvus 在 192.168.100.118（:11434 / :19530），索引 25 块约需 2 分钟+
- **jieba-analysis 用 1.0.2**（1.0.4 不存在，中央仓库最新即 1.0.2；版本解析失败会被本地仓库缓存，需 `mvn -U` 强刷）

## 已建立的核心结论（不要重复推导）

1. Spring AI 2.0 分层：ChatClient → Advisor 链（默认只有 ToolCallingAdvisor）→ ChatModel（厂商绑定，options 硬强转）
2. `ToolCallAdvisor` 在 2.0 已废弃，本体是 `ToolCallingAdvisor`；工具循环 do-while（:139-185）**无 maxStep**，终止靠 `ToolExecutionEligibilityChecker`
3. `internalToolExecutionEnabled` 开关已不存在——绕过 ChatClient 直调 ChatModel 天然全手动
4. 手搓 ReAct 比框架强：maxStep 兜底、幻觉工具名回喂；缺：可观测性、空参/null 兜底、returnDirect、ToolContext
5. 实测：一次"现在几点"= 2 次模型调用，token 1453/202（全量重发成本证据）
6. SAA 1.0.0.x 绑定 Spring AI 1.0 + Boot 3.4.x，与本项目不兼容 → 走读源码路线
7. **PromptTemplate**：`{placeholder}` 替换只发生在 `render(Map)`，UserMessage/Prompt 不做替换；render 只处理模板串，变量值不二次解析（检索原文可直接塞）
8. **RAG 上下文长度**：1.x QuestionAnswerAdvisor 全量 join 零防护，超窗口=API 报错；工程上靠切块大小 × topK 掐预算；2.0 留 `DocumentPostProcessor` 钩子但无内置实现
9. **多源融合**：官方默认 joiner 按 DocID 去重 + 原始 score 排序，跨源量纲不同有问题；RRF 只用排名，异构源更稳
10. **query 改写/扩展 vs BM25**：同解"词面鸿沟"，一个在 query 层进攻（LLM），一个在检索器层兜底；增强默认全关，质量 vs 延迟/成本的交换
11. **分词粒度决定 BM25 上限**：单字切 DF 虚高、IDF 失真；jieba 中文按词、英文完整词。坑：输出含空格 token 要过滤；OOV 词兜底单字切（可配用户词典）；实例加载词典开销大必须单例；线程安全官方未承诺
12. **持久化计算产物前先问**：源数据能否重放？重建成本多少？可重放 + 廉价 → 不做；不可重放 + 大规模 → 用现成方案（BM25 持久化 = ES/Lucene），不自研

## 切块器设计要点（MarkDownWordSplitter）

- 三层策略：标题层级切（cutLevel 默认 2）→ 超限按空行**贪心合并**（不是拆散！）→ 单段仍超限组合委托 TokenTextSplitter
- 标题栈存 record `(level, title)`，不变式：栈 = 当前块的完整祖先链；规则"弹出所有 ≥n，压入"
- 每块开头写【章 > 节】路径前缀，且**子块继承前缀**
- 切块层级是参数不是常量；验收标准：块大小 300~800 字
- 踩过的坑：split() 吃分隔符且按正则切 / 数全行 # 而非行首 / fencepost / 空块入列 / 弹栈当内容拼接

## 学习规则（用户要求，必须遵守）

- 核心机制手搓，工程噪音（pom、docker、样板代码）可由 AI 写
- AI 可以给提示和 review，但不直接替写核心代码（切块器是例外：用户多轮受挫后明确要求代写，已完成）
- 判断标准：代码能否在纸上复现
- 每轮 review 前先跑代码，以运行结果为准
- 每个源码结论标行号；笔记按周编号（ReAct01/02/03...）
- git 操作（commit/push）必须先问用户确认
