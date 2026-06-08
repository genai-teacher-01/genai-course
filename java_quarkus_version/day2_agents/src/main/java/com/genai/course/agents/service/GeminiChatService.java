package com.genai.course.agents.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genai.course.agents.model.AgentState;
import com.genai.course.agents.model.ChatMessage;
import com.genai.course.agents.model.ToolCall;
import com.genai.course.agents.model.TraceEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servizio di chat con Gemini API con supporto function calling.
 *
 * Corrisponde a ChatGoogleGenerativeAI + bind_tools() nella versione Python.
 * Supporta tre modalita': mock, gemini_free, vertex.
 */
@ApplicationScoped
public class GeminiChatService {

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    @ConfigProperty(name = "agent.llm.mode", defaultValue = "mock")
    String llmMode;

    @ConfigProperty(name = "agent.gemini.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "agent.gemini.model", defaultValue = "gemini-2.0-flash")
    String geminiModel;

    @Inject ToolRegistry toolRegistry;
    @Inject ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void invoke(AgentState state, String systemPrompt) {
        if ("mock".equals(llmMode)) {
            invokeMock(state);
            return;
        }

        String key = apiKey.filter(k -> !k.isBlank()).orElse(null);
        if (key == null) {
            state.finalAnswer = "GOOGLE_API_KEY non configurata.";
            state.addTrace(TraceEvent.ofError(state.nextTraceStep(), "configuration_error", state.finalAnswer));
            return;
        }

        invokeGemini(state, systemPrompt, key);
    }

    private void invokeGemini(AgentState state, String systemPrompt, String key) {
        try {
            List<Map<String, Object>> contents = state.messages.stream()
                    .filter(m -> !"system".equals(m.role()))
                    .map(ChatMessage::toGeminiContent)
                    .collect(Collectors.toList());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", contents);

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                body.put("systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ));
            }

            body.put("tools", toolRegistry.getToolDeclarations());

            body.put("generationConfig", Map.of(
                    "temperature", 0
            ));

            String json = objectMapper.writeValueAsString(body);
            String url = String.format(GEMINI_URL, geminiModel, key);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            state.lastLlmCallMs = System.currentTimeMillis();

            if (response.statusCode() != 200) {
                state.finalAnswer = "Errore Gemini API: HTTP " + response.statusCode();
                state.addTrace(TraceEvent.ofError(state.nextTraceStep(), "llm_error", state.finalAnswer));
                return;
            }

            parseGeminiResponse(state, response.body());

        } catch (Exception e) {
            state.finalAnswer = "Errore chiamata LLM: " + e.getMessage();
            state.addTrace(TraceEvent.ofError(state.nextTraceStep(), "llm_error", state.finalAnswer));
        }
    }

    @SuppressWarnings("unchecked")
    private void parseGeminiResponse(AgentState state, String responseBody) throws Exception {
        Map<String, Object> resp = objectMapper.readValue(responseBody, new TypeReference<>() {});

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            state.finalAnswer = "Nessuna risposta dal modello.";
            return;
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");

        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder textBuilder = new StringBuilder();

        for (Map<String, Object> part : parts) {
            if (part.containsKey("functionCall")) {
                Map<String, Object> fc = (Map<String, Object>) part.get("functionCall");
                String name = (String) fc.get("name");
                Map<String, Object> args = (Map<String, Object>) fc.getOrDefault("args", Map.of());
                String callId = "call-" + UUID.randomUUID().toString().substring(0, 8);
                toolCalls.add(new ToolCall(callId, name, args));
            }
            if (part.containsKey("text")) {
                textBuilder.append(part.get("text"));
            }
        }

        String text = textBuilder.toString().trim();

        if (!toolCalls.isEmpty()) {
            state.messages.add(ChatMessage.modelToolCalls(toolCalls));
            state.pendingToolCalls = new ArrayList<>(toolCalls);
            state.finalAnswer = null;
        } else if (!text.isEmpty()) {
            state.messages.add(ChatMessage.modelText(text));
            state.finalAnswer = text;
        } else {
            state.finalAnswer = "(Risposta vuota dal modello)";
        }

        state.addTrace(TraceEvent.ofLlm(state.nextTraceStep(), "llm_response", "llm",
                text.isEmpty() ? "[tool-call only]" : text,
                Map.of("tool_calls", toolCalls.stream()
                        .map(tc -> Map.of("id", tc.id(), "name", tc.name(), "args", tc.args()))
                        .toList())));
    }

    /**
     * Mock mode: simula il comportamento del modello con keyword matching.
     * Utile per demo senza API key.
     */
    private void invokeMock(AgentState state) {
        String lastUserText = "";
        for (int i = state.messages.size() - 1; i >= 0; i--) {
            ChatMessage m = state.messages.get(i);
            if ("user".equals(m.role()) && m.text() != null) {
                lastUserText = m.text().toLowerCase();
                break;
            }
        }

        boolean hasToolResults = state.messages.stream()
                .anyMatch(m -> "tool_result".equals(m.role()));

        if (hasToolResults && state.pendingToolCalls.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("**Risposta mock basata sui risultati dei tool:**\n\n");

            for (ChatMessage m : state.messages) {
                if ("tool_result".equals(m.role()) && m.toolResult() != null) {
                    sb.append("- **").append(m.toolName()).append("**: ")
                      .append(m.toolResult(), 0, Math.min(200, m.toolResult().length()));
                    if (m.toolResult().length() > 200) sb.append("...");
                    sb.append("\n");
                }
            }

            sb.append("\n*Questa e' una risposta mock. Configura agent.llm.mode=gemini_free per risposte reali.*");
            state.messages.add(ChatMessage.modelText(sb.toString()));
            state.finalAnswer = sb.toString();
            state.addTrace(TraceEvent.ofLlm(state.nextTraceStep(), "llm_response_mock", "llm",
                    state.finalAnswer, Map.of()));
            return;
        }

        List<ToolCall> mockCalls = new ArrayList<>();

        if (lastUserText.contains("inc-") || lastUserText.contains("itsm-") || lastUserText.contains("ticket") || lastUserText.contains("record")) {
            String ticketId = extractTicketId(lastUserText);
            if (ticketId != null) {
                mockCalls.add(new ToolCall("mock-1", "lookup_record",
                        Map.of("record_id", ticketId)));
            }
        }

        if (lastUserText.contains("sla") || lastUserText.contains("breach") || lastUserText.contains("violazione")) {
            String ticketId = extractTicketId(lastUserText);
            if (ticketId != null && mockCalls.isEmpty()) {
                mockCalls.add(new ToolCall("mock-1", "lookup_record",
                        Map.of("record_id", ticketId)));
            }
        }

        if (lastUserText.contains("policy") || lastUserText.contains("escalation") || lastUserText.contains("procedur")
                || lastUserText.contains("sla") || lastUserText.contains("knowledge") || lastUserText.contains("change")) {
            String query = lastUserText.length() > 80 ? lastUserText.substring(0, 80) : lastUserText;
            mockCalls.add(new ToolCall("mock-kb-" + (mockCalls.size() + 1), "search_kb",
                    Map.of("query", query, "top_k", 3)));
        }

        if (mockCalls.isEmpty()) {
            String answer = "*Risposta mock*: Non ho individuato keyword per attivare tool. " +
                    "Prova con un ID ticket (es. INC-1002) o parole chiave come 'policy', 'escalation', 'SLA'. " +
                    "Per risposte reali configura agent.llm.mode=gemini_free.";
            state.messages.add(ChatMessage.modelText(answer));
            state.finalAnswer = answer;
            state.addTrace(TraceEvent.ofLlm(state.nextTraceStep(), "llm_response_mock", "llm",
                    answer, Map.of()));
            return;
        }

        state.messages.add(ChatMessage.modelToolCalls(mockCalls));
        state.pendingToolCalls = new ArrayList<>(mockCalls);
        state.finalAnswer = null;
        state.addTrace(TraceEvent.ofLlm(state.nextTraceStep(), "llm_mock_tool_calls", "llm",
                "[mock tool dispatch]",
                Map.of("tool_calls", mockCalls.stream()
                        .map(tc -> Map.of("name", tc.name(), "args", tc.args()))
                        .toList())));
    }

    private String extractTicketId(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(inc-\\d+|itsm-\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return m.find() ? m.group(1).toUpperCase() : null;
    }
}
