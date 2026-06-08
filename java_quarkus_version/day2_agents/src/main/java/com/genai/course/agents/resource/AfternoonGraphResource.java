package com.genai.course.agents.resource;

import com.genai.course.agents.model.AgentState;
import com.genai.course.agents.service.StateGraphService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Endpoint per gli esercizi del pomeriggio: State Graph con HITL.
 *
 * Corrisponde a itsm_graph_didactic.py nella versione Python.
 * Qui lo studente vede il grafo di stato con nodi, routing condizionale
 * e approvazione umana per azioni critiche.
 */
@Path("/api/afternoon")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AfternoonGraphResource {

    @Inject StateGraphService graphService;

    public record GraphRequest(String prompt, String autoDecision) {}

    public record GraphResponse(
            String threadId,
            String finalAnswer,
            int stepCount,
            int toolCallCount,
            String riskLevel,
            Object pendingAction,
            boolean approvalRequired,
            Boolean approved,
            Object sla,
            List<Object> traces
    ) {}

    @POST
    @Path("/run")
    public GraphResponse run(GraphRequest req) {
        AgentState state = graphService.run(req.prompt(), req.autoDecision());

        return new GraphResponse(
                state.threadId,
                state.finalAnswer,
                state.stepCount,
                state.toolCallCount,
                state.riskLevel,
                state.pendingAction,
                state.approvalRequired,
                state.approved,
                state.sla,
                List.copyOf(state.traces)
        );
    }

    @GET
    @Path("/examples")
    public List<Map<String, String>> examples() {
        return List.of(
                Map.of(
                        "title", "1. Record + SLA (grafo)",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Mostrami il record INC-1002 e calcola lo SLA.\\\"}\"",
                        "expected", "lookup_record -> compute_sla -> risk_check -> eventuale HITL"
                ),
                Map.of(
                        "title", "2. Ricerca policy (grafo)",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Cerco la policy per i ticket urgenti e per l'escalation P1.\\\"}\"",
                        "expected", "search_kb -> risposta con fonti"
                ),
                Map.of(
                        "title", "3. Record inesistente (grafo)",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Recupera INC-9999 e dimmi lo stato SLA.\\\"}\"",
                        "expected", "lookup_record -> gestione errore senza inventare dati"
                ),
                Map.of(
                        "title", "4. HITL approvato (grafo)",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Analizza INC-1002, calcola lo SLA e proponi l'escalation se serve.\\\",\\\"autoDecision\\\":\\\"approve\\\"}\"",
                        "expected", "lookup_record -> compute_sla -> human_review -> execute_action -> risposta finale"
                ),
                Map.of(
                        "title", "5. HITL rifiutato (grafo)",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Analizza INC-1002, calcola lo SLA e proponi l'escalation se serve.\\\",\\\"autoDecision\\\":\\\"reject\\\"}\"",
                        "expected", "lookup_record -> compute_sla -> human_review -> execute_action (non eseguita) -> risposta finale"
                ),
                Map.of(
                        "title", "6. HITL senza decisione (attesa umana)",
                        "curl", "curl -s -X POST http://localhost:8080/api/afternoon/run -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Analizza INC-1002, calcola lo SLA e proponi l'escalation se serve.\\\"}\"",
                        "expected", "lookup_record -> compute_sla -> human_review -> stop (attesa approvazione)"
                )
        );
    }
}
