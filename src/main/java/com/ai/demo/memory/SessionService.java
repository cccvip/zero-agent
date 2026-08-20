package com.ai.demo.memory;

import com.ai.demo.dto.SessionMessageDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话短期记忆（Redis 持久化，设计文档 §4.4）。
 * key：session:{sessionId} → JSON 消息列表；TTL 24h。
 * 无跨会话共享状态，并发安全边界 = 按 sessionId 读写。
 */
@Service
public class SessionService {

    private static final String KEY_PREFIX = "session:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读会话历史。无该会话 → 空列表（Agent 从零开始）。
     */
    public List<Message> load(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return new ArrayList<>();
        }
        String key = KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<SessionMessageDto> dtos = objectMapper.readValue(json, new TypeReference<>() {});
            List<Message> messages = new ArrayList<>(dtos.size());
            for (SessionMessageDto dto : dtos) {
                Message message = rebuildMessage(dto);
                if (message != null) {
                    messages.add(message);
                }
            }
            return messages;
        } catch (Exception e) {
            // 数据损坏时直接清空，避免 agent 拿到畸形历史导致协议 400
            redisTemplate.delete(key);
            return new ArrayList<>();
        }
    }

    /**
     * 写会话历史。SETEX 带 TTL。
     */
    public void save(String sessionId, List<Message> messages) {
        if (StringUtils.isBlank(sessionId) || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            List<SessionMessageDto> dtos = new ArrayList<>(messages.size());
            for (Message message : messages) {
                SessionMessageDto dto = convertToDto(message);
                if (dto != null) {
                    dtos.add(dto);
                }
            }
            String json = objectMapper.writeValueAsString(dtos);
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, json, TTL);
        } catch (Exception e) {
            // 序列化失败不影响主对话，仅打印日志；可升级为 slf4j
            e.printStackTrace();
        }
    }

    private SessionMessageDto convertToDto(Message message) {
        SessionMessageDto dto = new SessionMessageDto();
        MessageType messageType = message.getMessageType();
        dto.setType(messageType.name());
        switch (messageType) {
            case SYSTEM -> dto.setText(message.getText());
            case USER -> dto.setText(message.getText());
            case ASSISTANT -> {
                AssistantMessage assistantMessage = (AssistantMessage) message;
                dto.setText(assistantMessage.getText());
                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    List<String> toolCallJsons = new ArrayList<>(toolCalls.size());
                    for (AssistantMessage.ToolCall toolCall : toolCalls) {
                        toolCallJsons.add(writeJson(toolCall));
                    }
                    dto.setToolCalls(toolCallJsons);
                }
            }
            case TOOL -> {
                ToolResponseMessage toolResponseMessage = (ToolResponseMessage) message;
                List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
                if (responses != null && !responses.isEmpty()) {
                    List<String> responseJsons = new ArrayList<>(responses.size());
                    for (ToolResponseMessage.ToolResponse response : responses) {
                        responseJsons.add(writeJson(response));
                    }
                    dto.setToolResponses(responseJsons);
                }
            }
            default -> {
                // 未知类型跳过
                return null;
            }
        }
        return dto;
    }

    private Message rebuildMessage(SessionMessageDto dto) {
        String type = dto.getType();
        return switch (type) {
            case "SYSTEM" -> new SystemMessage(dto.getText());
            case "USER" -> UserMessage.builder().text(dto.getText()).build();
            case "ASSISTANT" -> {
                List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                List<String> toolCallJsons = dto.getToolCalls();
                if (toolCallJsons != null) {
                    for (String tcJson : toolCallJsons) {
                        AssistantMessage.ToolCall toolCall = readJson(tcJson, AssistantMessage.ToolCall.class);
                        if (toolCall != null) {
                            toolCalls.add(toolCall);
                        }
                    }
                }
                yield new AssistantMessage(dto.getText(), toolCalls);
            }
            case "TOOL" -> {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                List<String> responseJsons = dto.getToolResponses();
                if (responseJsons != null) {
                    for (String trJson : responseJsons) {
                        ToolResponseMessage.ToolResponse response = readJson(trJson, ToolResponseMessage.ToolResponse.class);
                        if (response != null) {
                            responses.add(response);
                        }
                    }
                }
                yield ToolResponseMessage.builder().responses(responses).build();
            }
            default -> throw new IllegalStateException("未知消息类型: " + type);
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T readJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            // 单个对象损坏时跳过，不丢弃整段历史
            e.printStackTrace();
            return null;
        }
    }
}
