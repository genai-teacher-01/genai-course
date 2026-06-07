package com.genai.course.rag.service;

import com.genai.course.rag.model.Chunk;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ChunkingService {

    public List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        String cleaned = text.replaceAll("\\s+", " ").trim();

        if (cleaned.length() <= chunkSize) {
            return List.of(cleaned);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < cleaned.length()) {
            int end = start + chunkSize;
            String chunk = cleaned.substring(start, Math.min(end, cleaned.length())).trim();

            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (end >= cleaned.length()) {
                break;
            }

            start = end - chunkOverlap;
        }

        return chunks;
    }

    public List<Chunk> buildChunks(Map<String, String> documents, int chunkSize, int chunkOverlap) {
        List<Chunk> allChunks = new ArrayList<>();

        documents.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String source = entry.getKey();
                    String text = entry.getValue();
                    List<String> parts = splitText(text, chunkSize, chunkOverlap);

                    for (int idx = 0; idx < parts.size(); idx++) {
                        allChunks.add(new Chunk(
                                source + "::chunk_" + idx,
                                parts.get(idx),
                                source,
                                idx
                        ));
                    }
                });

        return allChunks;
    }

    public String inferDomainFromFilename(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("hr")) return "hr";
        if (lower.contains("procurement")) return "procurement";
        if (lower.contains("itsm")) return "itsm";
        return "general";
    }
}
