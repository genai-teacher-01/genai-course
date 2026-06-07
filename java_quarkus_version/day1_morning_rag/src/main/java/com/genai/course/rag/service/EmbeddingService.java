package com.genai.course.rag.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedding locale deterministico basato su hashing dei token.
 *
 * Pro: funziona offline, nessun modello da scaricare, cross-platform.
 * Contro: lessicale, non semantico. 
 */
@ApplicationScoped
public class EmbeddingService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z\\u00C0-\\u00FF0-9]+");

    @ConfigProperty(name = "rag.embedding.dim", defaultValue = "384")
    int embeddingDim;

    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    public double[] hashEmbedding(String text) {
        return hashEmbedding(text, embeddingDim);
    }

    public double[] hashEmbedding(String text, int dim) {
        double[] vec = new double[dim];
        List<String> tokens = tokenize(text);

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            for (String token : tokens) {
                byte[] digestBytes = sha256.digest(token.getBytes(StandardCharsets.UTF_8));
                String hex = bytesToHex(digestBytes);

                long rawIdx = Long.parseLong(hex.substring(0, 8), 16);
                int idx = (int) (rawIdx % dim);

                int signByte = Integer.parseInt(hex.substring(8, 10), 16);
                double sign = (signByte % 2 == 0) ? 1.0 : -1.0;

                vec[idx] += sign;
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 non disponibile", e);
        }

        double norm = 0;
        for (double v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }

        return vec;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
