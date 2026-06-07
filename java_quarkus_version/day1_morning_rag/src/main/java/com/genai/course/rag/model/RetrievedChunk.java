package com.genai.course.rag.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievedChunk(
        String text,
        String source,
        int chunkIndex,
        double distance,
        String domain,
        String chunkProfile
) {
}
