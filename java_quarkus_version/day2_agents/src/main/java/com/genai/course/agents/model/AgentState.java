package com.genai.course.agents.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stato condiviso dell'agente, mutabile.
 * Corrisponde a AgentState(TypedDict) nella versione Python.
 *
 * In LangGraph questo diventa lo schema dello stato del grafo.
 * Qui lo usiamo gia' cosi' gli studenti vedono il pattern.
 *
 * Nota didattica:
 *   Lo stato e' il taccuino dell'agente.
 *   Ogni nodo legge il taccuino, scrive qualcosa e poi il grafo decide il prossimo nodo.
 */
public class AgentState {
    public List<ChatMessage> messages = new ArrayList<>();
    public String scratchpad = "";
    public List<String> artifacts = new ArrayList<>();

    public List<ToolCall> pendingToolCalls = new ArrayList<>();
    public String finalAnswer;

    public String threadId;
    public long startedAtMs;
    public long lastLlmCallMs;
    public int stepCount;
    public int toolCallCount;

    public Map<String, Object> ticket;
    public SlaResult sla;

    public String riskLevel = "low";
    public PendingAction pendingAction;
    public boolean approvalRequired;
    public Boolean approved;
    public String autoDecision;

    public List<TraceEvent> traces = new ArrayList<>();

    public void addTrace(TraceEvent event) {
        traces.add(event);
    }

    public int nextTraceStep() {
        return traces.size() + 1;
    }

    public static AgentState create(String threadId, String prompt, String autoDecision) {
        AgentState state = new AgentState();
        state.threadId = threadId;
        state.startedAtMs = System.currentTimeMillis();
        state.autoDecision = autoDecision;
        state.messages.add(ChatMessage.user(prompt));
        return state;
    }
}
