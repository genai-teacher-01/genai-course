package com.genai.course.rag.service;

import com.genai.course.rag.model.RetrievedChunk;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vector store in-memory che sostituisce ChromaDB.
 * Usa distanza L2 (euclidea) per la similarità, come ChromaDB di default.
 * Supporta collection multiple e filtri su metadata.
 */
@ApplicationScoped
public class VectorStoreService {

    private final Map<String, List<Entry>> collections = new ConcurrentHashMap<>();

    static class Entry {
        final String id;
        final double[] embedding;
        final String document;
        final Map<String, String> metadata;

        Entry(String id, double[] embedding, String document, Map<String, String> metadata) {
            this.id = id;
            this.embedding = embedding;
            this.document = document;
            this.metadata = metadata;
        }
    }

    public void deleteCollection(String name) {
        collections.remove(name);
    }

    public int getCollectionSize(String name) {
        List<Entry> col = collections.get(name);
        return col == null ? 0 : col.size();
    }

    public void upsert(String collectionName, List<String> ids, List<double[]> embeddings,
                        List<String> documents, List<Map<String, String>> metadatas) {
        List<Entry> collection = collections.computeIfAbsent(collectionName, k -> new ArrayList<>());

        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            collection.removeIf(e -> e.id.equals(id));
            collection.add(new Entry(
                    id,
                    embeddings.get(i),
                    documents.get(i),
                    metadatas.get(i)
            ));
        }
    }

    public List<RetrievedChunk> query(String collectionName, double[] queryEmbedding,
                                       int topK, Map<String, String> filter) {
        List<Entry> collection = collections.get(collectionName);
        if (collection == null || collection.isEmpty()) {
            return List.of();
        }

        return collection.stream()
                .filter(entry -> matchesFilter(entry.metadata, filter))
                .map(entry -> new AbstractMap.SimpleEntry<>(entry, l2Distance(queryEmbedding, entry.embedding)))
                .sorted(Comparator.comparingDouble(AbstractMap.SimpleEntry::getValue))
                .limit(topK)
                .map(pair -> {
                    Entry e = pair.getKey();
                    return new RetrievedChunk(
                            e.document,
                            e.metadata.getOrDefault("source", "unknown"),
                            safeParseInt(e.metadata.getOrDefault("chunk_index", "-1")),
                            pair.getValue(),
                            e.metadata.getOrDefault("domain", null),
                            e.metadata.getOrDefault("chunk_profile", null)
                    );
                })
                .toList();
    }

    private boolean matchesFilter(Map<String, String> metadata, Map<String, String> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        return filter.entrySet().stream()
                .allMatch(f -> f.getValue().equals(metadata.get(f.getKey())));
    }

    private double l2Distance(double[] a, double[] b) {
        double sum = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    private int safeParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
