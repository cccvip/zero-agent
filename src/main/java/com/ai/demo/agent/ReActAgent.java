package com.ai.demo.agent;

import com.ai.demo.dto.AgentResult;
import com.ai.demo.dto.StepTrace;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
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

    private final ChatModel deepSeekChatModel;
    private final ToolRegistry toolRegistry;

    public ReActAgent(ChatModel deepSeekChatModel, ToolRegistry toolRegistry) {
        this.deepSeekChatModel = deepSeekChatModel;
        this.toolRegistry = toolRegistry;
    }
    private int maxStep = 5;
    /**
     * 跑一轮 Agentic 对话。
     *
     * @param messages  会话历史（调用方传入，本方法会往里追加新消息；调用方决定何时持久化）
     * @param userPrompt 用户本次输入
     */
    public AgentResult run(List<Message> messages, String userPrompt) {
        if(messages.isEmpty()){
            messages.add(new SystemMessage(
                    "你是 ReAct 助手。仅当用户问题涉及学习笔记或知识库内容时才调用 retrieve 工具；否则直接回答。"
            ));
        }
        String answer;
        List<StepTrace> trace = new ArrayList<>();
        long promptTokens =0 ;
        long completionTokens = 0;

        int step=0;

        UserMessage userMessage =  UserMessage.builder().text(userPrompt).build();
        messages.add(userMessage);

        List<ToolCallback> toolCallbacks = toolRegistry.all();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .build();

        while (true){
            step++;

            if(step>maxStep){
                answer = "已达到最大步数 " + maxStep + "，任务未完成";
                break;
            }

            long start = System.currentTimeMillis();

            Prompt prompt1 = new Prompt(messages,options);
            ChatResponse chatResponse = deepSeekChatModel.call(prompt1);

            long end = System.currentTimeMillis();

            StepTrace modelStep = new StepTrace("model_call","","","",end-start);
            trace.add(modelStep);

            ChatResponseMetadata chatResponseMetadata = chatResponse.getMetadata();
            Usage usage = chatResponseMetadata.getUsage();

            if(usage!=null){
                completionTokens+=usage.getCompletionTokens();
                promptTokens+=usage.getPromptTokens();
            }

            Generation generation =  chatResponse.getResult();
            AssistantMessage assistantMessage = generation.getOutput();
            messages.add(assistantMessage);

            if(assistantMessage.hasToolCalls()){
                List<ToolResponseMessage.ToolResponse> list = new ArrayList<>();
                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                for(AssistantMessage.ToolCall toolCall:toolCalls){
                    ToolCallback cBack = toolRegistry.lookup(toolCall.name());
                    //处理Tool不存在的情况
                    if(cBack ==null){
                        list.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "工具不存在"));
                        trace.add(new StepTrace("tool_call", toolCall.name(), toolCall.arguments(), "工具不存在", 0L));
                        continue;
                    }
                    //Tool执行报错
                    long toolStart = System.currentTimeMillis();
                    try{

                        String result = cBack.call(toolCall.arguments());
                        ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                                toolCall.id(),
                                toolCall.name(),
                                result
                        );
                        long toolEnd = System.currentTimeMillis();

                        StepTrace toolStep = new StepTrace("tool_call",toolCall.name(),toolCall.arguments(),result,toolEnd-toolStart);
                        trace.add(toolStep);
                        list.add(toolResponse);
                    }catch (Exception e){
                        long toolEnd = System.currentTimeMillis();
                        System.out.println("执行工具: " + toolCall.name() + " 参数: " + toolCall.arguments());
                        list.add(new ToolResponseMessage.ToolResponse(
                                toolCall.id(), toolCall.name(), "工具执行失败: " + e.getMessage()));
                        System.out.println("回喂错误: " + e.getMessage());
                        trace.add(new StepTrace("tool_call", toolCall.name(), toolCall.arguments(), "工具执行失败: " + e.getMessage(), toolEnd - toolStart));
                    }
                }
                ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(list).build();
                messages.add(toolResponseMessage);
            }else {
                answer=assistantMessage.getText();
                break;
            }
        }
        return new AgentResult(answer,trace,promptTokens,completionTokens);
    }
}
