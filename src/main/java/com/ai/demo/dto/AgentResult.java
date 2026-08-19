package com.ai.demo.dto;

import java.util.List;

/**
 * ReActAgent.run 的返回：答案 + 逐步 trace + 整轮 token 累计（多轮模型调用相加）。
 */
public record AgentResult(String answer, List<StepTrace> trace, long promptTokens, long completionTokens) {
}
