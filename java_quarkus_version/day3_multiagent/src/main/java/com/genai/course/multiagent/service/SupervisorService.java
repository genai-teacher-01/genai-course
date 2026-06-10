package com.genai.course.multiagent.service;

import com.genai.course.multiagent.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Supervisor: orchestra triage, knowledge e action agent.
 *
 * Corrisponde a supervisor_node() + run_manual() in supervisor.py.
 * Routing deterministico:
 *   1. Nessun triage -> triage
 *   2. Serve knowledge e manca ticket/sla -> knowledge
 *   3. Serve action e non ci sono azioni -> action
 *   4. Altrimenti -> end (risposta finale)
 */
@ApplicationScoped
public class SupervisorService {

    private static final int MAX_HANDOFFS = 8;

    @Inject TriageAgentService triageAgent;
    @Inject KnowledgeAgentService knowledgeAgent;
    @Inject ActionAgentService actionAgent;
    @Inject McpAdapter mcpAdapter;

    public SupervisorState run(String prompt, boolean fastMode) {
        String taskId = "sup-" + System.currentTimeMillis();
        SupervisorState state = SupervisorState.create(taskId, prompt, fastMode);
        state.traces.add(TraceEvent.of(state.nextTraceStep(), "supervisor_start", "supervisor", Map.of()));

        int handoffCount = 0;

        while (handoffCount < MAX_HANDOFFS) {
            String nextNode = route(state);
            state.next = nextNode;

            state.handoffs.add(HandoffEvent.of(state.nextHandoffStep(),
                    "supervisor", nextNode, routeReason(state, nextNode)));
            state.traces.add(TraceEvent.of(state.nextTraceStep(), "route", "supervisor",
                    Map.of("next", nextNode)));

            handoffCount++;

            if ("end".equals(nextNode)) {
                state.finalAnswer = buildFinalAnswer(state);
                state.messages.add(ChatMessage.agent("supervisor", state.finalAnswer));
                break;
            }

            switch (nextNode) {
                case "triage" -> triageAgent.execute(state);
                case "knowledge" -> knowledgeAgent.execute(state);
                case "action" -> actionAgent.execute(state);
                default -> {
                    state.finalAnswer = "Routing sconosciuto: " + nextNode;
                    state.next = "end";
                }
            }
        }

        if (state.finalAnswer == null) {
            state.finalAnswer = "Max handoffs (" + MAX_HANDOFFS + ") raggiunti.";
            state.traces.add(TraceEvent.ofError(state.nextTraceStep(), "max_handoffs", "supervisor",
                    state.finalAnswer));
        }

        state.traces.add(TraceEvent.of(state.nextTraceStep(), "supervisor_end", "supervisor", Map.of()));
        return state;
    }

    private String route(SupervisorState state) {
        // RULE 1: No triage yet
        if (state.triage == null) return "triage";

        // RULE 2: Needs knowledge, missing context
        String userText = state.getLatestUserText();
        String mentionedTicket = TriageAgentService.extractTicketId(userText);
        boolean needsKnowledge = state.triage.needsKnowledge() || mentionedTicket != null;
        boolean missingContext = state.ticket == null || state.sla == null;

        if (needsKnowledge && missingContext && state.knowledgeAttempts < 2) return "knowledge";

        // RULE 3: Needs action, no actions yet, has ticket
        if (state.triage.needsAction() && state.actions.isEmpty()) {
            if (state.ticket != null && state.actionAttempts < 1) return "action";
        }

        // RULE 4: End
        return "end";
    }

    private String routeReason(SupervisorState state, String nextNode) {
        return switch (nextNode) {
            case "triage" -> "Nessun triage nello stato: classificare la richiesta.";
            case "knowledge" -> "Richiesta cita ticket o necessita evidenza operativa.";
            case "action" -> "Triage indica azione necessaria; contesto disponibile.";
            case "end" -> "Risposta finale pronta.";
            default -> "Routing: " + nextNode;
        };
    }

    private String buildFinalAnswer(SupervisorState state) {
        Map<String, Object> ticket = state.ticket;
        SlaResult sla = state.sla;

        if (ticket == null && state.citations.isEmpty()) {
            return "Non e' stato possibile recuperare il ticket richiesto. " +
                    "Nessuna azione operativa proposta senza record valido.";
        }

        StringBuilder sb = new StringBuilder();

        if (ticket == null && !state.citations.isEmpty()) {
            sb.append("**Risultati ricerca KB:**\n\n");
            for (var citation : state.citations) {
                sb.append("- **").append(citation.get("source")).append("**: ")
                  .append(citation.get("snippet")).append("\n");
            }
            sb.append("\n*Risposta basata sulla documentazione KB.*");
            return sb.toString();
        }

        String ticketId = String.valueOf(ticket.getOrDefault("id", "unknown"));
        sb.append("**Riepilogo operativo per ").append(ticketId).append(":**\n\n");
        sb.append("- Sommario: ").append(ticket.getOrDefault("summary", "n/d")).append("\n");
        sb.append("- Priorità: ").append(ticket.getOrDefault("priority", "n/d")).append("\n");
        sb.append("- Stato: ").append(ticket.getOrDefault("status", "n/d")).append("\n");
        sb.append("- Servizio: ").append(ticket.getOrDefault("service", "n/d")).append("\n");
        sb.append("- Owner: ").append(ticket.getOrDefault("owner", "n/d")).append("\n");

        Object impact = ticket.get("businessImpact");
        if (impact != null && !impact.toString().isBlank()) {
            sb.append("- Impatto business: ").append(impact).append("\n");
        }

        if (sla != null) {
            sb.append("\n**Valutazione SLA:**\n");
            sb.append("- Stato: ").append(sla.status()).append("\n");
            sb.append("- Trascorse: ").append(String.format("%.2fh", sla.elapsedHours())).append("\n");
            sb.append("- Soglia: ").append(String.format("%.1fh", sla.thresholdHours())).append("\n");
            sb.append("- Residuo: ").append(String.format("%.2fh", sla.remainingHours())).append("\n");
            sb.append("- Raccomandazione: ").append(sla.recommendation()).append("\n");
            if (sla.reason() != null) sb.append("- Motivazione: ").append(sla.reason()).append("\n");
        }

        if (!state.actions.isEmpty()) {
            sb.append("\n**Azione proposta:**\n");
            for (ProposedAction action : state.actions) {
                sb.append("- ").append(action.actionType())
                  .append(" per ").append(action.ticketId())
                  .append(" verso ").append(action.owner())
                  .append(" [status: ").append(action.status()).append("]\n");
            }
            sb.append("\n**Conferma umana richiesta: si.**\n");
            sb.append("Azione preparata come pendente, non eseguita.\n");
        } else {
            sb.append("\nNessuna azione operativa preparata.\n");
        }

        if (!state.citations.isEmpty()) {
            sb.append("\n**Fonti:**\n");
            for (var c : state.citations) {
                sb.append("- ").append(c.get("source")).append("\n");
            }
        }

        return sb.toString();
    }
}
