package com.ai.demo.rag;

import com.ai.demo.splitter.MarkDownWordSplitter;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
    // TODO：补充你需要的索引结构，例如 docTermFreqs / docFreqs / docLengths / avgdl
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
        List<Document> fused = search(query,10);
        return fused.stream()
                .map(Document::getText)
                .limit(5)
                .toList();
    }

    private List<Document> search(String query,int topK){
        // 向量召回
        SearchRequest vectorRequest = SearchRequest.builder()
                .query(query)
                .topK(10)
                .build();
        List<Document> vectorResults = vectorStore.similaritySearch(vectorRequest);

        if(CollectionUtils.isEmpty(corpus)){
            return vectorResults;
        }

        // 关键词召回
        // TODO：调用你写的 bm25Search(query, 10)
        List<Document> bm25Results = bm25Search(query,topK);

        // RRF 融合
        // TODO：调用你写的 rrfFuse(vectorResults, bm25Results, 60)
        List<Document> fused = rrfFuse(vectorResults, bm25Results, 60);
        return fused;
    }

    /**
     * GET /hybrid/chat?query=...
     * 真正的 RAG 问答：检索 → 拼接 prompt → 调 DeepSeek 生成答案。
     *
     * TODO Step 1：复用 /hybrid/search 的检索逻辑拿到 Top-K 文档。
     *   （建议先把 search() 里"向量 + BM25 + RRF"那一段抽成 private List<Document> retrieve(String query)，
     *     让 search() 和 chat() 共用，避免复制粘贴。）
     * TODO Step 2：拼接 prompt。把检索到的文档内容拼进"资料"区，附上约束指令：
     *   "仅根据以下资料回答问题；资料里没有的，明确说不知道，不要编造。"
     * TODO Step 3：deepSeekChatModel.call(new Prompt(...)) 生成答案。
     * TODO Step 4：返回 答案 + 引用来源（哪些块被用上了），方便核对模型有没有被陷阱文档带偏。
     * 思考：如果 corpus 为空（没调过 /hybrid/index）会怎样？要不要兜底？
     */
    @GetMapping("/hybrid/chat")
    public String chat(@RequestParam String query) {
        if (corpus.isEmpty()) {
            return "索引为空，请先 POST /hybrid/index";
        }
        // TODO
        List<Document> searchDocument = search(query,10);
        List<String> documents  = searchDocument.stream()
                .map(Document::getText)
                .limit(5)
                .toList();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            sb.append("[资料").append(i + 1).append("]\n")
                    .append(documents.get(i))                 // documents 保持 List<Document>，不用先 map 成 String
                    .append("\n---\n");
        }

        Prompt prompt = RAG_TEMPLATE.create(Map.of("content", sb.toString(), "query", query));

        ChatResponse chatResponse = deepSeekChatModel.call(prompt);
        if(chatResponse == null) {
            return "";
        }

        AssistantMessage assistantMessage =  chatResponse.getResult().getOutput();

        return assistantMessage.getText();
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
        documents.addAll(splitter.split(new Document(md1)));
        documents.addAll(splitter.split(new Document(md2)));
        documents.addAll(splitter.split(new Document(trap)));
        return documents;
    }

    // TODO Step 1：分词。中文可以简单按字符切，英文按非单词字符切。
    private List<String> tokenize(String text) {
        String tokenText = text.toLowerCase(Locale.ROOT);
        List<String> list  = new ArrayList<>();
        char[] tokenByte =  tokenText.toCharArray();

        StringBuilder stringBuilder = new StringBuilder();
        for(char c:tokenByte){
            if( (c >= 'a' && c<= 'z') || (c >='0' && c<='9')){
                stringBuilder.append(c);
            }else {
                if (!stringBuilder.isEmpty()){
                    String result = stringBuilder.toString().trim();
                    list.add(result);
                    stringBuilder.setLength(0);
                }
                if(Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS){
                    list.add(c+"");
                }
            }
        }
        if (!stringBuilder.isEmpty()) {
            list.add(stringBuilder.toString());
        }
        return list;
    }

    // TODO Step 2：根据 corpus 建立 BM25 索引（docTermFreqs / docFreqs / docLengths / avgdl）。
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

    // TODO Step 2：对 query 做 BM25 打分，返回按分数降序排列的 Top-K 文档。
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

    // TODO Step 3：RRF 融合。按排名给分：score = Σ 1/(k + rank)，去重后降序。
    private List<Document> rrfFuse(List<Document> vectorResults, List<Document> bm25Results, int k) {
        Map<String, Double> rrfScores = new HashMap<>();
        // 記住每個內容對應的 Document
        Map<String, Document> docByText = new HashMap<>();

        // 處理向量召回結果
        for (int i = 0; i < vectorResults.size(); i++) {
            Document doc = vectorResults.get(i);
            String text = doc.getText();
            int rank = i + 1; // 第 1 名 rank = 1

            rrfScores.merge(text, 1.0 / (k + rank), Double::sum);
            docByText.put(text, doc);
        }

        // 處理 BM25 召回結果
        for (int i = 0; i < bm25Results.size(); i++) {
            Document doc = bm25Results.get(i);
            String text = doc.getText();
            int rank = i + 1;

            rrfScores.merge(text, 1.0 / (k + rank), Double::sum);
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
    }

}
