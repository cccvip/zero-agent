package com.ai.demo.agent;

import com.ai.demo.dto.AgentResult;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agentic RAG 的 ReAct 循环（手搓版升级，对齐设计文档 §4.1）。
 *
 * 底座 = react/ReActAgent 已实现的循环，机制全部保留：
 *   - while(true) + break、maxStep 兜底
 *   - 工具异常 catch 回喂、幻觉工具名回喂"工具不存在"
 *
 * 升级点（本次要手搓的部分）：
 *   1. 工具查表：把硬编码 List<ToolCallback> 换成 toolRegistry.lookup(name).call(args)
 *   2. Trace：每轮记录 StepTrace（model_call / tool_call + 耗时），失败定位"规划/检索/工具/生成"哪一步挂了
 *   3. system 引导：起始注入"仅当问题涉及知识库内容时才调用 retrieve"，防模型每轮盲目检索烧 token
 *   4. token 统计：ChatResponse 的 metadata.usage（prompt/completion），多轮累加
 *
 * 踩坑提醒（第一周错误清单复习）：
 *   - ToolCallback 必须放进 DeepSeekChatOptions.toolCallbacks()，否则模型看不见工具且不报错
 *   - catch 到工具异常必须回喂给模型，不能只 printStackTrace（否则下一轮协议校验直接 400）
 *   - maxStep 是最后一道防线，不是唯一防线
 */
@Component
public class ReActAgent {

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;

    public ReActAgent(ChatModel chatModel, ToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 跑一轮 Agentic 对话。
     *
     * @param messages  会话历史（调用方传入，本方法会往里追加新消息；调用方决定何时持久化）
     * @param userPrompt 用户本次输入
     */
    public AgentResult run(List<Message> messages, String userPrompt) {
        // TODO 由你实现
        // 参照 react/ReActAgent.run() 的结构改造：
        // 1. messages.add(UserMessage)
        // 2. List<ToolCallback> = toolRegistry.all()，放进 DeepSeekChatOptions
        // 3. while(true)：
        //    step++；step > maxStep → 返回终止文案
        //    记 model_call trace（本步耗时 System.currentTimeMillis 差）
        //    chatModel.call(new Prompt(messages, options)) → assistantMessage 入列
        //    assistantMessage.hasToolCalls() ?
        //       对每个 toolCall：toolRegistry.lookup(name)
        //           null → 回喂"工具不存在"（幻觉名兜底）
        //           else → try call(arguments) catch(Exception) 回喂错误文案
        //           记 tool_call trace（工具名/参数/结果/耗时）
        //       ToolResponseMessage 入列，continue
        //       : assistantMessage.getText() → break
        // 4. 每轮 ChatResponse.getMetadata().getUsage() 拿 promptTokens/completionTokens 累加
        return null;
    }
}
