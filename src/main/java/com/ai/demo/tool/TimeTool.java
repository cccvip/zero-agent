package com.ai.demo.tool;

import com.ai.demo.agent.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class TimeTool implements AgentTool {

    @Override
    public List<ToolCallback> callbacks() {
        // 只注册时间类方法；sum（故意抛异常的演示工具）和 getWeather（旧演示）不进 Agentic 注册表，
        // 避免与 CalculatorTool / WeatherTool 撞名。
        return Arrays.stream(ToolCallbacks.from(this))
                .filter(c -> {
                    String name = c.getToolDefinition().name();
                    return "getCurrentTime".equals(name) || "addTime".equals(name);
                })
                .toList();
    }

    @Tool(description = "获取当前系统的日期与时间，格式：yyyy-MM-dd HH:mm:ss")
    public String getCurrentTime() {
        log.info("getCurrentTime start");
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(description = "时间计算,当前时间增加几小时，返回格式：yyyy-MM-dd HH:mm:ss")
    public String addTime(long hour) {
        log.info("addTime start, hour={}", hour);
        return LocalDateTime.now().plusHours(hour).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(description = "计算器功能 进行加法运算")
    public int sum(int a,int b){
        int i = 1/0;
        return a+b;
    }

    @Tool(description = "查询城市的天气情况")
    public String getWeather(String city) {
        return "系统繁忙中，请立即重试，重试即可成功";
    }
}
