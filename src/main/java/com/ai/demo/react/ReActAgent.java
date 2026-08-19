package com.ai.demo.react;

import com.ai.demo.tool.TimeTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component(value = "DeepSeekAgent")
public class ReActAgent {

    private ChatModel deepSeekChatModel;

    private TimeTool timeTool;

    private Integer maxStep=5;

    public ReActAgent(ChatModel deepSeekChatModel, TimeTool timeTool) {
        this.deepSeekChatModel = deepSeekChatModel;
        this.timeTool = timeTool;
    }

    public String run(List<Message> messages,String userPrompt){
        String content = "";
        int step=0;
        UserMessage userMessage =  UserMessage.builder().text(userPrompt).build();
        messages.add(userMessage);

        List<ToolCallback> toolCallbacks = Arrays.asList(ToolCallbacks.from(timeTool));

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .toolCallbacks(toolCallbacks)      // 同一份，放进请求配置
                .build();

        while (true){

            step++;

            if(step>maxStep){
                content = "已达到最大步数 " + maxStep + "，任务未完成";
                break;
            }

            Prompt prompt1 = new Prompt(messages,options);
            ChatResponse chatResponse = deepSeekChatModel.call(prompt1);
            Generation generation =  chatResponse.getResult();
            AssistantMessage assistantMessage = generation.getOutput();
            messages.add(assistantMessage);

            if(assistantMessage.hasToolCalls()){
                List<ToolResponseMessage.ToolResponse> list = new ArrayList<>();
                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                for(AssistantMessage.ToolCall toolCall:toolCalls){
                    ToolCallback cBack = null;
                    for(ToolCallback callback: toolCallbacks){
                        String cb = callback.getToolDefinition().name();
                        String name = toolCall.name();
                        if(name.equals(cb)){
                            cBack = callback;
                            break;
                        }
                    }
                    //处理Tool不存在的情况
                    if(cBack ==null){
                        list.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "工具不存在"));
                        continue;
                    }
                    //Tool执行报错
                    try{
                        String result = cBack.call(toolCall.arguments());
                        ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                                toolCall.id(),
                                toolCall.name(),
                                result
                        );
                        list.add(toolResponse);
                    }catch (Exception e){
                        System.out.println("执行工具: " + toolCall.name() + " 参数: " + toolCall.arguments());
                        list.add(new ToolResponseMessage.ToolResponse(
                                toolCall.id(), toolCall.name(), "工具执行失败: " + e.getMessage()));
                        System.out.println("回喂错误: " + e.getMessage());
                    }
                }
                ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(list).build();
                messages.add(toolResponseMessage);
            }else {
                content=assistantMessage.getText();
                break;
            }
        }
        System.out.println(step);



        return content;
    }
}
