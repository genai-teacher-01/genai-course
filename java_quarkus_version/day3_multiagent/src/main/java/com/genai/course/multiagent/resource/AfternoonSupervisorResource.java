package com.genai.course.multiagent.resource;

import com.genai.course.multiagent.model.SupervisorState;
import com.genai.course.multiagent.service.SupervisorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Endpoint per gli esercizi del pomeriggio: Multi-Agent Supervisor.
 *
 * Corrisponde a supervisor.py nella versione Python.
 * Lo studente vede l'orchestrazione: supervisor -> triage -> knowledge -> action -> end.
 */
@Path("/api/afternoon")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AfternoonSupervisorResource {

    @Inject SupervisorService supervisorService;

    public record SupervisorRequest(String prompt) {}

    public record SupervisorResponse(
            String taskId,
            String finalAnswer,
            Object triage,
            Object ticket,
            Object sla,
            List<Object> actions,
            List<Object> handoffs,
            List<Object> citations,
            List<Object> traces,
            String next
    ) {}

    @POST
    @Path("/run")
    public SupervisorResponse run(SupervisorRequest req) {
        SupervisorState state = supervisorService.run(req.prompt(), true);

        return new SupervisorResponse(
                state.taskId,
                state.finalAnswer,
                state.triage,
                state.ticket,
                state.sla,
                List.copyOf(state.actions),
                List.copyOf(state.handoffs),
                List.copyOf(state.citations),
                List.copyOf(state.traces),
                state.next
        );
    }

    @GET
    @Path("/examples")
    public List<Map<String, String>> examples() {
        return List.of(
                Map.of("title", "1. Multi-agent classico: investigation + escalation",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Mostrami INC-1002, calcola lo SLA e prepara l'escalation se serve.\\\"}\"",
                        "expected", "triage -> knowledge (lookup+sla) -> action (escalation) -> end"),
                Map.of("title", "2. Solo knowledge: policy",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Quando va escalato un ticket P1 al team on-call?\\\"}\"",
                        "expected", "triage -> knowledge (search_kb) -> end"),
                Map.of("title", "3. Near-breach P2",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Controlla INC-1003: e' vicino alla violazione SLA?\\\"}\"",
                        "expected", "triage -> knowledge (lookup+sla) -> end (no action: near_breach P2 non critico)"),
                Map.of("title", "4. Errore controllato: record inesistente",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Recupera INC-9999, calcola SLA e proponi azione.\\\"}\"",
                        "expected", "triage -> knowledge (lookup fail) -> end (nessuna azione senza dati)"),
                Map.of("title", "5. Azione esplicita",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Apri escalation formale per INC-1002 verso team-sre con motivo SLA P1.\\\"}\"",
                        "expected", "triage (action_request) -> knowledge (lookup+sla) -> action (escalation) -> end"),
                Map.of("title", "6. Ticket senza rischio",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Mostrami INC-1004 e calcola lo SLA.\\\"}\"",
                        "expected", "triage -> knowledge -> end (SLA ok, nessuna azione)")
        );
    }
}
