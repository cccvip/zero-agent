package com.ai.demo.rag;

import com.ai.demo.splitter.MarkDownWordSplitter;
import com.alibaba.fastjson2.JSON;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 混合检索 Demo：向量召回 + BM25 关键词召回，再用 RRF 融合。
 *
 * 实现步骤（由你手搓核心）：
 * 1. tokenize(String text) —— 中英文分词，越简单越好，能跑通即可。
 * 2. buildBm25Index(List<Document>) / bm25Search(String query, int topK) —— 手写 BM25。
 * 3. rrfFuse(List<Document> vectorResults, List<Document> bm25Results, int k) —— 倒数排序融合。
 * 4. 加一份"陷阱文档"，测试向量检索的盲区。
 */
@RestController
public class HybridRagDemo {

    private final VectorStore vectorStore;

    private final ChatModel deepSeekChatModel;

    // BM25 索引状态（仅内存，服务重启后需重新 /hybrid/index）
    private List<Document> corpus = new ArrayList<>();

    // 每个文档的词频
    private List<Map<String,Integer>> docTermFreqs = new ArrayList<>();

    // 每个词出现在几个文档
    private Map<String,Integer> docFreqs = new HashMap<>();

    // 每个文档长度
    private List<Integer> docLengths =new ArrayList<>();

    // 平均文檔長度
    private double avgdl;

    // 文檔總數
    private int n;

    public HybridRagDemo(VectorStore vectorStore, ChatModel deepSeekChatModel) {
        this.vectorStore = vectorStore;
        this.deepSeekChatModel = deepSeekChatModel;
    }

    private static final PromptTemplate RAG_TEMPLATE = new PromptTemplate("""
        仅根据以下资料回答问题；资料里没有的，明确说不知道，不要编造。
        回答末尾用 引用：[资料N] 标注你用到的资料编号。
        若资料间存在冲突，优先采信来源为 ReAct01.md 和 ReAct02.md 的内容；
        来源为"陷阱文档"的资料仅供参考，不可作为最终结论依据。

        资料：
        {content}

        问题：{query}
        """);

    /**
     * POST /hybrid/index
     * 语料：ReAct01.md + ReAct02.md + 陷阱文档。
     * 向量库索引 + 内存 BM25 索引同时建立。
     */
    @PostMapping("/hybrid/index")
    public String index() throws IOException {
        List<Document> documents = loadDocuments();
        // 1. 向量索引
        vectorStore.add(documents);
        // 2. 关键词索引（BM25）
        this.corpus = documents;
        buildBm25Index(documents);
        return "OK";
    }

    /**
     * GET /hybrid/search?query=...
     * 向量 + BM25 独立召回，RRF 融合后返回 Top-K。
     */
    @GetMapping("/hybrid/search")
    public List<String> search(@RequestParam String query) {
        return retrieve(query, 10).stream()
                .map(Document::getText)
                .limit(5)
                .toList();
    }

    public boolean isIndexed() {
        return !corpus.isEmpty();
    }

    /** 混合检索核心（向量+BM25+RRF），公开给 RetrieveTool 复用为 Agentic RAG 的检索工具 */
    public List<Document> retrieve(String query, int topK) {
        // 向量召回
        SearchRequest vectorRequest = SearchRequest.builder()
                .query(query)
                .topK(10)
                .build();
        List<Document> vectorResults = vectorStore.similaritySearch(vectorRequest);

        // 关键词召回（BM25）+ RRF 融合
        List<Document> bm25Results = bm25Search(query, topK);
        return rrfFuse(vectorResults, bm25Results, 60, 1.0, 2.0);
    }

    /**
     * GET /hybrid/chat?query=...
     * 固定流水线 RAG 问答（Agentic RAG 的 A/B 对照组）。委托给 fixedChat，保持原响应结构不变。
     */
    @GetMapping("/hybrid/chat")
    public String chat(@RequestParam String query) {
        return JSON.toJSONString(fixedChat(query));
    }

