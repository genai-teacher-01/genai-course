package com.genai.course.agents.service;

import com.genai.course.agents.model.PendingAction;
import com.genai.course.agents.model.SlaResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
public class CriticalActionService {

    public PendingAction buildPendingAction(Map<String, Object> ticket, SlaResult sla) {
        if (sla == null || !sla.critical()) {
            return null;
        }

        String ticketId = sla.id();
        if (ticket != null && ticket.containsKey("id")) {
            ticketId = String.valueOf(ticket.get("id"));
        }

        String priority = sla.priority();
        if (ticket != null && ticket.containsKey("priority")) {
            priority = String.valueOf(ticket.get("priority"));
        }

        String owner = "unknown";
        if (ticket != null && ticket.containsKey("owner")) {
            owner = String.valueOf(ticket.get("owner"));
        }

        return new PendingAction(
                "open_formal_escalation",
                ticketId,
                priority,
                owner,
                true,
                sla.reason() != null ? sla.reason() : "Azione critica richiesta dalla policy SLA."
        );
    }

    public Map<String, Object> executeCriticalAction(PendingAction action, boolean approved) {
        if (action == null) {
            return Map.of(
                    "success", false,
                    "error", "Nessuna azione critica pendente."
            );
        }

        if (!approved) {
            return Map.of(
                    "success", true,
                    "executed", false,
                    "action", Map.of(
                            "action_type", action.actionType(),
                            "ticket_id", action.ticketId(),
                            "priority", action.priority()
                    ),
                    "message", "Azione critica NON eseguita: approvazione negata dall'operatore."
            );
        }

        return Map.of(
                "success", true,
                "executed", true,
                "action", Map.of(
                        "action_type", action.actionType(),
                        "ticket_id", action.ticketId(),
                        "priority", action.priority(),
                        "owner", action.owner()
                ),
                "message", String.format(
                        "Escalation formale aperta per %s (%s). Notifica inviata a %s. " +
                        "Motivo: %s",
                        action.ticketId(), action.priority(), action.owner(), action.reason())
        );
    }
}
