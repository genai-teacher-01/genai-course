package com.genai.course.agents.resource;

import com.genai.course.agents.model.AgentState;
import com.genai.course.agents.model.ToolResponse;
import com.genai.course.agents.service.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Endpoint per gli esercizi del mattino: tool manuali + loop ReAct.
 *
 * Corrisponde alla CLI di itsm_agent.py nella versione Python.
 * Qui lo studente esplora i singoli tool e poi il loop ReAct completo.
 */
@Path("/api/morning")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MorningAgentResource {

    @Inject KnowledgeBaseService kbService;
    @Inject TicketStoreService ticketStore;
    @Inject SlaService slaService;
    @Inject ReactLoopService reactLoop;

    // --- Esercizio 1: Tool singoli ---

    public record SearchKbRequest(String query, Integer topK) {}

    @POST
    @Path("/tools/search-kb")
    public ToolResponse searchKb(SearchKbRequest req) {
        int topK = req.topK() != null ? req.topK() : 3;
        return kbService.searchKb(req.query(), topK);
    }

    public record LookupRequest(String recordId) {}

    @POST
    @Path("/tools/lookup-record")
    public ToolResponse lookupRecord(LookupRequest req) {
        return ticketStore.lookupRecord(req.recordId());
    }

    public record SlaRequest(String id, String priority, Double elapsedHours, String owner) {}

    @POST
    @Path("/tools/compute-sla")
    public ToolResponse computeSla(SlaRequest req) {
        double elapsed = req.elapsedHours() != null ? req.elapsedHours() : 0.0;
        String owner = req.owner() != null ? req.owner() : "";
        return slaService.computeSla(req.id(), req.priority(), elapsed, owner);
    }

    // --- Esercizio 2: Loop ReAct manuale ---

    public record AgentRequest(String prompt, String autoDecision) {}

    public record AgentResponse(
            String threadId,
            String finalAnswer,
            int stepCount,
            int toolCallCount,
            String riskLevel,
            Object pendingAction,
            Boolean approved,
            List<Object> traces
    ) {}

    @POST
    @Path("/manual")
    public AgentResponse runManual(AgentRequest req) {
        AgentState state = reactLoop.run(req.prompt(), req.autoDecision());

        return new AgentResponse(
                state.threadId,
                state.finalAnswer,
                state.stepCount,
                state.toolCallCount,
                state.riskLevel,
                state.pendingAction,
                state.approved,
                List.copyOf(state.traces)
        );
    }

    // --- Esempi ---

    @GET
    @Path("/examples")
    public List<Map<String, String>> examples() {
        return List.of(
                Map.of(
                        "title", "1. Ricerca KB",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/tools/search-kb -H \"Content-Type: application/json\" -d \"{\\\"query\\\":\\\"policy escalation P1\\\",\\\"topK\\\":3}\""
                ),
                Map.of(
                        "title", "2. Lookup ticket",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/tools/lookup-record -H \"Content-Type: application/json\" -d \"{\\\"recordId\\\":\\\"INC-1002\\\"}\""
                ),
                Map.of(
                        "title", "3. Calcolo SLA",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/tools/compute-sla -H \"Content-Type: application/json\" -d \"{\\\"id\\\":\\\"INC-1002\\\",\\\"priority\\\":\\\"P1\\\",\\\"elapsedHours\\\":1.0,\\\"owner\\\":\\\"team-sre\\\"}\""
                ),
                Map.of(
                        "title", "4. Loop ReAct (mock)",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/manual -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Mostrami il record INC-1002 e calcola lo SLA.\\\"}\""
                ),
                Map.of(
                        "title", "5. Loop ReAct con record inesistente",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/manual -H \"Content-Type: application/json\" -d \"{\\\"prompt\\\":\\\"Recupera INC-9999 e dimmi lo stato SLA.\\\"}\""
                )
        );
    }
}