    /**
     * 固定流水线 RAG 核心：检索 → 拼接 prompt → DeepSeek 生成答案 + 引用来源。
     * 抽取为公开方法，供 EvalRunner 做 A/B 对照组（agentic vs fixed）。
     */
    public Map<String, Object> fixedChat(String query) {
        if (corpus.isEmpty()) {
            return Map.of("answer", "索引为空，请先 POST /hybrid/index", "sources", List.of());
        }

        List<Document> docs = retrieve(query, 10).stream()
                .limit(5)
                .toList();

        // per-query 空结果兜底：检索器什么都没召回时，不再调用模型，直接说明
        if (docs.isEmpty()) {
            return Map.of("answer", "资料中没有与问题相关的内容，无法回答。", "sources", List.of());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String source = doc.getMetadata().getOrDefault("source", "未知").toString();
            sb.append("[资料").append(i + 1).append("]")
                    .append("[来源：").append(source).append("]\n")
                    .append(doc.getText())
                    .append("\n---\n");
        }

        Prompt prompt = RAG_TEMPLATE.create(Map.of("content", sb.toString(), "query", query));

        ChatResponse chatResponse = deepSeekChatModel.call(prompt);
        String answer = "";
        if (chatResponse != null && chatResponse.getResult() != null) {
            answer = chatResponse.getResult().getOutput().getText();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("sources", docs.stream()
                .map(Document::getText)
                .toList());
        return result;
    }

    /**
     * GET /hybrid/compare?query=...
     * 对比实验：同一 query 下，纯向量检索（A 组）vs 混合检索（B 组）的 Top-K 差异，
     * 用于量化 BM25 的收益。
     *
     * 可比性前提：两组检索阶段 topK 一致（均为 5）。
     * bm25OnlyNewDocs = B 组有而 A 组没有的块（以正文为 key 判同一块，同 rrfFuse 去重口径）。
     */
    @GetMapping("/hybrid/compare")
    public Map<String, Object> compare(@RequestParam String query) {
        if (corpus.isEmpty()) {
            return Map.of("error", "索引为空，请先 POST /hybrid/index");
        }

        //A组 向量召回
        SearchRequest vectorRequest = SearchRequest.builder()
                .query(query)
                .topK(5)
                .build();
        List<Document> aDocuments = vectorStore.similaritySearch(vectorRequest);
        //B组 混合检索
        List<Document> bDocuments = retrieve(query,5);

        Set<String> aSet = new HashSet<>();
        for(Document d:aDocuments){
            aSet.add(d.getText());
        }
        // B 组中正文不在 A 组里的块 = BM25 的净贡献
        List<Document> bm25NewDocs = new ArrayList<>();
        for(Document d:bDocuments){
            if(aSet.contains(d.getText())){
                continue;
            }
            bm25NewDocs.add(d);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("vectorOnly", toEntries(aDocuments));
        result.put("hybrid", toEntries(bDocuments));
        result.put("bm25OnlyNewDocs", toEntries(bm25NewDocs));
        return result;
    }

    /** 把检索结果格式化为 {rank, source, preview} 条目列表，preview 取前 50 字 */
    private List<Map<String, Object>> toEntries(List<Document> docs) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String text = doc.getText();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", i + 1);
            entry.put("source", doc.getMetadata().getOrDefault("source", "未知"));
            entry.put("preview", text.length() > 50 ? text.substring(0, 50) : text);
            entries.add(entry);
        }
        return entries;
    }

    /** 加載語料：兩份筆記 + 陷阱文檔 */
    private List<Document> loadDocuments() throws IOException {
        MarkDownWordSplitter splitter = new MarkDownWordSplitter(2, 800);

        String md1 = Files.readString(Path.of("ReAct01.md"));
        String md2 = Files.readString(Path.of("ReAct02.md"));
        // 陷阱文档：故意和 ReAct01.md 的某个结论相反
        String trap = """
                # 陷阱文档
                ## 关于 maxStep 的错误认知
                有人认为手搓 ReAct 循环必须自己实现 maxStep 兜底，
                但实际上 Spring AI 2.0 的 ToolCallingAdvisor 内部已经自带 maxStep 限制，
                因此手写循环根本不需要考虑死循环兜底。
                """;

        List<Document> documents = new ArrayList<>();

        List<Document> md1Docs = splitter.split(new Document(md1));
        md1Docs.forEach(d -> d.getMetadata().put("source", "ReAct01.md"));
        documents.addAll(md1Docs);

        List<Document> md2Docs = splitter.split(new Document(md2));
        md2Docs.forEach(d -> d.getMetadata().put("source", "ReAct02.md"));
        documents.addAll(md2Docs);

        List<Document> trapDocs = splitter.split(new Document(trap));
        trapDocs.forEach(d -> d.getMetadata().put("source", "陷阱文档"));
        documents.addAll(trapDocs);

        return documents;
    }

