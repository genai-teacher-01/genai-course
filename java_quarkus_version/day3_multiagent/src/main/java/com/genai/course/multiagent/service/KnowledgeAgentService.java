package com.genai.course.multiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genai.course.multiagent.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * KnowledgeAgent: recupera dati operativi, policy, calcolo SLA.
 *
 * Corrisponde a knowledge_agent() in supervisor.py.
 * Usa i tool kb_search, kb_lookup_record, kb_compute_sla
 * tramite McpAdapter.
 */
@ApplicationScoped
public class KnowledgeAgentService {

    @Inject McpAdapter mcpAdapter;
    @Inject ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public void execute(SupervisorState state) {
        String userText = state.getLatestUserText();
        String ticketId = TriageAgentService.extractTicketId(userText);

        if (ticketId == null) {
            boolean hasKbQuery = userText.toLowerCase().matches(".*(policy|sla|escalation|procedura|on-call|knowledge).*");
            if (hasKbQuery) {
                String result = mcpAdapter.callTool("search_kb", Map.of("query", userText, "top_k", 3));
                extractCitations(state, result);
                state.messages.add(ChatMessage.agent("knowledge",
                        "KnowledgeAgent: ricerca KB completata. RITORNO AL SUPERVISOR"));
                state.traces.add(TraceEvent.of(state.nextTraceStep(), "knowledge_kb_search", "knowledge",
                        Map.of("query", userText)));
            } else {
                state.messages.add(ChatMessage.agent("knowledge",
                        "KnowledgeAgent: nessun ticket ID nella richiesta. RITORNO AL SUPERVISOR"));
                state.traces.add(TraceEvent.of(state.nextTraceStep(), "knowledge_no_ticket", "knowledge", Map.of()));
            }
            state.knowledgeAttempts++;
            return;
        }

        String lookupResult = mcpAdapter.callTool("lookup_record", Map.of("record_id", ticketId));
        state.traces.add(TraceEvent.of(state.nextTraceStep(), "knowledge_tool_call", "knowledge",
                Map.of("tool", "lookup_record", "record_id", ticketId)));

        try {
            Map<String, Object> parsed = objectMapper.readValue(lookupResult, new TypeReference<>() {});
            if (Boolean.TRUE.equals(parsed.get("success"))) {
                List<Object> results = (List<Object>) parsed.getOrDefault("results", List.of());
                if (!results.isEmpty()) {
                    state.ticket = (Map<String, Object>) results.get(0);
                }
            } else {
                state.messages.add(ChatMessage.agent("knowledge",
                        "KnowledgeAgent: ticket " + ticketId + " non trovato. RITORNO AL SUPERVISOR"));
                state.traces.add(TraceEvent.of(state.nextTraceStep(), "knowledge_ticket_not_found", "knowledge",
                        Map.of("record_id", ticketId)));
                state.knowledgeAttempts++;
                return;
            }
        } catch (Exception e) {
            state.traces.add(TraceEvent.ofError(state.nextTraceStep(), "knowledge_parse_error", "knowledge", e.getMessage()));
            state.knowledgeAttempts++;
            return;
        }

        if (state.ticket != null) {
            String priority = String.valueOf(state.ticket.getOrDefault("priority", ""));
            double elapsed = toDouble(state.ticket.get("elapsedHours"));
            String owner = String.valueOf(state.ticket.getOrDefault("owner", ""));
            String service = String.valueOf(state.ticket.getOrDefault("service", "unknown"));

            String slaResult = mcpAdapter.callTool("compute_sla", Map.of(
                    "id", ticketId, "priority", priority, "elapsed_hours", elapsed, "owner", owner));

            state.traces.add(TraceEvent.of(state.nextTraceStep(), "knowledge_tool_call", "knowledge",
                    Map.of("tool", "compute_sla", "ticket_id", ticketId)));

            try {
                Map<String, Object> slaParsed = objectMapper.readValue(slaResult, new TypeReference<>() {});
                if (Boolean.TRUE.equals(slaParsed.get("success"))) {
                    List<Object> results = (List<Object>) slaParsed.getOrDefault("results", List.of());
                    if (!results.isEmpty()) {
                        Map<String, Object> slaMap = (Map<String, Object>) results.get(0);
                        state.sla = new SlaResult(
                                (String) slaMap.get("id"), (String) slaMap.get("priority"),
                                ((Number) slaMap.get("thresholdHours")).doubleValue(),
                                ((Number) slaMap.get("elapsedHours")).doubleValue(),
                                ((Number) slaMap.get("remainingHours")).doubleValue(),
                                (String) slaMap.get("status"), (String) slaMap.get("recommendation"),
                                Boolean.TRUE.equals(slaMap.get("critical")), (String) slaMap.get("reason"));
                    }
                }
            } catch (Exception e) {
                state.traces.add(TraceEvent.ofError(state.nextTraceStep(), "knowledge_sla_parse_error", "knowledge", e.getMessage()));
            }
        }

        boolean hasKbKeywords = userText.toLowerCase().matches(".*(policy|sla|escalation|procedura|on-call|knowledge|p1|p2).*");
        if (hasKbKeywords) {
            String kbResult = mcpAdapter.callTool("search_kb", Map.of("query", userText, "top_k", 3));
            extractCitations(state, kbResult);
            state.traces.add(TraceEvent.of(state.nextTraceStep(), "knowledge_kb_search", "knowledge",
                    Map.of("query", userText)));
        }

        state.messages.add(ChatMessage.agent("knowledge",
                "KnowledgeAgent: recuperato " + ticketId + " e calcolato SLA. RITORNO AL SUPERVISOR"));
        state.knowledgeAttempts++;
    }

    @SuppressWarnings("unchecked")
    private void extractCitations(SupervisorState state, String rawResult) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawResult, new TypeReference<>() {});
            if (Boolean.TRUE.equals(parsed.get("success"))) {
                List<Object> results = (List<Object>) parsed.getOrDefault("results", List.of());
                for (Object hit : results) {
                    Map<String, Object> hitMap = (Map<String, Object>) hit;
                    String snippet = String.valueOf(hitMap.getOrDefault("snippet", ""));
                    if (snippet.length() > 240) snippet = snippet.substring(0, 240);
                    state.citations.add(Map.of(
                            "source", String.valueOf(hitMap.getOrDefault("source", "")),
                            "snippet", snippet));
                }
            }
        } catch (Exception ignored) {}
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
        return 0;
    }
}
