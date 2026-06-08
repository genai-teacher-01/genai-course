package com.genai.course.agents.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEvent(
        int step,
        String event,
        String timestamp,
        String node,
        String tool,
        Map<String, Object> args,
        Object result,
        String error,
        String text
) {
    public static TraceEvent of(int step, String event) {
        return new TraceEvent(step, event, Instant.now().toString(), null, null, null, null, null, null);
    }

    public static TraceEvent ofNode(int step, String event, String node) {
        return new TraceEvent(step, event, Instant.now().toString(), node, null, null, null, null, null);
    }

    public static TraceEvent ofTool(int step, String event, String tool, Map<String, Object> args, Object result) {
        return new TraceEvent(step, event, Instant.now().toString(), null, tool, args, result, null, null);
    }

    public static TraceEvent ofError(int step, String event, String error) {
        return new TraceEvent(step, event, Instant.now().toString(), null, null, null, null, error, null);
    }

    public static TraceEvent ofLlm(int step, String event, String node, String text, Object result) {
        return new TraceEvent(step, event, Instant.now().toString(), node, null, null, result, null, text);
    }
}
