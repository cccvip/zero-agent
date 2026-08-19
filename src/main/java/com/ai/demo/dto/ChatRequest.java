package com.ai.demo.dto;

/**
 * POST /api/chat 入参。
 * sessionId 为空时视为一次性会话（不持久化有意义），非空时走 Redis 短期记忆。
 */
public record ChatRequest(String sessionId, String message) {
}
