package com.genai.course.multiagent.model;

public record SlaResult(
        String id,
        String priority,
        double thresholdHours,
        double elapsedHours,
        double remainingHours,
        String status,
        String recommendation,
        boolean critical,
        String reason
) {
}
