package com.ai.demo.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTool {

    @Tool(description = "获取当前系统的日期与时间，格式：yyyy-MM-dd HH:mm:ss")
    public String getCurrentTime() {
        System.out.println("getCurrentTime start");
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(description = "时间计算,当前时间增加几小时，返回格式：yyyy-MM-dd HH:mm:ss")
    public String addTime(long hour) {
        System.out.println("addTime start");
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
