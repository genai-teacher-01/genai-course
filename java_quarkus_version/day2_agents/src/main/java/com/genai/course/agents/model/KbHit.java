package com.genai.course.agents.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KbHit(
        String source,
        String snippet,
        Double score,
        Double distance,
        Integer chunkIndex
) {
}
