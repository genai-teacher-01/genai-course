package com.genai.course.agents.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Rappresenta un messaggio nella conversazione agente-LLM.
 * Puo' contenere testo, tool calls (dal modello), o risultati tool.
 *
 * Corrisponde ai messaggi LangChain (HumanMessage, SystemMessage, ToolMessage)
 * nella versione Python.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        String text,
        List<ToolCall> toolCalls,
        String toolName,
        String toolCallId,
        String toolResult
) {
    public static ChatMessage system(String text) {
        return new ChatMessage("system", text, null, null, null, null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text, null, null, null, null);
    }

    public static ChatMessage modelText(String text) {
        return new ChatMessage("model", text, null, null, null, null);
    }

    public static ChatMessage modelToolCalls(List<ToolCall> calls) {
        return new ChatMessage("model", null, calls, null, null, null);
    }

    public static ChatMessage modelMixed(String text, List<ToolCall> calls) {
        return new ChatMessage("model", text, calls, null, null, null);
    }

    public static ChatMessage toolResult(String toolName, String toolCallId, String result) {
        return new ChatMessage("tool_result", null, null, toolName, toolCallId, result);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Converte al formato Gemini API contents.
     */
    public Map<String, Object> toGeminiContent() {
        return switch (role) {
            case "user" -> Map.of("role", "user", "parts", List.of(Map.of("text", text)));
            case "model" -> {
                if (hasToolCalls()) {
                    var parts = toolCalls.stream()
                            .map(tc -> Map.of("functionCall",
                                    (Object) Map.of("name", tc.name(), "args", tc.args())))
                            .toList();
                    yield Map.of("role", "model", "parts", parts);
                }
                yield Map.of("role", "model", "parts", List.of(Map.of("text", text != null ? text : "")));
            }
            case "tool_result" -> Map.of("role", "user", "parts", List.of(
                    Map.of("functionResponse", Map.of(
                            "name", toolName,
                            "response", Map.of("content", toolResult != null ? toolResult : "")))));
            default -> Map.of("role", "user", "parts", List.of(Map.of("text", text != null ? text : "")));
        };
    }
}
