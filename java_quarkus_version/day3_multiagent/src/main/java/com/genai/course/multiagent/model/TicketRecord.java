package com.genai.course.multiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketRecord(
        String id,
        String key,
        String recordType,
        String summary,
        String description,
        String priority,
        String status,
        String service,
        String environment,
        String component,
        double elapsedHours,
        String owner,
        String assignee,
        String reporter,
        int affectedUsers,
        String businessImpact,
        boolean workaroundAvailable,
        List<String> labels,
        List<String> linkedRecords,
        List<Comment> comments
) {
    public record Comment(String author, String text, String createdAt) {}
}
