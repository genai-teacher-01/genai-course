package com.genai.course.rag.service;

import com.genai.course.rag.model.RetrievedChunk;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@ApplicationScoped
public class QaLogService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ConfigProperty(name = "rag.qa-log.path", defaultValue = "qa_log.md")
    String qaLogPath;

    public void appendLog(String question, String answer, List<RetrievedChunk> results,
                          String profileName, String domain, int k) {
        String timestamp = LocalDateTime.now().format(FORMATTER);

        StringBuilder sources = new StringBuilder();
        for (RetrievedChunk chunk : results) {
            sources.append("- %s | domain=%s | chunk=%d | score=%.4f\n".formatted(
                    chunk.source(),
                    chunk.domain() != null ? chunk.domain() : "n/a",
                    chunk.chunkIndex(),
                    chunk.distance()));
        }

        String entry = """
                ## %s

                ### Question
                %s

                ### Settings
                - profile: %s
                - domain filter: %s
                - top_k: %d

                ### Retrieved sources
                %s
                ### Answer
                %s

                ### Human note
                - corretto/parziale/sbagliato:
                - osservazioni:
                - possibile miglioramento:

                ---

                """.formatted(timestamp, question, profileName,
                domain != null ? domain : "none", k,
                sources.toString(), answer);

        try {
            Path path = Path.of(qaLogPath);
            Files.writeString(path, entry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Errore scrittura QA log: " + e.getMessage(), e);
        }
    }
}
