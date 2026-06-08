package com.genai.course.agents.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genai.course.agents.model.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro dei tool disponibili all'agente.
 *
 * Corrisponde a TOOLS / TOOL_MAP nella versione Python.
 * Contiene le definizioni JSON Schema per l'API Gemini
 * e il dispatch dell'esecuzione.
 */
@ApplicationScoped
public class ToolRegistry {

    @Inject KnowledgeBaseService kbService;
    @Inject TicketStoreService ticketStore;
    @Inject SlaService slaService;

    @Inject ObjectMapper objectMapper;

    public List<Map<String, Object>> getToolDeclarations() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(Map.of("functionDeclarations", List.of(
                Map.of(
                        "name", "search_kb",
                        "description", "Cerca policy, procedure, SLA, escalation e knowledge article nella KB ITSM.",
                        "parameters", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "STRING",
                                                "description", "Termini di ricerca per documentazione policy o procedurale."
                                        ),
                                        "top_k", Map.of(
                                                "type", "INTEGER",
                                                "description", "Numero massimo di risultati (default 3)."
                                        )
                                ),
                                "required", List.of("query")
                        )
                ),
                Map.of(
                        "name", "lookup_record",
                        "description", "Recupera record operativo ITSM/Jira-like dato id (es. INC-1002).",
                        "parameters", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "record_id", Map.of(
                                                "type", "STRING",
                                                "description", "Identificativo del ticket (es. INC-1002)."
                                        )
                                ),
                                "required", List.of("record_id")
                        )
                ),
                Map.of(
                        "name", "compute_sla",
                        "description", "Calcola stato SLA e raccomandazione operativa per ticket ITSM.",
                        "parameters", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "id", Map.of("type", "STRING", "description", "ID del ticket."),
                                        "priority", Map.of("type", "STRING", "description", "Priorità: P1, P2, P3 o P4."),
                                        "elapsed_hours", Map.of("type", "NUMBER", "description", "Ore trascorse dall'apertura."),
                                        "owner", Map.of("type", "STRING", "description", "Team owner del ticket.")
                                ),
                                "required", List.of("id", "priority", "elapsed_hours")
                        )
                )
        )));

        return tools;
    }

    public String executeTool(String toolName, Map<String, Object> args) {
        ToolResponse response = switch (toolName) {
            case "search_kb" -> {
                String query = stringArg(args, "query", "");
                int topK = intArg(args, "top_k", 3);
                yield kbService.searchKb(query, topK);
            }
            case "lookup_record" -> {
                String recordId = stringArg(args, "record_id", "");
                yield ticketStore.lookupRecord(recordId);
            }
            case "compute_sla" -> {
                String id = stringArg(args, "id", "");
                String priority = stringArg(args, "priority", "");
                double elapsed = doubleArg(args, "elapsed_hours", 0.0);
                String owner = stringArg(args, "owner", "");
                yield slaService.computeSla(id, priority, elapsed, owner);
            }
            default -> ToolResponse.fail("Tool sconosciuto: " + toolName);
        };

        return toJson(response);
    }

    public List<String> getToolNames() {
        return List.of("search_kb", "lookup_record", "compute_sla");
    }

    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"Errore serializzazione: " + e.getMessage() + "\"}";
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object v = args.get(key);
        return v != null ? v.toString() : fallback;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static double doubleArg(Map<String, Object> args, String key, double fallback) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }
}
