package com.ai.demo.memory;

import org.springframework.ai.chat.messages.Message;
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

    public SessionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读会话历史。无该会话 → 空列表（Agent 从零开始）。
     */
    public List<Message> load(String sessionId) {
        // TODO 由你实现
        // 坑（已用 javap 确认）：2.0 的 Message/AbstractMessage 没有任何 Jackson 类型注解，
        //   直接 ObjectMapper 序列化/反序列化会丢多态类型，反序列化时无法还原成
        //   UserMessage / AssistantMessage / ToolResponseMessage。
        // 可选方案：
        //   a) 自定义 Jackson mixin / @JsonTypeInfo 配置（classpath 上手动注册）
        //   b) 自定义序列化格式：按 MessageType 存 {type, text, toolCalls, toolResponses...} 再手动重建
        //   注意 AssistantMessage 要保留 toolCalls（id/name/arguments），ToolResponseMessage 要保留
        //   toolCallId/name/responseData——它们不是纯文本，丢了 agent 循环没法续跑。
        // sessionId 为 null/空 → 直接返回空列表（一次性会话不持久化）
        return new ArrayList<>();
    }

    /**
     * 写会话历史。SETEX 带 TTL。
     */
    public void save(String sessionId, List<Message> messages) {
        // TODO 由你实现（序列化方案与 load 对称；messages 为空时可直接跳过）
    }
}