    // jieba 分词器：构造时加载词典，开销大，全局单例（Demo 阶段够用；官方未承诺线程安全，并发场景需另行处理）
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    /**
     * jieba 分词（SEARCH 模式）：中文按词切（"死循环"是一个 term），英文/数字保持完整词。
     * 替代旧的"中文单字切 + 英文 a-z0-9 聚合"——单字切会让 DF 虚高、IDF 失真。
     */
    private List<String> tokenize(String text) {
        return SEGMENTER.process(text.toLowerCase(Locale.ROOT), JiebaSegmenter.SegMode.SEARCH)
                .stream()
                .map(token -> token.word)
                .filter(word -> !word.isBlank())
                .toList();
    }

    private void buildBm25Index(List<Document> documents) {
        for(Document document:documents){
            String text = document.getText();
            Map<String,Integer> map = new HashMap<>();
            List<String> stringList  = tokenize(text);
            for(String tokenizWord:stringList){
                map.merge(tokenizWord, 1, Integer::sum);
            }
            for (String token : map.keySet()) {
                docFreqs.merge(token, 1, Integer::sum);
            }
            docLengths.add(stringList.size());
            docTermFreqs.add(map);
        }
        double avg = docLengths.stream()
                .mapToLong(Integer::longValue)
                .average()
                .orElse(0.0);  // 空列表时返回默认值
        avgdl = avg;
        n = documents.size();
        corpus = documents;
    }

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private List<Document> bm25Search(String query, int topK) {
        // 1. 對 query 分詞
        List<String> queryTerms = tokenize(query);

        // 2. 記錄每個文檔的總分：key = 文檔索引，value = BM25 分數
        Map<Integer, Double> scores = new HashMap<>();

        // 3. 對 query 裡的每個詞，給所有文檔打分
        for (String term : queryTerms) {
            Integer df = docFreqs.get(term);

            // 語料庫裡沒有的詞，直接跳過
            if (df == null || df == 0) {
                continue;
            }

            // BM25+ 的 IDF 公式
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));

            // 掃描每個文檔
            for (int i = 0; i < n; i++) {
                int tf = docTermFreqs.get(i).getOrDefault(term, 0);
                int len = docLengths.get(i);

                // BM25 的 TF 部分
                double numerator = tf * (K1 + 1);
                double denominator = tf + K1 * (1 - B + B * len / avgdl);
                double score = idf * numerator / denominator;

                // 加到這個文檔的總分
                scores.merge(i, score, Double::sum);
            }
        }

        // 4. 按分數降序排序，取 topK
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> corpus.get(entry.getKey()))
                .toList();
    }

    private List<Document> rrfFuse(List<Document> vectorResults, List<Document> bm25Results, int k,
                                   double vectorWeight, double bm25Weight) {
        Map<String, Double> rrfScores = new HashMap<>();
        // 記住每個內容對應的 Document
        Map<String, Document> docByText = new HashMap<>();

        // 處理向量召回結果
        for (int i = 0; i < vectorResults.size(); i++) {
            Document doc = vectorResults.get(i);
            String text = doc.getText();
            int rank = i + 1; // 第 1 名 rank = 1

            rrfScores.merge(text, vectorWeight / (k + rank), Double::sum);
            docByText.put(text, doc);
        }

        // 處理 BM25 召回結果
        for (int i = 0; i < bm25Results.size(); i++) {
            Document doc = bm25Results.get(i);
            String text = doc.getText();
            int rank = i + 1;

            rrfScores.merge(text, bm25Weight / (k + rank), Double::sum);
            docByText.put(text, doc);
        }

        // 按 RRF 總分降序排序，返回 Document 列表
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> docByText.get(entry.getKey()))
                .toList();
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, Charset.defaultCharset()));
        HybridRagDemo hybridRagDemo = new HybridRagDemo(null, null);
        System.out.println(JSON.toJSONString(hybridRagDemo.tokenize("手搓 ReAct 循环 maxStep 123")));

        JiebaSegmenter segmenter = new JiebaSegmenter();
        List<SegToken> segTokens = segmenter.process("手搓 ReAct 循环 maxStep 123", JiebaSegmenter.SegMode.INDEX );
        System.out.println(JSON.toJSONString(segTokens));
    }

}
