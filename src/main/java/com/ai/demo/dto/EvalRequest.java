package com.ai.demo.dto;

/**
 * POST /api/eval/run 入参。testSetPath 缺省时用 classpath 里的 testset.json。
 */
public record EvalRequest(String testSetPath) {
}
