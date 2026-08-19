package com.ai.demo.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：把 Spring 容器里所有 AgentTool 收集成 name → ToolCallback 的映射。
 * Agent 查表执行（react/ReActAgent 里是硬编码 List<ToolCallback> 线性查找，这里升级为注册表）。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolCallback> callbacks;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, ToolCallback> map = new HashMap<>();
        for (AgentTool tool : tools) {
            for (ToolCallback callback : tool.callbacks()) {
                String name = callback.getToolDefinition().name();
                ToolCallback old = map.put(name, callback);
                if (old != null) {
                    throw new IllegalStateException("工具名冲突: " + name);
                }
            }
        }
        this.callbacks = Map.copyOf(map);
    }

    public ToolCallback lookup(String name) {
        return callbacks.get(name);
    }

    public List<ToolCallback> all() {
        return new ArrayList<>(callbacks.values());
    }
}
