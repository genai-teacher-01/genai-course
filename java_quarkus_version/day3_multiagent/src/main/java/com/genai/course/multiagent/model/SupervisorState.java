package com.genai.course.multiagent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stato condiviso del sistema multi-agente.
 *
 * Corrisponde a AgentState(TypedDict) in supervisor.py.
 * Il supervisor e i sotto-agenti leggono/scrivono questo stato.
 */
public class SupervisorState {
    public List<ChatMessage> messages = new ArrayList<>();
    public String taskId;

    public TriageDecision triage;
    public Map<String, Object> ticket;
    public SlaResult sla;

    public List<Map<String, Object>> citations = new ArrayList<>();
    public List<ProposedAction> actions = new ArrayList<>();
    public List<HandoffEvent> handoffs = new ArrayList<>();
    public List<TraceEvent> traces = new ArrayList<>();

    public String next;

    public int tokensIn;
    public int tokensOut;

    public int knowledgeAttempts;
    public int actionAttempts;

    public boolean fastMode;
    public String finalAnswer;

    public int nextTraceStep() {
        return traces.size() + 1;
    }

    public int nextHandoffStep() {
        return handoffs.size() + 1;
    }

    public String getLatestUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.role()) && m.text() != null) {
                return m.text();
            }
        }
        return "";
    }

    public static SupervisorState create(String taskId, String prompt, boolean fastMode) {
        SupervisorState s = new SupervisorState();
        s.taskId = taskId;
        s.next = "triage";
        s.fastMode = fastMode;
        s.messages.add(ChatMessage.user(prompt));
        return s;
    }
}
