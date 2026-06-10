package com.genai.course.multiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResponse(
        boolean success,
        List<Object> results,
        String error,
        Map<String, Object> meta
) {
    public static ToolResponse ok(List<?> results) {
        return new ToolResponse(true, List.copyOf(results), null, null);
    }

    public static ToolResponse ok(List<?> results, Map<String, Object> meta) {
        return new ToolResponse(true, List.copyOf(results), null, meta);
    }

    public static ToolResponse fail(String error) {
        return new ToolResponse(false, List.of(), error, null);
    }
}
