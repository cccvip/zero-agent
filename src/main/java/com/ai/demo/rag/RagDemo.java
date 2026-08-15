package com.ai.demo.rag;

import com.ai.demo.splitter.MarkDownWordSplitter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 最小闭环 Demo。
 * 流程：ReAct01.md / ReAct02.md → MarkDownWordSplitter 切块 → Milvus 索引 → 向量召回。
 *
 * 注意：当前 /rag/index 每次调用都会向 Milvus 追加新文档；生产环境需配合去重/删除旧数据。
 */
@RestController
public class RagDemo {

    private final VectorStore vectorStore;

    public RagDemo(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * POST /rag/index
     * 读取本地 Markdown，切块后写入 Milvus。
     */
    @PostMapping("/rag/index")
    public String index() throws IOException {
        List<Document> documents = loadDocuments();
        vectorStore.add(documents);
        return "indexed " + documents.size() + " chunks";
    }

    /**
     * GET /rag/search?query=...
     * 向量召回 Top-K 文本块。
     */
    @GetMapping("/rag/search")
    public List<String> search(@RequestParam String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(3)
                .build();

        return vectorStore.similaritySearch(request).stream()
                .map(Document::getText)
                .toList();
    }

    /** 加载本地 md 并用你的 MarkDownWordSplitter 切块 */
    private List<Document> loadDocuments() throws IOException {
        MarkDownWordSplitter splitter = new MarkDownWordSplitter(2, 800);
        String md1 = Files.readString(Path.of("ReAct01.md"));
        String md2 = Files.readString(Path.of("ReAct02.md"));

        // splitText 是 protected，外部走 TextSplitter 的 public API：split(Document)
        List<Document> documents = new ArrayList<>();
        documents.addAll(splitter.split(new Document(md1)));
        documents.addAll(splitter.split(new Document(md2)));
        return documents;
    }
}
