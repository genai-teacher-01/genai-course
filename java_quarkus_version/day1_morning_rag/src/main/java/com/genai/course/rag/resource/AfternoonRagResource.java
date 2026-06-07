package com.genai.course.rag.resource;

import com.genai.course.rag.model.ChunkProfile;
import com.genai.course.rag.model.RetrievedChunk;
import com.genai.course.rag.service.ChunkingService;
import com.genai.course.rag.service.EmbeddingService;
import com.genai.course.rag.service.LlmService;
import com.genai.course.rag.service.PromptService;
import com.genai.course.rag.service.QaLogService;
import com.genai.course.rag.service.SampleDataService;
import com.genai.course.rag.service.VectorStoreService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint REST per l'esercitazione del pomeriggio.
 * Corrisponde a afternoon_langchain_rag.py della versione Python.
 *
 * Aggiunge rispetto alla mattina:
 * - profili di chunking (small, default, large)
 * - inferenza automatica del dominio (hr, procurement, itsm)
 * - filtro per dominio nel retrieval
 * - prompt template piu' robusto (anti-injection)
 * - logging in qa_log.md
 * - confronto tra profili di chunking
 * - batch test
 */
@Path("/api/afternoon")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AfternoonRagResource {

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

    @Inject
    QaLogService qaLogService;

    @ConfigProperty(name = "rag.lc.collection-prefix", defaultValue = "hcl_day1_lc")
    String collectionPrefix;

    @ConfigProperty(name = "rag.top-k", defaultValue = "3")
    int defaultTopK;

    // --- Chunk profiles (identici alla versione Python) ---

    private static final Map<String, ChunkProfile> CHUNK_PROFILES = Map.of(
            "small", new ChunkProfile("small", 350, 80),
            "default", new ChunkProfile("default", 700, 120),
            "large", new ChunkProfile("large", 1200, 200)
    );

    // --- Test questions (identiche alla versione Python) ---

    private static final List<TestQuestion> TEST_QUESTIONS = List.of(
            new TestQuestion("itsm", "Quando un ticket P1 deve essere escalato?"),
            new TestQuestion("itsm", "Quali informazioni devo inserire per aprire un ticket urgente?"),
            new TestQuestion("hr", "Come richiedo ferie superiori a cinque giorni consecutivi?"),
            new TestQuestion("hr", "Cosa posso fare se una richiesta HR resta senza risposta?"),
            new TestQuestion("procurement", "Quando serve l'approvazione Procurement?"),
            new TestQuestion("procurement", "Cosa succede se il fornitore non e' censito?")
    );

    record TestQuestion(String domain, String question) {}

    // --- DTO records ---

    public record BuildIndexRequest(String profile, Boolean reset) {}
    public record BuildIndexResponse(String message, int documentCount, String profile, String collectionName) {}

    public record AfternoonQuestionRequest(String question, String profile, String domain, Integer k, Boolean log) {}
    public record RetrieveResponse(List<RetrievedChunk> chunks, String profile, String domain) {}
    public record AskResponse(List<RetrievedChunk> retrievedChunks, String answer, String profile, String domain) {}

    public record CompareRequest(String question, String domain, Integer k) {}
    public record CompareResponse(Map<String, List<RetrievedChunk>> profileResults) {}

    public record BatchTestRequest(String profile, Integer k) {}
    public record BatchTestResult(String question, String domain, String answer, List<RetrievedChunk> chunks) {}
    public record BatchTestResponse(List<BatchTestResult> results, String profile, int k) {}

    // --- Helper methods ---

    private ChunkProfile getProfile(String name) {
        String profileName = (name != null) ? name : "default";
        ChunkProfile profile = CHUNK_PROFILES.get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException(
                    "Profilo non valido: " + profileName + ". Validi: " + CHUNK_PROFILES.keySet());
        }
        return profile;
    }

    private String getCollectionName(String profileName) {
        return collectionPrefix + "_" + profileName;
    }

    private void buildIndexInternal(String profileName, boolean reset) {
        ChunkProfile profile = getProfile(profileName);
        String colName = getCollectionName(profileName);

        if (reset) {
            vectorStoreService.deleteCollection(colName);
        }

        Map<String, String> docs = sampleDataService.readDocuments();

        List<String> ids = new ArrayList<>();
        List<double[]> embeddings = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<Map<String, String>> metadatas = new ArrayList<>();

        docs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String source = entry.getKey();
                    String domain = chunkingService.inferDomainFromFilename(source);
                    List<String> parts = chunkingService.splitText(
                            entry.getValue(), profile.chunkSize(), profile.chunkOverlap());

                    for (int idx = 0; idx < parts.size(); idx++) {
                        String chunk = parts.get(idx);
                        ids.add(source + "::" + profileName + "::" + idx);
                        documents.add(chunk);
                        embeddings.add(embeddingService.hashEmbedding(chunk));
                        metadatas.add(Map.of(
                                "source", source,
                                "domain", domain,
                                "chunk_index", String.valueOf(idx),
                                "chunk_profile", profileName
                        ));
                    }
                });

        vectorStoreService.upsert(colName, ids, embeddings, documents, metadatas);
    }

    private List<RetrievedChunk> retrieveInternal(String question, String profileName, String domain, int k) {
        String colName = getCollectionName(profileName);
        double[] queryEmbedding = embeddingService.hashEmbedding(question);

        Map<String, String> filter = null;
        if (domain != null && !domain.isBlank() && !domain.equals("all")) {
            filter = Map.of("domain", domain);
        }

        return vectorStoreService.query(colName, queryEmbedding, k, filter);
    }

    // --- Endpoints ---

    @POST
    @Path("/build-index")
    public BuildIndexResponse buildIndex(BuildIndexRequest request) {
        String profileName = (request != null && request.profile() != null) ? request.profile() : "default";
        boolean reset = (request == null || request.reset() == null) || request.reset();

        buildIndexInternal(profileName, reset);

        int count = vectorStoreService.getCollectionSize(getCollectionName(profileName));

        return new BuildIndexResponse(
                "Indicizzati " + count + " Document con profilo " + profileName + ".",
                count,
                profileName,
                getCollectionName(profileName)
        );
    }

    @POST
    @Path("/retrieve")
    public RetrieveResponse retrieve(AfternoonQuestionRequest request) {
        String profileName = (request.profile() != null) ? request.profile() : "default";
        int k = (request.k() != null) ? request.k() : defaultTopK;

        List<RetrievedChunk> chunks = retrieveInternal(
                request.question(), profileName, request.domain(), k);

        return new RetrieveResponse(chunks, profileName, request.domain());
    }

    @POST
    @Path("/ask")
    public AskResponse ask(AfternoonQuestionRequest request) {
        String profileName = (request.profile() != null) ? request.profile() : "default";
        int k = (request.k() != null) ? request.k() : defaultTopK;
        boolean log = (request.log() != null) && request.log();

        List<RetrievedChunk> chunks = retrieveInternal(
                request.question(), profileName, request.domain(), k);
        String prompt = promptService.buildAfternoonPrompt(request.question(), chunks);
        String answer = llmService.callLlm(prompt);

        if (log) {
            qaLogService.appendLog(request.question(), answer, chunks,
                    profileName, request.domain(), k);
        }

        return new AskResponse(chunks, answer, profileName, request.domain());
    }

    @POST
    @Path("/compare-chunks")
    public CompareResponse compareChunks(CompareRequest request) {
        int k = (request.k() != null) ? request.k() : defaultTopK;

        Map<String, List<RetrievedChunk>> results = new LinkedHashMap<>();

        for (String profileName : List.of("small", "default", "large")) {
            buildIndexInternal(profileName, true);
            List<RetrievedChunk> chunks = retrieveInternal(
                    request.question(), profileName, request.domain(), k);
            results.put(profileName, chunks);
        }

        return new CompareResponse(results);
    }

    @POST
    @Path("/batch-test")
    public BatchTestResponse batchTest(BatchTestRequest request) {
        String profileName = (request != null && request.profile() != null) ? request.profile() : "default";
        int k = (request != null && request.k() != null) ? request.k() : defaultTopK;

        buildIndexInternal(profileName, true);

        List<BatchTestResult> results = new ArrayList<>();
        for (TestQuestion tq : TEST_QUESTIONS) {
            List<RetrievedChunk> chunks = retrieveInternal(tq.question(), profileName, tq.domain(), k);
            String prompt = promptService.buildAfternoonPrompt(tq.question(), chunks);
            String answer = llmService.callLlm(prompt);

            qaLogService.appendLog(tq.question(), answer, chunks, profileName, tq.domain(), k);
            results.add(new BatchTestResult(tq.question(), tq.domain(), answer, chunks));
        }

        return new BatchTestResponse(results, profileName, k);
    }
}
