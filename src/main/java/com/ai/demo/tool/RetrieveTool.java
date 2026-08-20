package com.ai.demo.tool;

import com.ai.demo.agent.AgentTool;
import com.ai.demo.rag.HybridRagDemo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agentic RAG 核心：把知识库检索包装成 ReAct 工具。
 * 模型自主决定"这个问题要不要查知识库、查什么"——这是与固定流水线 /hybrid/chat 的本质区别。
 */
@Component
public class RetrieveTool implements AgentTool {

    private final HybridRagDemo hybridRagDemo;

    public RetrieveTool(HybridRagDemo hybridRagDemo) {
        this.hybridRagDemo = hybridRagDemo;
    }

    @Override
    public List<ToolCallback> callbacks() {
        return List.of(ToolCallbacks.from(this));
    }

    @Tool(description = "检索知识库（ReAct 手搓/混合检索学习笔记），返回与问题最相关的资料块，模型应据 [资料N] 标注引用")
    public String retrieve(
            @ToolParam(description = "检索的关键词或问题") String query,
            @ToolParam(description = "返回的条数，默认 5") Integer topK) {

        if (StringUtils.isEmpty(query)) {
            return "检索问题不能为空";
        }

        if (topK == null || topK <= 0) {
            topK = 5;
        }

        boolean indexed = hybridRagDemo.isIndexed();
        if(!indexed){
            return "数据未建立索引，请先调用 POST /hybrid/index 构建索引";
        }

        List<Document> docs = hybridRagDemo.retrieve(query,topK);
        if (docs.isEmpty()) {
            return "未检索到相关内容";
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

        return sb.toString();

    }
}
