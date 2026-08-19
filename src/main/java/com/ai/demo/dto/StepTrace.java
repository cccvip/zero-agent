package com.ai.demo.dto;

/**
 * ReAct 循环里的一步。
 * action: "model_call"（调用模型，含首轮/每轮迭代）| "tool_call"（执行工具）
 * tool_call 才填 tool / args / result；model_call 只记耗时。
 */
public record StepTrace(String action, String tool, String args, String result, long ms) {
}
