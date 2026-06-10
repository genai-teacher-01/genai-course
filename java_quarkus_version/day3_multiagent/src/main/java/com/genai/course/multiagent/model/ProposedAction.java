package com.genai.course.multiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProposedAction(
        String actionType,
        String ticketId,
        String priority,
        String owner,
        boolean critical,
        String reason,
        String idempotencyKey,
        String status
) {
}
