package com.genai.course.rag.resource;

import com.genai.course.rag.model.Chunk;
import com.genai.course.rag.model.RetrievedChunk;
import com.genai.course.rag.service.ChunkingService;
import com.genai.course.rag.service.EmbeddingService;
import com.genai.course.rag.service.LlmService;
import com.genai.course.rag.service.PromptService;
import com.genai.course.rag.service.SampleDataService;
import com.genai.course.rag.service.VectorStoreService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

/**
 * Endpoint REST per l'esercitazione del Giorno 1 mattina.
 * Corrisponde a {@code main.py} della versione Python.
 *
 * <p>Pipeline RAG implementata:
 * <ol>
 *   <li>Caricamento documenti locali (HR, Procurement, ITSM)</li>
 *   <li>Chunking semplice a caratteri con overlap</li>
 *   <li>Indicizzazione in vector store in-memory (sostituisce ChromaDB)</li>
 *   <li>Retrieval per similarità (distanza L2)</li>
 *   <li>Answer generation con mock o Gemini</li>
 * </ol>
 *
 * <p>Modalità LLM (configurare {@code rag.llm.mode} in application.properties o .env):
 * <ul>
 *   <li>{@code mock}        — risposta finta, utile per test locale senza API</li>
 *   <li>{@code gemini_free} — chiama Gemini Free usando {@code GOOGLE_API_KEY}</li>
 *   <li>{@code vertex}      — chiama Gemini su Vertex AI usando service_account.json</li>
 * </ul>
 *
 * <p>Equivalenza comandi Python → endpoint REST:
 * <pre>
 *   python main.py setup-data              →  POST /api/morning/setup-data
 *   python main.py ingest                  →  POST /api/morning/ingest
 *   python main.py retrieve "domanda"      →  POST /api/morning/retrieve  {"question": "..."}
 *   python main.py ask "domanda"           →  POST /api/morning/ask       {"question": "..."}
 *   python main.py test-llm                →  POST /api/morning/test-llm
 * </pre>
 */
@Path("/api/morning")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MorningRagResource {

    @Inject
    SampleDataService sampleDataService;

    @Inject
    ChunkingService chunkingService;

    @Inject
    EmbeddingService embeddingService;

    @Inject
    VectorStoreService vectorStoreService;

    @Inject
    PromptService promptService;

    @Inject
    LlmService llmService;

    @ConfigProperty(name = "rag.collection.name", defaultValue = "hcl_day1_rag")
    String collectionName;

    @ConfigProperty(name = "rag.top-k", defaultValue = "3")
    int topK;

    @ConfigProperty(name = "rag.chunk.size", defaultValue = "700")
    int chunkSize;

    @ConfigProperty(name = "rag.chunk.overlap", defaultValue = "120")
    int chunkOverlap;

    // --- DTO records ---

    public record SetupResponse(String message) {}
    public record IngestResponse(String message, int chunkCount, String collectionName) {}
    public record QuestionRequest(String question, Integer topK) {}
    public record RetrieveResponse(List<RetrievedChunk> chunks) {}
    public record AskResponse(List<RetrievedChunk> retrievedChunks, String prompt, String answer) {}
    public record TestLlmResponse(String answer, String mode) {}

    // --- Endpoints ---

    @POST
    @Path("/setup-data")
    public SetupResponse setupData() {
        String message = sampleDataService.setupSampleData();
        return new SetupResponse(message);
    }

    @POST
    @Path("/ingest")
    public IngestResponse ingest() {
        Map<String, String> docs = sampleDataService.readDocuments();
        List<Chunk> chunks = chunkingService.buildChunks(docs, chunkSize, chunkOverlap);

        vectorStoreService.deleteCollection(collectionName);

        List<String> ids = chunks.stream().map(Chunk::id).toList();
        List<String> documents = chunks.stream().map(Chunk::text).toList();
        List<Map<String, String>> metadatas = chunks.stream()
                .map(c -> Map.of("source", c.source(), "chunk_index", String.valueOf(c.chunkIndex())))
                .toList();
        List<double[]> embeddings = chunks.stream()
                .map(c -> embeddingService.hashEmbedding(c.text()))
                .toList();

        vectorStoreService.upsert(collectionName, ids, embeddings, documents, metadatas);

        return new IngestResponse(
                "Indicizzati " + chunks.size() + " chunk nel vector store.",
                chunks.size(),
                collectionName
        );
    }

    @POST
    @Path("/retrieve")
    public RetrieveResponse retrieve(QuestionRequest request) {
        int k = (request.topK() != null) ? request.topK() : topK;
        double[] queryEmbedding = embeddingService.hashEmbedding(request.question());
        List<RetrievedChunk> chunks = vectorStoreService.query(collectionName, queryEmbedding, k, null);
        return new RetrieveResponse(chunks);
    }

    @POST
    @Path("/ask")
    public AskResponse ask(QuestionRequest request) {
        int k = (request.topK() != null) ? request.topK() : topK;
        double[] queryEmbedding = embeddingService.hashEmbedding(request.question());
        List<RetrievedChunk> chunks = vectorStoreService.query(collectionName, queryEmbedding, k, null);
        String prompt = promptService.buildMorningPrompt(request.question(), chunks);
        String answer = llmService.callLlm(prompt);
        return new AskResponse(chunks, prompt, answer);
    }

    @POST
    @Path("/test-llm")
    public TestLlmResponse testLlm() {
        String answer = llmService.callLlm(
                "Rispondi solo con: Il piu' grande insegnante, il fallimento e'.");
        return new TestLlmResponse(answer, llmService.getMode());
    }
}
