package com.ai.demo.dto;

import java.util.List;

public class SessionMessageDto {
    private String type;
    private String text;
    private List<String> toolCalls;
    private List<String> toolResponses;

    public SessionMessageDto(){

    }

    public SessionMessageDto(String type, String text, List<String> toolCalls, List<String> toolResponses) {
        this.type = type;
        this.text = text;
        this.toolCalls = toolCalls;
        this.toolResponses = toolResponses;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<String> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public List<String> getToolResponses() {
        return toolResponses;
    }

    public void setToolResponses(List<String> toolResponses) {
        this.toolResponses = toolResponses;
    }
}
