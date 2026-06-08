package com.genai.course.agents.service;

import java.util.*;
import java.util.function.Function;

/**
 * Grafo di stato didattico, ispirato a LangGraph StateGraph.
 *
 * Corrisponde a SimpleStateGraph nella versione Python.
 * In produzione si userebbe LangGraph4j o un framework simile;
 * qui lo implementiamo a mano per motivi didattici.
 */
public class SimpleStateGraph<S> {

    public static final String END = "__end__";

    private final Map<String, Function<S, S>> nodes = new LinkedHashMap<>();
    private final Map<String, String> edges = new LinkedHashMap<>();
    private final Map<String, Function<S, String>> conditionalEdges = new LinkedHashMap<>();
    private String entryPoint;

    public void addNode(String name, Function<S, S> fn) {
        nodes.put(name, fn);
    }

    public void addEdge(String from, String to) {
        edges.put(from, to);
    }

    public void addConditionalEdges(String from, Function<S, String> router) {
        conditionalEdges.put(from, router);
    }

    public void setEntryPoint(String name) {
        this.entryPoint = name;
    }

    public S invoke(S state, int maxIterations) {
        if (entryPoint == null) {
            throw new IllegalStateException("Entry point non configurato.");
        }

        String current = entryPoint;

        for (int i = 0; i < maxIterations; i++) {
            if (END.equals(current)) {
                break;
            }

            Function<S, S> nodeFn = nodes.get(current);
            if (nodeFn == null) {
                throw new IllegalStateException("Nodo non trovato: " + current);
            }

            state = nodeFn.apply(state);

            if (conditionalEdges.containsKey(current)) {
                current = conditionalEdges.get(current).apply(state);
            } else if (edges.containsKey(current)) {
                current = edges.get(current);
            } else {
                break;
            }
        }

        return state;
    }

    public Set<String> getNodeNames() {
        return Collections.unmodifiableSet(nodes.keySet());
    }
}
