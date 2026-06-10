package com.genai.course.multiagent.service;

import com.genai.course.multiagent.model.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TriageAgent: classifica la richiesta per dominio, intento, priorità.
 *
 * Corrisponde a triage_agent() in supervisor.py.
 * Non usa tool: solo classificazione deterministica (mock)
 * o structured output Gemini (gemini_free).
 */
@ApplicationScoped
public class TriageAgentService {

    private static final Pattern TICKET_RE = Pattern.compile("\\bINC-\\d+\\b", Pattern.CASE_INSENSITIVE);

    private static final List<String> ACTION_KEYWORDS = List.of(
            "proponi azione", "apri", "esegui", "escala", "escalation", "chiudi", "notifica");
    private static final List<String> KNOWLEDGE_KEYWORDS = List.of(
            "mostrami", "controlla", "verifica", "sla", "policy", "procedura", "quando");

    public void execute(SupervisorState state) {
        String userText = state.getLatestUserText();
        TriageDecision decision = fallbackTriageDecision(userText);

        state.triage = decision;
        state.messages.add(ChatMessage.agent("triage",
                "Triage completato: " + state.triage.intent() + " / " + state.triage.priorityGuess()));
        state.traces.add(TraceEvent.of(state.nextTraceStep(), "triage_decision", "triage",
                Map.of("domain", decision.domain(), "intent", decision.intent(),
                        "priority", decision.priorityGuess(),
                        "needs_knowledge", decision.needsKnowledge(),
                        "needs_action", decision.needsAction())));
    }

    public TriageDecision fallbackTriageDecision(String userText) {
        String lowered = userText.toLowerCase();
        boolean hasTicket = TICKET_RE.matcher(userText).find();

        boolean needsAction = ACTION_KEYWORDS.stream().anyMatch(lowered::contains);
        boolean needsKnowledge = hasTicket || KNOWLEDGE_KEYWORDS.stream().anyMatch(lowered::contains);

        String intent;
        if (hasTicket) intent = "investigation";
        else if (needsAction) intent = "action_request";
        else intent = "question";

        String priorityGuess = (lowered.contains("p1") || hasTicket) ? "P1" : "P3";

        return new TriageDecision(
                "itsm", intent, priorityGuess, needsKnowledge, needsAction,
                "Classificazione deterministica: " +
                (hasTicket ? "ticket ID trovato" : "basata su keyword operativi") + ".");
    }

    public static String extractTicketId(String text) {
        if (text == null) return null;
        Matcher m = TICKET_RE.matcher(text);
        return m.find() ? m.group(0).toUpperCase() : null;
    }
}
