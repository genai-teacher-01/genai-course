package com.genai.course.agents.service;

import com.genai.course.agents.model.SlaResult;
import com.genai.course.agents.model.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SlaService {

    private static final Map<String, Double> THRESHOLDS = Map.of(
            "P1", 0.5,
            "P2", 4.0,
            "P3", 24.0,
            "P4", 72.0
    );

    public ToolResponse computeSla(String id, String priority, double elapsedHours, String owner) {
        if (priority == null || !THRESHOLDS.containsKey(priority.toUpperCase())) {
            return ToolResponse.fail("Priorità non valida: " + priority + ". Valori ammessi: P1, P2, P3, P4.");
        }

        String prio = priority.toUpperCase();
        double threshold = THRESHOLDS.get(prio);
        double remaining = threshold - elapsedHours;

        String status;
        String recommendation;
        boolean critical;
        String reason;

        if (remaining < 0) {
            status = "violated";
            recommendation = "escalate";
            critical = true;
            reason = String.format(
                    "SLA %s violato: soglia %.1fh, trascorse %.2fh, superamento %.2fh. Escalation necessaria per %s.",
                    prio, threshold, elapsedHours, Math.abs(remaining), id);
        } else if (remaining <= threshold * 0.2) {
            status = "near_breach";
            if ("P1".equals(prio)) {
                recommendation = "prepare_escalation";
                critical = true;
                reason = String.format(
                        "SLA %s vicino alla violazione: soglia %.1fh, trascorse %.2fh, residuo %.2fh. " +
                        "P1 near-breach richiede preparazione escalation per %s.",
                        prio, threshold, elapsedHours, remaining, id);
            } else {
                recommendation = "prepare_escalation";
                critical = false;
                reason = String.format(
                        "SLA %s vicino alla violazione: soglia %.1fh, trascorse %.2fh, residuo %.2fh. " +
                        "Monitorare attentamente %s.",
                        prio, threshold, elapsedHours, remaining, id);
            }
        } else {
            status = "ok";
            recommendation = "continue";
            critical = false;
            reason = String.format(
                    "SLA %s nei limiti: soglia %.1fh, trascorse %.2fh, residuo %.2fh.",
                    prio, threshold, elapsedHours, remaining);
        }

        SlaResult result = new SlaResult(
                id, prio, threshold, elapsedHours, remaining, status, recommendation, critical, reason
        );

        return ToolResponse.ok(List.of(result), Map.of("ticket_id", id, "priority", prio));
    }
}
