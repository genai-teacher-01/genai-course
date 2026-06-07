package com.genai.course.rag.model;

public record ChunkProfile(
        String name,
        int chunkSize,
        int chunkOverlap
) {
}
