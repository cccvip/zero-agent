package com.ai.demo.eval;

import com.ai.demo.agent.ReActAgent;
import com.ai.demo.rag.HybridRagDemo;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 自建轻量 Eval Runner（设计文档 §4.6），不引 RAGAS/DeepEval 等 Python 生态。
 *
 * A/B：同一测试集分别跑 Agentic（/api/chat 的 ReActAgent）与固定流水线（/hybrid/chat 的 fixedChat），
 * 对比 成功率 / token 成本 / 延迟 / 工具调用序列。
 *
 * 测试集 testset.json：{id, category, query, expected}，category 四类——
 * 关键词型 / 语义型 / 陷阱型 / Prompt Injection。
 */
@Component
public class EvalRunner {

    private final ReActAgent agent;
    private final HybridRagDemo hybridRagDemo;   // A/B 对照组：固定流水线

    public EvalRunner(ReActAgent agent, HybridRagDemo hybridRagDemo) {
        this.agent = agent;
        this.hybridRagDemo = hybridRagDemo;
    }

    /**
     * 跑测试集，返回汇总报告。
     *
     * @param testSetPath classpath 相对路径，缺省 "testset.json"
     */
    public Map<String, Object> run(String testSetPath) {
        // TODO 由你实现
        // 1. 读 classpath 的 testset.json → List<{id, category, query, expected}>
        // 2. 每条用例：
        //    Agentic：agent.run(new ArrayList<>(), query) —— 新会话，无历史
        //    固定流水线：hybridRagDemo.fixedChat(query)（已抽取为公开方法，A/B 对照组）
        // 3. 判定成功：answer 非空 + 包含 expected 的关键词（String.contains 即可，别引评测框架）
        // 4. 记录：是否成功 / 工具调用序列（从 AgentResult.trace 里抽 tool_call 的 tool 名）/
        //    token（prompt+completion）/ 延迟 ms
        // 5. 汇总：整体成功率、平均 token/延迟、工具序列分布、A/B 对比表
        // 6. Prompt Injection 用例额外记录"是否被拦截"（calculator 抛异常 = 拦截成功）
        // 输出 Map（JSON 化），控制台再打一份汇总表
        return null;
    }
}
