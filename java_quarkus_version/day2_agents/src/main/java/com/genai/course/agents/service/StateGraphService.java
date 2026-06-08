package com.genai.course.agents.service;

import com.genai.course.agents.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Map;

/**
 * Costruisce e invoca il grafo di stato per l'agente ITSM.
 *
 * Corrisponde a build_graph() + invoke in itsm_graph_didactic.py.
 *
 * Grafo:
 *   llm -> (se risposta finale -> END)
 *       -> (se tool_calls -> tool_executor -> risk_check)
 *             risk_check -> (se azione critica -> human_review -> execute_action -> llm)
 *                        -> (altrimenti -> llm)
 */
@ApplicationScoped
public class StateGraphService {

    private static final int MAX_ITERATIONS = 25;
    private static final int MAX_TOOL_CALLS = 15;

    private static final String SYSTEM_PROMPT = """
            Sei un agente ITSM enterprise.

            Obiettivo:
            - aiutare l'operatore a leggere record ITSM, policy e stato SLA;
            - usare i tool quando servono dati operativi, documentazione o calcoli;
            - non inventare ticket, policy, owner o soglie SLA.

            Regole operative:
            1. Se la domanda cita un ticket come INC-1002, usa lookup_record.
            2. Se devi valutare SLA, usa compute_sla dopo aver recuperato il ticket.
            3. Se la domanda riguarda policy, ticket urgenti, P1 o escalation, usa search_kb.
            4. Le azioni critiche, come escalation formale o major incident, devono essere proposte e richiedono conferma umana.
            5. Rispondi in italiano, in modo operativo e sintetico.

            Formato consigliato della risposta finale:
            - Sintesi
            - Evidenze usate
            - Valutazione SLA, se presente
            - Raccomandazione
            - Conferma umana richiesta, se applicabile
            """;

    @Inject GeminiChatService chatService;
    @Inject ToolRegistry toolRegistry;
    @Inject CriticalActionService criticalActionService;

    public AgentState run(String prompt, String autoDecision) {
        String threadId = "graph-" + System.currentTimeMillis();
        AgentState state = AgentState.create(threadId, prompt, autoDecision);
        state.addTrace(TraceEvent.of(state.nextTraceStep(), "graph_start"));

        SimpleStateGraph<AgentState> graph = buildGraph();
        state = graph.invoke(state, MAX_ITERATIONS);

        state.addTrace(TraceEvent.of(state.nextTraceStep(), "graph_end"));
        return state;
    }

    private SimpleStateGraph<AgentState> buildGraph() {
        SimpleStateGraph<AgentState> g = new SimpleStateGraph<>();

        g.addNode("llm", this::llmNode);
        g.addNode("tool_executor", this::toolExecutorNode);
        g.addNode("risk_check", this::riskCheckNode);
        g.addNode("human_review", this::humanReviewNode);
        g.addNode("execute_action", this::executeActionNode);

        g.setEntryPoint("llm");

        g.addConditionalEdges("llm", this::routeAfterLlm);
        g.addConditionalEdges("tool_executor", s -> "risk_check");
        g.addConditionalEdges("risk_check", this::routeAfterRiskCheck);
        g.addConditionalEdges("human_review", this::routeAfterHumanReview);
        g.addEdge("execute_action", "llm");

        return g;
    }

    // --- Nodi ---

