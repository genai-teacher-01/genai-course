package com.genai.course.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class LlmService {

    @ConfigProperty(name = "rag.llm.mode", defaultValue = "mock")
    String llmMode;

    @ConfigProperty(name = "rag.gemini.api-key")
    Optional<String> geminiApiKey;

    @ConfigProperty(name = "rag.gemini.model", defaultValue = "gemini-2.5-flash")
    String geminiModel;

    @ConfigProperty(name = "rag.vertex.project")
    Optional<String> vertexProject;

    @ConfigProperty(name = "rag.vertex.location", defaultValue = "us-east1")
    String vertexLocation;

    @ConfigProperty(name = "rag.vertex.credentials", defaultValue = "./service_account.json")
    String vertexCredentials;

    private final ObjectMapper mapper = new ObjectMapper();

    public String callLlm(String prompt) {
        return switch (llmMode.toLowerCase().trim()) {
            case "mock" -> callMock(prompt);
            case "gemini_free" -> callGeminiFree(prompt);
            case "vertex" -> callVertex(prompt);
            default -> throw new IllegalArgumentException(
                    "LLM_MODE non valido: " + llmMode + ". Usa 'mock', 'gemini_free' oppure 'vertex'.");
        };
    }

    public String getMode() {
        return llmMode;
    }

    private String callMock(String prompt) {
        return """
                MOCK RESPONSE

                La pipeline RAG ha recuperato un contesto e ha costruito un prompt. \
                In modalita' reale, questo prompt verrebbe inviato a Gemini su Vertex AI.

                Controlla sopra i chunk recuperati: se sono pertinenti, la RAG ha buone probabilita' \
                di produrre una risposta corretta. Se i chunk non sono pertinenti, il problema e' nel retrieval, \
                non nel modello.

                Fonti: vedere i chunk recuperati.""";
    }

    private String callGeminiFree(String prompt) {
        String key = geminiApiKey.filter(k -> !k.isBlank()).orElse(null);
        if (key == null) {
            throw new IllegalStateException(
                    "GOOGLE_API_KEY non impostata. Per usare la modalita' gemini_free, " +
                            "crea una API key su Google AI Studio e inseriscila in application.properties " +
                            "(rag.gemini.api-key) oppure come variabile d'ambiente GOOGLE_API_KEY.");
        }

        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + geminiModel + ":generateContent?key=" + key;

            String body = mapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    ))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Gemini API errore " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            return root.path("candidates").path(0).path("content")
                    .path("parts").path(0).path("text").asText();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Chiamata Gemini interrotta", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Errore nella chiamata Gemini: " + e.getMessage(), e);
        }
    }

    private String callVertex(String prompt) {
        throw new UnsupportedOperationException(
                "La modalita' Vertex AI richiede la dipendenza google-cloud-vertexai. " +
                        "Decommenta la dipendenza nel pom.xml e implementa la chiamata. " +
                        "Per ora, usa 'mock' o 'gemini_free'. ");
    }
}
