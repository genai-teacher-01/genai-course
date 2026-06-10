package com.genai.course.multiagent.service;

import com.genai.course.multiagent.model.KbHit;
import com.genai.course.multiagent.model.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class KnowledgeBaseService {

    private static final Map<String, String> DOCS = new LinkedHashMap<>();

    static {
        DOCS.put("itsm_incident_policy.md",
                "# ITSM Incident Management Policy\n\n" +
                "Definisce regole per gestione incidenti, malfunzionamenti applicativi, disservizi infrastrutturali. " +
                "Un incidente è qualsiasi evento non pianificato che interrompe o degrada la qualità di un servizio IT. " +
                "Informazioni minime obbligatorie: servizio coinvolto, ambiente, data/ora inizio, utenti impattati, " +
                "impatto business, workaround disponibile, log/screenshot, priorità proposta. " +
                "Classificazione iniziale può essere proposta dall'utente ma verificata dal team ITSM. " +
                "Per P1/P2 aggiornamento periodico nel ticket obbligatorio. " +
                "Chiusura solo quando servizio ripristinato, utente confermato, causa documentata, " +
                "per P1/P2 valutata necessità problem management.");
        DOCS.put("itsm_sla_policy.md",
                "# ITSM SLA Policy\n\n" +
                "P1 critico con impatto produzione/servizi essenziali: presa in carico entro 30 minuti, " +
                "aggiornamento ogni 30 minuti, escalation se non preso entro 30 minuti, " +
                "coinvolgimento service manager se non workaround entro 60 minuti. " +
                "P2 rilevante/workaround disponibile: presa in carico entro 4 ore, " +
                "aggiornamento entro giornata, escalation se non aggiornato entro 4 ore. " +
                "P3 ordinaria/non bloccante: presa in carico entro 24 ore. " +
                "P4 informativa/backlog: presa in carico entro 72 ore. " +
                "Near breach quando tempo residuo ≤20% soglia SLA. " +
                "Violazione quando tempo supera soglia.");
        DOCS.put("itsm_escalation_policy.md",
                "# ITSM Escalation Policy\n\n" +
                "Escalation tecnica quando team manca competenze, richiede specialisti, workaround assente, " +
                "log indicano fault complesso, coinvolge più componenti. " +
                "Escalation gestionale quando SLA violato, P1 senza owner, business impact alto, " +
                "cliente richiede visibilità, coordinamento mancante. " +
                "Azioni critiche (escalation formale, notifica service manager, cambio priorità a P1, " +
                "major incident, chiusura P1/P2, workaround produzione, emergency change) richiedono conferma umana. " +
                "Escalation minima: ticket id, priorità, servizio, owner, impatto business, " +
                "stato SLA, motivo, azione richiesta, checkpoint temporale.");
        DOCS.put("change_management_policy.md",
                "# Change Management Policy\n\n" +
                "Standard change: pre-approvato, ripetibile, basso rischio " +
                "(riavvio servizio non critico, rotazione certificati, aggiornamento configurazione, deploy patch validate). " +
                "Normal change richiede approvazione: descrizione, motivazione, rischio, piano test, rollback, " +
                "finestra esecuzione, impatto, approvatore. " +
                "Emergency change durante incidente critico: tracciato, giustificato, include incidente collegato, " +
                "rischio, rollback, approvatore, finestra, evidenza post-change. " +
                "P1 senza workaround può proporre emergency change; " +
                "agente suggerisce necessità valutazione ma non approva autonomamente.");
        DOCS.put("on_call_policy.md",
                "# On-call and Major Incident Policy\n\n" +
                "Team on-call attivato quando: P1 non preso in carico entro 30 minuti, " +
                "servizio critico indisponibile, impatto sicurezza/revenue/obblighi contrattuali, " +
                "più sistemi correlati degradati, service owner richiede supporto immediato. " +
                "Major incident valutato quando: molteplici utenti impattati, servizio essenziale indisponibile, " +
                "workaround assente, comunicazione stakeholder business necessaria, " +
                "danno potenziale supera operatività team normale. " +
                "Dichiarazione major incident richiede approvazione umana.");
        DOCS.put("knowledge_article_guidelines.md",
                "# Knowledge Article Guidelines\n\n" +
                "Articolo deve contenere: sintomi osservabili, causa nota/ipotesi principale, " +
                "passaggi diagnosi, workaround, soluzione definitiva, log/comandi utili, " +
                "servizi/componenti, data ultimo aggiornamento. " +
                "Agente AI cerca article e propone risposta; se basata su documentazione deve indicare fonti. " +
                "Se documentazione insufficiente, agente dichiara evidenza mancante.");
    }

    public ToolResponse searchKb(String query, int topK) {
        if (query == null || query.isBlank()) return ToolResponse.fail("Query vuota.");
        String[] tokens = query.toLowerCase().split("\\s+");
        List<KbHit> hits = new ArrayList<>();
        for (var entry : DOCS.entrySet()) {
            String docLower = entry.getValue().toLowerCase();
            int matchCount = 0;
            for (String token : tokens) {
                if (token.length() >= 3 && docLower.contains(token)) matchCount++;
            }
            if (matchCount > 0) {
                double score = (double) matchCount / tokens.length;
                String snippet = extractSnippet(entry.getValue(), tokens);
                hits.add(new KbHit(entry.getKey(), snippet, score));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (hits.size() > topK) hits = hits.subList(0, topK);
        return ToolResponse.ok(hits, Map.of("total_docs", DOCS.size(), "query", query));
    }

    private String extractSnippet(String text, String[] tokens) {
        String lower = text.toLowerCase();
        int bestPos = 0;
        for (String token : tokens) {
            if (token.length() >= 3) {
                int idx = lower.indexOf(token);
                if (idx >= 0) { bestPos = idx; break; }
            }
        }
        int start = Math.max(0, bestPos - 40);
        int end = Math.min(text.length(), bestPos + 200);
        String snippet = text.substring(start, end).replaceAll("\\s+", " ").trim();
        if (start > 0) snippet = "..." + snippet;
        if (end < text.length()) snippet = snippet + "...";
        return snippet;
    }
}
