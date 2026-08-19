package com.ai.demo.agent;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具的公共接口：每个工具暴露它的一组 ToolCallback（"名称/描述/Schema" 由 @Tool 注解给出），
 * 交给 ToolRegistry 统一收集。
 */
public interface AgentTool {

    List<ToolCallback> callbacks();
}
