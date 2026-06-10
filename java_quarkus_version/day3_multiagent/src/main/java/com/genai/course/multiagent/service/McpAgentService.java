package com.genai.course.multiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genai.course.multiagent.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agente singolo MCP: usa l'adapter per chiamare tool in modo deterministico.
 *
 * Corrisponde a run_agent_fast() in mcp_lab.py.
 * Lo studente vede il pattern: discovery -> call -> compose.
 */
@ApplicationScoped
public class McpAgentService {

    private static final Pattern TICKET_RE = Pattern.compile("\\bINC-\\d+\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> KB_KEYWORDS = List.of(
            "policy", "sla", "escalation", "p1", "p2", "procedura", "regola", "on-call", "runbook", "knowledge");

    @Inject McpAdapter mcpAdapter;
    @Inject ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public McpAgentResult run(String query) {
        String taskId = "mcp-" + System.currentTimeMillis();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        List<TraceEvent> traces = new ArrayList<>();
        int step = 0;

        traces.add(TraceEvent.of(++step, "mcp_agent_start", "mcp_agent", Map.of("query", query)));

        StringBuilder answer = new StringBuilder();
        String ticketId = extractTicketId(query);
        Map<String, Object> ticket = null;
        Map<String, Object> sla = null;

        // Step 1: Lookup ticket if mentioned
        if (ticketId != null) {
            String result = mcpAdapter.callTool("lookup_record", Map.of("record_id", ticketId));
            toolCalls.add(Map.of("tool", "lookup_record", "args", Map.of("record_id", ticketId)));
            traces.add(TraceEvent.of(++step, "tool_call", "mcp_agent",
                    Map.of("tool", "lookup_record", "record_id", ticketId)));

            try {
                Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
                if (Boolean.TRUE.equals(parsed.get("success"))) {
                    List<Object> results = (List<Object>) parsed.getOrDefault("results", List.of());
                    if (!results.isEmpty()) {
                        ticket = (Map<String, Object>) results.get(0);
                        answer.append("**Ticket ").append(ticketId).append(":**\n");
                        answer.append("- Sommario: ").append(ticket.getOrDefault("summary", "n/d")).append("\n");
                        answer.append("- Priorità: ").append(ticket.getOrDefault("priority", "n/d")).append("\n");
                        answer.append("- Stato: ").append(ticket.getOrDefault("status", "n/d")).append("\n");
                        answer.append("- Owner: ").append(ticket.getOrDefault("owner", "n/d")).append("\n");
                        answer.append("- Servizio: ").append(ticket.getOrDefault("service", "n/d")).append("\n\n");
                    }
                } else {
                    answer.append("Ticket ").append(ticketId).append(" non trovato.\n\n");
                }
            } catch (Exception e) {
                answer.append("Errore lettura ticket: ").append(e.getMessage()).append("\n\n");
            }
        }

        // Step 2: Compute SLA if ticket exists
        if (ticket != null) {
            String priority = String.valueOf(ticket.getOrDefault("priority", ""));
            double elapsed = toDouble(ticket.get("elapsedHours"));
            String owner = String.valueOf(ticket.getOrDefault("owner", ""));

            String result = mcpAdapter.callTool("compute_sla", Map.of(
                    "id", ticketId, "priority", priority, "elapsed_hours", elapsed, "owner", owner));
            toolCalls.add(Map.of("tool", "compute_sla", "args",
                    Map.of("id", ticketId, "priority", priority, "elapsed_hours", elapsed)));
            traces.add(TraceEvent.of(++step, "tool_call", "mcp_agent",
                    Map.of("tool", "compute_sla", "ticket_id", ticketId)));

            try {
                Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
                if (Boolean.TRUE.equals(parsed.get("success"))) {
                    List<Object> results = (List<Object>) parsed.getOrDefault("results", List.of());
                    if (!results.isEmpty()) {
                        sla = (Map<String, Object>) results.get(0);
                        answer.append("**SLA:**\n");
                        answer.append("- Stato: ").append(sla.getOrDefault("status", "n/d")).append("\n");
                        answer.append("- Soglia: ").append(sla.getOrDefault("thresholdHours", "n/d")).append("h\n");
                        answer.append("- Residuo: ").append(sla.getOrDefault("remainingHours", "n/d")).append("h\n");
                        answer.append("- Raccomandazione: ").append(sla.getOrDefault("recommendation", "n/d")).append("\n\n");
                    }
                }
            } catch (Exception ignored) {}
        }

        // Step 3: Search KB if query has relevant keywords
        String queryLower = query.toLowerCase();
        boolean hasKbKeywords = KB_KEYWORDS.stream().anyMatch(queryLower::contains);
        if (hasKbKeywords) {
            String result = mcpAdapter.callTool("search_kb", Map.of("query", query, "top_k", 3));
            toolCalls.add(Map.of("tool", "search_kb", "args", Map.of("query", query, "top_k", 3)));
            traces.add(TraceEvent.of(++step, "tool_call", "mcp_agent",
                    Map.of("tool", "search_kb", "query", query)));

            try {
                Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
                if (Boolean.TRUE.equals(parsed.get("success"))) {
                    List<Object> results = (List<Object>) parsed.getOrDefault("results", List.of());
                    if (!results.isEmpty()) {
                        answer.append("**Risultati KB:**\n");
                        for (Object hit : results) {
                            Map<String, Object> hitMap = (Map<String, Object>) hit;
                            String source = String.valueOf(hitMap.getOrDefault("source", ""));
                            String snippet = String.valueOf(hitMap.getOrDefault("snippet", ""));
                            if (snippet.length() > 240) snippet = snippet.substring(0, 240) + "...";
                            answer.append("- **").append(source).append("**: ").append(snippet).append("\n");
                            citations.add(Map.of("source", source, "snippet", snippet));
                        }
                        answer.append("\n");
                    }
                }
            } catch (Exception ignored) {}
        }

        if (answer.isEmpty()) {
            answer.append("Nessun tool attivato per questa query. ");
            answer.append("Prova con un ID ticket (es. INC-1002) o keyword (policy, escalation, SLA).");
        }

        traces.add(TraceEvent.of(++step, "mcp_agent_end", "mcp_agent", Map.of()));

        return new McpAgentResult(taskId, answer.toString(), toolCalls, citations, traces);
    }

    private static String extractTicketId(String text) {
        if (text == null) return null;
        Matcher m = TICKET_RE.matcher(text);
        return m.find() ? m.group(0).toUpperCase() : null;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
        return 0;
    }

    public record McpAgentResult(
            String taskId,
            String answer,
            List<Map<String, Object>> toolCalls,
            List<Map<String, Object>> citations,
            List<TraceEvent> traces
    ) {}
}