    private AgentState llmNode(AgentState state) {
        state.stepCount++;
        state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "node_enter", "llm"));

        chatService.invoke(state, SYSTEM_PROMPT);
        return state;
    }

    private AgentState toolExecutorNode(AgentState state) {
        state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "node_enter", "tool_executor"));

        for (ToolCall tc : new ArrayList<>(state.pendingToolCalls)) {
            if (state.toolCallCount >= MAX_TOOL_CALLS) {
                state.finalAnswer = "Stop: superato MAX_TOOL_CALLS=" + MAX_TOOL_CALLS + ".";
                state.addTrace(TraceEvent.ofError(state.nextTraceStep(), "max_tool_calls", state.finalAnswer));
                return state;
            }

            String rawResult;
            if (!toolRegistry.getToolNames().contains(tc.name())) {
                rawResult = toolRegistry.toJson(Map.of(
                        "success", false, "results", java.util.List.of(),
                        "error", "Tool sconosciuto: " + tc.name()));
            } else {
                try {
                    rawResult = toolRegistry.executeTool(tc.name(), tc.args());
                } catch (Exception e) {
                    rawResult = toolRegistry.toJson(Map.of(
                            "success", false, "results", java.util.List.of(),
                            "error", "Errore esecuzione tool " + tc.name() + ": " + e.getMessage()));
                }
            }

            state.toolCallCount++;
            state.messages.add(ChatMessage.toolResult(tc.name(), tc.id(), rawResult));
            state.scratchpad += "\n[tool:" + tc.name() + "] " + rawResult;
            state.artifacts.add(tc.name() + " result");

            updateStateFromToolResult(state, tc.name(), rawResult);

            state.addTrace(TraceEvent.ofTool(state.nextTraceStep(), "tool_call",
                    tc.name(), tc.args(),
                    rawResult.length() > 500 ? rawResult.substring(0, 500) + "..." : rawResult));
        }

        state.pendingToolCalls.clear();
        return state;
    }

    private AgentState riskCheckNode(AgentState state) {
        state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "node_enter", "risk_check"));

        if (state.ticket == null || state.sla == null) {
            state.riskLevel = "low";
            state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "risk_check",
                    "risk_check"));
            return state;
        }

        PendingAction action = criticalActionService.buildPendingAction(state.ticket, state.sla);

        if (action != null) {
            state.riskLevel = "high";
            state.pendingAction = action;
            state.approvalRequired = true;
            state.addTrace(TraceEvent.ofTool(state.nextTraceStep(), "critical_action_detected",
                    "risk_check", null, Map.of(
                            "action_type", action.actionType(),
                            "ticket_id", action.ticketId(),
                            "priority", action.priority())));
        } else {
            state.riskLevel = "low";
            state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "risk_check_low", "risk_check"));
        }

        return state;
    }

    private AgentState humanReviewNode(AgentState state) {
        state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "node_enter", "human_review"));

        String decision = state.autoDecision;

        if (decision == null) {
            state.approvalRequired = true;
            state.finalAnswer = "Serve approvazione umana per procedere con l'azione critica:\n" +
                    toolRegistry.toJson(state.pendingAction) + "\n\n" +
                    "Rilancia con autoDecision=approve oppure autoDecision=reject.";
            state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "human_approval_required", "human_review"));
            return state;
        }

        boolean approved = "approve".equalsIgnoreCase(decision);
        state.approved = approved;
        state.approvalRequired = false;

        state.addTrace(TraceEvent.ofTool(state.nextTraceStep(), "human_approval",
                "human_review", null, Map.of("decision", decision, "approved", approved)));

        return state;
    }

    private AgentState executeActionNode(AgentState state) {
        state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "node_enter", "execute_action"));

        Map<String, Object> execution = criticalActionService.executeCriticalAction(
                state.pendingAction, Boolean.TRUE.equals(state.approved));

        state.addTrace(TraceEvent.ofTool(state.nextTraceStep(), "critical_action_execution",
                "execute_action", null, execution));

        state.messages.add(ChatMessage.user(
                "Esito del controllo umano e dell'azione critica:\n" +
                toolRegistry.toJson(execution) + "\n\n" +
                "Ora produci una risposta finale per l'operatore. " +
                "Indica evidenze, stato SLA, azione approvata o non approvata."));

        return state;
    }

    // --- Routing ---

    private String routeAfterLlm(AgentState state) {
        if (state.finalAnswer != null && state.pendingToolCalls.isEmpty()) {
            return SimpleStateGraph.END;
        }
        if (!state.pendingToolCalls.isEmpty()) {
            return "tool_executor";
        }
        return SimpleStateGraph.END;
    }

    private String routeAfterRiskCheck(AgentState state) {
        if (state.pendingAction != null && state.approved == null) {
            return "human_review";
        }
        return "llm";
    }

    private String routeAfterHumanReview(AgentState state) {
        if (state.finalAnswer != null) {
            return SimpleStateGraph.END;
        }
        return "execute_action";
    }

    // --- Utility ---

    @SuppressWarnings("unchecked")
    private void updateStateFromToolResult(AgentState state, String toolName, String rawResult) {
        try {
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(rawResult, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            boolean success = Boolean.TRUE.equals(parsed.get("success"));
            java.util.List<Object> results = (java.util.List<Object>) parsed.getOrDefault("results", java.util.List.of());

            if (success && !results.isEmpty()) {
                if ("lookup_record".equals(toolName)) {
                    state.ticket = (Map<String, Object>) results.get(0);
                }
                if ("compute_sla".equals(toolName)) {
                    Map<String, Object> slaMap = (Map<String, Object>) results.get(0);
                    state.sla = new SlaResult(
                            (String) slaMap.get("id"),
                            (String) slaMap.get("priority"),
                            ((Number) slaMap.get("thresholdHours")).doubleValue(),
                            ((Number) slaMap.get("elapsedHours")).doubleValue(),
                            ((Number) slaMap.get("remainingHours")).doubleValue(),
                            (String) slaMap.get("status"),
                            (String) slaMap.get("recommendation"),
                            Boolean.TRUE.equals(slaMap.get("critical")),
                            (String) slaMap.get("reason")
                    );
                }
            }
        } catch (Exception ignored) {
        }
    }
}
