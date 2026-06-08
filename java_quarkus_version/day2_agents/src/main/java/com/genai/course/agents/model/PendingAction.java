package com.genai.course.agents.model;

public record PendingAction(
        String actionType,
        String ticketId,
        String priority,
        String owner,
        boolean critical,
        String reason
) {
}
