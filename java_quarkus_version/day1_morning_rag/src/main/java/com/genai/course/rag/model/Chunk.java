package com.genai.course.rag.model;

public record Chunk(
        String id,
        String text,
        String source,
        int chunkIndex
) {
}
