# 学习进度交接（持续更新）

> 用途：跨会话续接。新会话开场说"读 PROGRESS.md 继续"即可。
> 最后更新：第三周 - /hybrid/chat RAG 问答完成（待自测），进入检索对比实验

## 当前状态

**已完成**：第三周混合检索章节 + RAG 问答闭环。`/hybrid/chat`（检索 → PromptTemplate 拼接 → DeepSeek 生成）已实现，含引用编号标注指令、`[资料N]` 标签、空索引兜底。编译通过，**端到端自测待做**（当时环境依赖有问题）。
**遗留**：少量过期 TODO 注释清理（`:45`、`:118`、`:122`、`:145`、`:155` 矛盾注释）；`search(query,topK)` 里多余的 CollectionUtils 空语料 guard 待删。
**当前状态**：本地有两个 commit 待 push（`74d3eae`、`add68ea`），push 因 GitHub HTTPS 认证需用户在本地终端/IDEA 完成。

## 下一步

- [ ] **进行中**：对比纯向量检索 vs 混合检索的 Top-K 差异，量化 BM25 收益
- [ ] （待自测）`/hybrid/chat` 验收：`POST /hybrid/index` → `GET /hybrid/chat?query=手搓ReAct要不要自己实现maxStep兜底`，看答案站笔记结论还是陷阱结论、有无标注 `引用：[资料N]`
- [ ] 中文分词升级（当前是单字切，可试 jieba / HanLP）
- [ ] BM25 索引持久化，避免服务重启重建
- [ ] 确认 README.md 是否已推送（上次 push 被取消过）
- [ ] 完成 push（在本地终端/IDEA 执行 `git push`）

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
    └── HybridRagDemo.java       # 混合检索：向量 + BM25 + RRF + 陷阱文档
```

### 文档

- `ReAct01.md`：第一周总结（核心认知 5 条 + 错误清单 10 条 + 验收记录 + SAA 兼容性结论）
- `ReAct02.md`：第二周源码对照笔记（ToolCallingAdvisor / DefaultToolCallingManager / Advisor 链 / 可观测性实测）
- `ReAct03.md`：第三周混合检索实战（BM25 + RRF + 陷阱文档 + 错误清单 + 面试弹药）
- `README.md`：面试导向的学习档案（已写好；**最后一次 push 时被取消，可能未推送，下次先确认 `git status`**）

## 关键环境备忘（重要！）

- **Maven 本地仓库在 `F:\maven\repository`**（settings.xml 自定义，非默认 ~/.m2）
- **命令行 mvn 默认 JDK 17，项目要求 21**：必须 `JAVA_HOME='C:\Program Files\Java\jdk-21' mvn ...`
- **中文乱码**：JEP 400（JDK 18+ UTF-8 默认） vs Windows GBK 控制台。运行加 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`；IDEA 里配 Run Configuration 的 VM options
- sources jar 已下载，关键类解压位置：`target/sai-src/`（client-chat、model 包）、`target/sai-commons/`（部分）

## 已建立的核心结论（不要重复推导）

1. Spring AI 2.0 分层：ChatClient → Advisor 链（默认只有 ToolCallingAdvisor）→ ChatModel（厂商绑定，options 硬强转，如 DeepSeekChatModel 要 DeepSeekChatOptions）
2. `ToolCallAdvisor` 在 2.0 已废弃，本体是 `ToolCallingAdvisor`；工具循环是 do-while（:139-185），**无 maxStep**，终止靠可插拔 `ToolExecutionEligibilityChecker`
3. `internalToolExecutionEnabled` 开关已不存在——绕过 ChatClient 直调 ChatModel 天然全手动
4. 手搓版比框架强的两处：maxStep 兜底、幻觉工具名回喂（框架直接抛 IllegalStateException）
5. 手搓版缺的能力：可观测性（绕过 DefaultToolCallingManager 导致工具指标不采集）、空参数/null 结果兜底、returnDirect、ToolContext
6. 实测证据：一次"现在几点"= 2 次模型调用，input/output token = 1453/202（全量重发的成本证据）
7. SAA（spring-ai-alibaba）1.0.0.x 绑定 Spring AI 1.0 + Boot 3.4.x，与当前项目（AI 2.0 + Boot 4.1）不兼容 → 走读源码路线

## 切块器设计要点（MarkDownWordSplitter）

- 三层策略：标题层级切（cutLevel 默认 2）→ 超限按空行**贪心合并**（不是拆散！）→ 单段仍超限组合委托 TokenTextSplitter
- 标题栈存 record `(level, title)`，不变式：栈 = 当前块的完整祖先链；规则"弹出所有 ≥n，压入"
- 每块开头写【章 > 节】路径前缀，且**子块继承前缀**（第二三层不能丢上下文）
- 切块层级是参数不是常量；验收标准：块大小 300~800 字
- 踩过的坑：split() 吃分隔符且按正则切（层级信息全毁）/ 数全行 # 而非行首 / fencepost（最后一块漏结算）/ 空块入列 / 弹栈当内容拼接

## 学习规则（用户要求，必须遵守）

- 核心机制手搓，工程噪音（pom、docker、样板代码）可由 AI 写
- AI 可以给提示和 review，但不直接替写核心代码（切块器是例外：用户多轮受挫后明确要求代写，已完成）
- 判断标准：代码能否在纸上复现
- 每轮 review 前先跑代码，以运行结果为准
- 每个源码结论标行号；笔记按周编号（ReAct01/02/03...）
- git 操作（commit/push）必须先问用户确认

## 待办清单

- [ ] 确认 README.md 是否已推送（上次 push 被取消过）
- [x] 确认 Ollama + bge-m3 就绪
- [x] pom 加入 milvus/ollama 两个 starter
- [x] 手写 RAG 索引 + 检索最小闭环（`RagDemo` 两个 TODO）
- [x] 混合检索（向量 + BM25 + RRF）
- [x] 切块器实战经历补进 ReAct03.md
- [x] （建议）DeepSeek 真实 api-key 已替换成占位符 111，但旧 key 曾明文出现过，建议吊销重建
