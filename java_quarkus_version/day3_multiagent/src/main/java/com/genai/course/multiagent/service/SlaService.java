package com.genai.course.multiagent.service;

import com.genai.course.multiagent.model.SlaResult;
import com.genai.course.multiagent.model.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SlaService {

    private static final Map<String, Double> THRESHOLDS = Map.of(
            "P1", 0.5, "P2", 4.0, "P3", 24.0, "P4", 72.0);

    public ToolResponse computeSla(String id, String priority, double elapsedHours, String owner) {
        if (priority == null || !THRESHOLDS.containsKey(priority.toUpperCase()))
            return ToolResponse.fail("Priorità non valida: " + priority);
        String prio = priority.toUpperCase();
        double threshold = THRESHOLDS.get(prio);
        double remaining = threshold - elapsedHours;

        String status, recommendation, reason;
        boolean critical;

        if (remaining < 0) {
            status = "violated"; recommendation = "escalate"; critical = true;
            reason = String.format("SLA %s violato: soglia %.1fh, trascorse %.2fh, superamento %.2fh.", prio, threshold, elapsedHours, Math.abs(remaining));
        } else if (remaining <= threshold * 0.2) {
            status = "near_breach";
            recommendation = "prepare_escalation";
            critical = "P1".equals(prio);
            reason = String.format("SLA %s vicino alla violazione: soglia %.1fh, trascorse %.2fh, residuo %.2fh.", prio, threshold, elapsedHours, remaining);
        } else {
            status = "ok"; recommendation = "continue"; critical = false;
            reason = String.format("SLA %s nei limiti: soglia %.1fh, trascorse %.2fh, residuo %.2fh.", prio, threshold, elapsedHours, remaining);
        }

        return ToolResponse.ok(List.of(new SlaResult(id, prio, threshold, elapsedHours, remaining, status, recommendation, critical, reason)),
                Map.of("ticket_id", id, "priority", prio));
    }
}
