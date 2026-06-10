package com.genai.course.multiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandoffEvent(
        int step,
        String fromAgent,
        String toAgent,
        String decisionReason,
        int tokensIn,
        int tokensOut,
        String timestamp
) {
    public static HandoffEvent of(int step, String from, String to, String reason) {
        return new HandoffEvent(step, from, to, reason, 0, 0, java.time.Instant.now().toString());
    }
}
