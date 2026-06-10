package com.genai.course.multiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEvent(
        int step,
        String event,
        String timestamp,
        String agent,
        Object payload,
        String error
) {
    public static TraceEvent of(int step, String event, String agent, Object payload) {
        return new TraceEvent(step, event, java.time.Instant.now().toString(), agent, payload, null);
    }

    public static TraceEvent ofError(int step, String event, String agent, String error) {
        return new TraceEvent(step, event, java.time.Instant.now().toString(), agent, null, error);
    }
}
