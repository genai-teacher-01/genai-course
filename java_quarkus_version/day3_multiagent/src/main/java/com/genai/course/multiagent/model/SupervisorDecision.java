package com.genai.course.multiagent.model;

public record SupervisorDecision(
        String next,
        String reason
) {
}
