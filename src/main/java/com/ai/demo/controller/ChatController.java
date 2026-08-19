package com.ai.demo.controller;

import com.ai.demo.agent.ReActAgent;
import com.ai.demo.dto.ChatRequest;
import com.ai.demo.dto.ChatResponse;
import com.ai.demo.dto.StepTrace;
import com.ai.demo.memory.SessionService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agentic RAG 入口（设计文档 §3 总体架构）。
 * POST /api/chat {sessionId, message}
 * Redis 取历史 → Agent 循环（自主决定检索/调工具）→ 写回历史 → 返回 answer + sources + tokenStats + trace。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ReActAgent reActAgent;
    private final SessionService sessionService;

    public ChatController(ReActAgent reActAgent, SessionService sessionService) {
        this.reActAgent = reActAgent;
        this.sessionService = sessionService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        List<Message> messages = sessionService.load(request.sessionId());
        var result = reActAgent.run(messages, request.message());
        sessionService.save(request.sessionId(), messages);

        // sources = 本次实际用到的资料（trace 里 retrieve 工具的执行结果），前端据此核对引用是否属实
        List<String> sources = result.trace().stream()
                .filter(s -> "tool_call".equals(s.action()) && "retrieve".equals(s.tool()))
                .map(StepTrace::result)
                .toList();

        return new ChatResponse(result.answer(), sources,
                Map.of("promptTokens", result.promptTokens(), "completionTokens", result.completionTokens()),
                result.trace());
    }
}
