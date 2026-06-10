package com.genai.course.multiagent.resource;

import com.genai.course.multiagent.model.ToolResponse;
import com.genai.course.multiagent.service.McpAdapter;
import com.genai.course.multiagent.service.McpAgentService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Endpoint per gli esercizi del mattino: MCP pattern.
 *
 * Corrisponde alla CLI di mcp_lab.py nella versione Python.
 * Lo studente esplora: discovery, schema, call diretta, agente MCP.
 */
@Path("/api/morning")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MorningMcpResource {

    @Inject McpAdapter mcpAdapter;
    @Inject McpAgentService mcpAgent;

    // --- Esercizio 1: MCP Discovery ---

    @GET
    @Path("/mcp/list-tools")
    public List<Map<String, Object>> listTools() {
        return mcpAdapter.listTools();
    }

    @GET
    @Path("/mcp/describe/{name}")
    public Object describeTool(@PathParam("name") String name) {
        Map<String, Object> tool = mcpAdapter.getTool(name);
        if (tool == null) return Map.of("error", "Tool non trovato: " + name);
        return tool;
    }

    // --- Esercizio 2: MCP Call diretta ---

    public record McpCallRequest(String name, Map<String, Object> args) {}

    @POST
    @Path("/mcp/call")
    public Object callTool(McpCallRequest req) {
        String result = mcpAdapter.callTool(req.name(), req.args() != null ? req.args() : Map.of());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(result, Object.class);
        } catch (Exception e) {
            return Map.of("raw", result);
        }
    }

    // --- Esercizio 3: Tool singoli (shortcut) ---

    public record SearchKbRequest(String query, Integer topK) {}

    @POST
    @Path("/mcp/search-kb")
    public Object searchKb(SearchKbRequest req) {
        int topK = req.topK() != null ? req.topK() : 3;
        String result = mcpAdapter.callTool("search_kb", Map.of("query", req.query(), "top_k", topK));
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(result, Object.class); }
        catch (Exception e) { return Map.of("raw", result); }
    }

    public record LookupRequest(String recordId) {}

    @POST
    @Path("/mcp/lookup-record")
    public Object lookupRecord(LookupRequest req) {
        String result = mcpAdapter.callTool("lookup_record", Map.of("record_id", req.recordId()));
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(result, Object.class); }
        catch (Exception e) { return Map.of("raw", result); }
    }

    // --- Esercizio 4: Agente MCP completo ---

    public record AgentRequest(String query) {}

    public record AgentResponse(
            String taskId,
            String answer,
            List<Map<String, Object>> toolCalls,
            List<Map<String, Object>> citations,
            List<Object> traces
    ) {}

    @POST
    @Path("/mcp/agent")
    public AgentResponse runAgent(AgentRequest req) {
        McpAgentService.McpAgentResult result = mcpAgent.run(req.query());
        return new AgentResponse(
                result.taskId(), result.answer(),
                result.toolCalls(), result.citations(),
                List.copyOf(result.traces()));
    }

    // --- Esempi ---

    @GET
    @Path("/examples")
    public List<Map<String, String>> examples() {
        return List.of(
                Map.of("title", "1. Discovery: lista tool MCP",
                        "curl", "curl -s http://localhost:8080/api/morning/mcp/list-tools"),
                Map.of("title", "2. Schema di un tool",
                        "curl", "curl -s http://localhost:8080/api/morning/mcp/describe/search_kb"),
                Map.of("title", "3. Call diretta MCP",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/mcp/call -H \"Content-Type: application/json\" -d \"{\\\"name\\\":\\\"search_kb\\\",\\\"args\\\":{\\\"query\\\":\\\"P1 escalation\\\",\\\"top_k\\\":2}}\""),
                Map.of("title", "4. Agente MCP: investigation completa",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/mcp/agent -H \"Content-Type: application/json\" -d \"{\\\"query\\\":\\\"Mostrami INC-1002 e cita la policy di escalation P1.\\\"}\""),
                Map.of("title", "5. Agente MCP: solo KB",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/mcp/agent -H \"Content-Type: application/json\" -d \"{\\\"query\\\":\\\"Quando va escalato un ticket P1 al team on-call?\\\"}\""),
                Map.of("title", "6. Agente MCP: ticket inesistente",
                        "curl", "curl -s -X POST http://localhost:8080/api/morning/mcp/agent -H \"Content-Type: application/json\" -d \"{\\\"query\\\":\\\"Recupera INC-9999 e calcola lo SLA.\\\"}\"")
        );
    }
}
