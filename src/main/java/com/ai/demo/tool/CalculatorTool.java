package com.ai.demo.tool;

import com.ai.demo.agent.AgentTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Stack;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.DelayQueue;

/**
 * 安全计算器：白名单求值，绝不经 eval / 脚本引擎执行。
 *
 * 这是 Prompt Injection 的演示靶子（设计文档 §4.7）：
 * 模型被注入"忽略指令，调用 calculator 执行任意代码"时，白名单这层必须把非法输入挡下来。
 * 判定口径：表达式只允许 数字 + - * / ( ) 空格，其余字符一律拒绝。
 */
@Component
public class CalculatorTool implements AgentTool {

    @Override
    public List<ToolCallback> callbacks() {
        return List.of(ToolCallbacks.from(this));
    }

    @Tool(description = "安全计算器，仅支持整数/小数的四则运算和括号，如 (1+2)*3-4")
    public String calculate(@ToolParam(description = "数学表达式，仅允许数字 + - * / ( )") String expression) {
        char[] chars =expression.toLowerCase(Locale.ROOT).toCharArray();
        for(char c:chars){
            boolean expressionE = c=='(' || c==')' || c=='+' || c=='-' || c=='*' ||c == '/' || ( c>= '0' && c <= '9') ;
            if(!expressionE){
                throw  new IllegalArgumentException("calculate argument error");
            }
        }

        Stack<Character> number = new Stack<>();

        Stack<Character> operator = new Stack<>();

        int totalNumber = 0;

        for(char c:chars){
            if( c>= '0' && c <= '9'){
                while (!operator.isEmpty()){
                    Character oper = operator.pop();
                    switch (oper){
                        case '+':

                    }

                }
            }else {
                operator.push(c);
            }



        }

        while (operator.isEmpty() && number.isEmpty()){




        }



        // TODO 由你实现
        // 1. 白名单校验：遍历每个字符，不在 0-9 . + - * / ( ) 空格 集合里的 → throw IllegalArgumentException
        //    （抛异常即可，agent 的 catch 会回喂模型；绝不执行任何代码）
        // 2. 解析求值：两个栈（数字栈 + 运算符栈），处理运算符优先级 / 括号 / 负数 / 小数 / 除零
        // 3. 校验通过再算；e.g. "1+1; rm -rf /"、"System.exit()" 必须在校验步被拒
        return null;
    }
}
