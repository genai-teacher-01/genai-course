package com.genai.course.multiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriageDecision(
        String domain,
        String intent,
        String priorityGuess,
        boolean needsKnowledge,
        boolean needsAction,
        String reasoning
) {
}
