package com.genai.course.multiagent.service;

import com.genai.course.multiagent.model.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * ActionAgent: prepara e (opzionalmente) esegue azioni critiche.
 *
 * Corrisponde a action_agent() in supervisor.py.
 * Opera solo su ticket+SLA dal KnowledgeAgent; non inventa contesto.
 */
@ApplicationScoped
public class ActionAgentService {

    public void execute(SupervisorState state) {
        Map<String, Object> ticket = state.ticket;
        SlaResult sla = state.sla;

        if (ticket == null) {
            state.messages.add(ChatMessage.agent("action",
                    "ActionAgent: nessun ticket nello stato; impossibile proporre azione. RITORNO AL SUPERVISOR"));
            state.traces.add(TraceEvent.of(state.nextTraceStep(), "action_no_ticket", "action", Map.of()));
            state.actionAttempts++;
            return;
        }

        String ticketId = String.valueOf(ticket.getOrDefault("id", ticket.getOrDefault("key", "unknown")));
        String priority = String.valueOf(ticket.getOrDefault("priority", ""));
        String owner = String.valueOf(ticket.getOrDefault("owner", "unknown"));
        String recommendation = sla != null ? sla.recommendation() : "";
        String slaStatus = sla != null ? sla.status() : "";

        boolean shouldPropose = "escalate".equals(recommendation)
                || "violated".equals(slaStatus)
                || "near_breach".equals(slaStatus)
                || (state.triage != null && state.triage.needsAction());

        if (shouldPropose) {
            String reason = sla != null && sla.reason() != null ? sla.reason() : "Azione richiesta o SLA critico.";
            ProposedAction action = new ProposedAction(
                    "open_formal_escalation", ticketId, priority, owner, true,
                    reason, ticketId + ":open_formal_escalation", "pending_approval");
            state.actions.add(action);
            state.messages.add(ChatMessage.agent("action",
                    "ActionAgent: preparata escalation per " + ticketId + ". Status: pending_approval. RITORNO AL SUPERVISOR"));
            state.traces.add(TraceEvent.of(state.nextTraceStep(), "action_proposal", "action",
                    Map.of("action_type", action.actionType(), "ticket_id", ticketId, "priority", priority)));
        } else {
            state.messages.add(ChatMessage.agent("action",
                    "ActionAgent: nessuna escalation necessaria per " + ticketId + ". RITORNO AL SUPERVISOR"));
            state.traces.add(TraceEvent.of(state.nextTraceStep(), "action_not_needed", "action",
                    Map.of("ticket_id", ticketId, "sla_status", slaStatus)));
        }

        state.actionAttempts++;
    }
}
