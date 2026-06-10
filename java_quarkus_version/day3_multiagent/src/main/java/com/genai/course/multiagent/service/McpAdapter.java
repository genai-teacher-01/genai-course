package com.genai.course.multiagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genai.course.multiagent.model.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/**
 * MCP Adapter in-process: simula un client MCP senza JSON-RPC/subprocess.
 *
 * Corrisponde a MCPAdapter nella versione Python (mcp_lab.py).
 * Registra tool con schema JSON e dispatch dell'esecuzione.
 * Lo studente vede il pattern MCP: discovery -> schema -> call.
 */
@ApplicationScoped
public class McpAdapter {

    @Inject KnowledgeBaseService kbService;
    @Inject TicketStoreService ticketStore;
    @Inject SlaService slaService;
    @Inject ObjectMapper objectMapper;

    public List<Map<String, Object>> listTools() {
        return List.of(
                Map.of("name", "search_kb",
                        "description", "Cerca policy, procedure, SLA, escalation e knowledge article nella KB ITSM.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description", "Domanda dell'utente in linguaggio naturale."),
                                        "top_k", Map.of("type", "integer", "description", "Numero massimo di risultati (default 3).", "minimum", 1, "maximum", 10, "default", 3)),
                                "required", List.of("query"))),
                Map.of("name", "lookup_record",
                        "description", "Recupera record operativo ITSM/Jira-like dato id (es. INC-1002).",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "record_id", Map.of("type", "string", "description", "Identificativo del ticket (es. INC-1002).")),
                                "required", List.of("record_id"))),
                Map.of("name", "compute_sla",
                        "description", "Calcola stato SLA e raccomandazione operativa per ticket ITSM.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "ID del ticket."),
                                        "priority", Map.of("type", "string", "description", "Priorità: P1, P2, P3 o P4."),
                                        "elapsed_hours", Map.of("type", "number", "description", "Ore trascorse dall'apertura."),
                                        "owner", Map.of("type", "string", "description", "Team owner.", "default", ""),
                                        "service", Map.of("type", "string", "description", "Nome servizio.", "default", "unknown"),
                                        "workaround_available", Map.of("type", "boolean", "description", "Workaround disponibile.", "default", false)),
                                "required", List.of("id", "priority", "elapsed_hours")))
        );
    }

    public Map<String, Object> getTool(String name) {
        return listTools().stream()
                .filter(t -> name.equals(t.get("name")))
                .findFirst().orElse(null);
    }

    public String callTool(String name, Map<String, Object> args) {
        ToolResponse response = switch (name) {
            case "search_kb" -> {
                String query = str(args, "query", "");
                int topK = intVal(args, "top_k", 3);
                yield kbService.searchKb(query, topK);
            }
            case "lookup_record" -> {
                String recordId = str(args, "record_id", "");
                yield ticketStore.lookupRecord(recordId);
            }
            case "compute_sla" -> {
                String id = str(args, "id", "");
                String priority = str(args, "priority", "");
                double elapsed = dblVal(args, "elapsed_hours", 0.0);
                String owner = str(args, "owner", "");
                yield slaService.computeSla(id, priority, elapsed, owner);
            }
            default -> ToolResponse.fail("Tool sconosciuto: " + name);
        };
        return toJson(response);
    }

    public List<Map<String, Object>> getGeminiToolDeclarations() {
        List<Map<String, Object>> decls = new ArrayList<>();
        for (var tool : listTools()) {
            Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
            Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            Map<String, Object> geminiProps = new LinkedHashMap<>();
            for (var entry : props.entrySet()) {
                Map<String, Object> propSpec = (Map<String, Object>) entry.getValue();
                String type = ((String) propSpec.getOrDefault("type", "string")).toUpperCase();
                if ("INTEGER".equals(type)) type = "INTEGER";
                else if ("NUMBER".equals(type)) type = "NUMBER";
                else if ("BOOLEAN".equals(type)) type = "BOOLEAN";
                else type = "STRING";
                Map<String, Object> geminiProp = new LinkedHashMap<>();
                geminiProp.put("type", type);
                if (propSpec.containsKey("description")) geminiProp.put("description", propSpec.get("description"));
                geminiProps.put(entry.getKey(), geminiProp);
            }
            decls.add(Map.of(
                    "name", tool.get("name"),
                    "description", tool.get("description"),
                    "parameters", Map.of(
                            "type", "OBJECT",
                            "properties", geminiProps,
                            "required", schema.getOrDefault("required", List.of()))));
        }
        return decls;
    }

    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"Errore serializzazione: " + e.getMessage() + "\"}";
        }
    }

    private static String str(Map<String, Object> args, String key, String fallback) {
        Object v = args.get(key); return v != null ? v.toString() : fallback;
    }
    private static int intVal(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; } }
        return fallback;
    }
    private static double dblVal(Map<String, Object> args, String key, double fallback) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; } }
        return fallback;
    }
}
