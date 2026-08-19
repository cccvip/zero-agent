package com.ai.demo.tool;

import com.ai.demo.agent.AgentTool;
import com.ai.demo.rag.HybridRagDemo;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.support.ToolCallbacks;
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
        // TODO 由你实现
        // 1. topK == null 时给默认值 5（@ToolParam 只描述不传值，不会自动填默认）
        // 2. hybridRagDemo.retrieve(query, topK) 复用混合检索（向量+BM25+RRF），别动它的逻辑
        // 3. 拼成 "[资料N][来源：xxx]\n<正文>\n---\n" 编号块——格式必须和 /hybrid/chat 的 RAG_TEMPLATE 对齐，
        //    否则模型标注的 [资料N] 对不上
        // 4. 空结果返回"未检索到相关内容"，别抛异常（抛异常 → agent 回喂 → 模型可能乱编）
        // 5. 注意前置条件：需先 POST /hybrid/index 建索引，corpus 为空时给出友好提示
        return null;
    }
}
