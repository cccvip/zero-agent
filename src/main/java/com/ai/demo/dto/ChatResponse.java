package com.ai.demo.dto;

import java.util.List;
import java.util.Map;

/**
 * POST /api/chat 响应。
 * sources = 本次检索用到的资料原文（从 trace 里 retrieve 工具的结果提取），供前端核对引用。
 * tokenStats = {promptTokens, completionTokens}。
 */
public record ChatResponse(String answer, List<String> sources, Map<String, Object> tokenStats, List<StepTrace> trace) {
}
