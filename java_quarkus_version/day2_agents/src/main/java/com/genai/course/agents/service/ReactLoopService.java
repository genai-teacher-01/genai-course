package com.genai.course.agents.service;

import com.genai.course.agents.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Map;

/**
 * Loop ReAct manuale: Reason -> Act -> Observe -> ripeti.
 *
 * Corrisponde al ciclo while nel main() di itsm_agent.py.
 * Qui lo studente vede il pattern passo-passo prima di passare al grafo.
 */
@ApplicationScoped
public class ReactLoopService {

    private static final int MAX_STEPS = 10;
    private static final int MAX_TOOL_CALLS = 15;

    private static final String SYSTEM_PROMPT = """
            Sei un agente ITSM enterprise per un laboratorio di sviluppo software.

            Obiettivo:
            - aiutare l'operatore a interpretare policy ITSM, record operativi e SLA;
            - usare strumenti quando servono dati aggiornati, calcoli o documentazione;
            - distinguere chiaramente tra evidenza documentale, dato operativo e inferenza.

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

    public AgentState run(String prompt, String autoDecision) {
        String threadId = "react-" + System.currentTimeMillis();
        AgentState state = AgentState.create(threadId, prompt, autoDecision);
        state.addTrace(TraceEvent.of(state.nextTraceStep(), "react_loop_start"));

        for (int step = 0; step < MAX_STEPS; step++) {
            state.stepCount = step + 1;

            // === REASON ===
            state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "reason_start", "llm"));
            chatService.invoke(state, SYSTEM_PROMPT);

            if (state.finalAnswer != null && state.pendingToolCalls.isEmpty()) {
                state.addTrace(TraceEvent.ofNode(state.nextTraceStep(), "final_answer", "llm"));
                break;
            }

            // === ACT + OBSERVE ===
            if (!state.pendingToolCalls.isEmpty()) {
                executeToolCalls(state);
            }

            if (state.finalAnswer != null) {
                break;
            }
        }

        if (state.finalAnswer == null) {
            state.finalAnswer = "Stop: raggiunto limite massimo di " + MAX_STEPS + " step.";
            state.addTrace(TraceEvent.ofError(state.nextTraceStep(), "max_steps_reached", state.finalAnswer));
        }

        state.addTrace(TraceEvent.of(state.nextTraceStep(), "react_loop_end"));
        return state;
    }

    private void executeToolCalls(AgentState state) {
        for (ToolCall tc : new ArrayList<>(state.pendingToolCalls)) {
            if (state.toolCallCount >= MAX_TOOL_CALLS) {
                state.finalAnswer = "Stop: superato MAX_TOOL_CALLS=" + MAX_TOOL_CALLS + ".";
                state.addTrace(TraceEvent.ofError(state.nextTraceStep(), "max_tool_calls", state.finalAnswer));
                return;
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
                    tc.name(), tc.args(), rawResult.length() > 500 ? rawResult.substring(0, 500) + "..." : rawResult));
        }

        state.pendingToolCalls.clear();
    }

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
