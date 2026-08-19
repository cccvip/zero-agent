package com.ai.demo.controller;

import com.ai.demo.dto.EvalRequest;
import com.ai.demo.eval.EvalRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评测入口：POST /api/eval/run
 * 触发 EvalRunner 跑测试集（Agentic vs 固定流水线 A/B）。
 */
@RestController
@RequestMapping("/api")
public class EvalController {

    private final EvalRunner evalRunner;

    public EvalController(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    @PostMapping("/eval/run")
    public Map<String, Object> run(@RequestBody(required = false) EvalRequest request) {
        String testSetPath = (request == null || request.testSetPath() == null)
                ? "testset.json"
                : request.testSetPath();
        return evalRunner.run(testSetPath);
    }
}
