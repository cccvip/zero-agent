package com.ai.demo.tool;

import com.ai.demo.agent.AgentTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 天气查询。设计文档 §4.3：外部天气 API 不稳定/网络受限时可降级为模拟数据源。
 *
 * 当前实现 = 模拟数据（纯本地、零网络，演示够用）。
 * 接真实 API 时：把方法体换成 RestClient/WebClient 调用，签名和 Schema 不用动。
 */
@Component
public class WeatherTool implements AgentTool {

    @Override
    public List<ToolCallback> callbacks() {
        return List.of(ToolCallbacks.from(this));
    }

    private static final Map<String, String> MOCK = Map.of(
            "北京", "晴，28℃，微风，空气质量良",
            "上海", "多云，30℃，东南风3级",
            "广州", "雷阵雨，31℃，闷热",
            "深圳", "阵雨，29℃，湿度大");

    @Tool(description = "查询指定城市的天气情况")
    public String getWeather(@ToolParam(description = "城市名，如 北京/上海/广州") String city) {
        if (city == null || city.isBlank()) {
            return "请提供城市名";
        }
        String known = MOCK.get(city.trim());
        if (known != null) {
            return city.trim() + "：" + known;
        }
        int temp = 22 + ThreadLocalRandom.current().nextInt(10);
        return city.trim() + "：晴间多云，" + temp + "℃，适合出行（模拟数据，仅供参考）。";
    }
}
