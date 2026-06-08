package com.genai.course.agents.model;

import java.util.Map;

public record ToolCall(
        String id,
        String name,
        Map<String, Object> args
) {
}
