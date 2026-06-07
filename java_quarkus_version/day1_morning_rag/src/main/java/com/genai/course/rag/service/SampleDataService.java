package com.genai.course.rag.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@ApplicationScoped
public class SampleDataService {

    private static final Map<String, String> SAMPLE_DOCS = new LinkedHashMap<>();

    static {
        SAMPLE_DOCS.put("hr_policy.md", """
                # HR Policy — Ferie e permessi

                I dipendenti possono richiedere ferie tramite il portale HR aziendale.
                La richiesta deve essere inserita almeno 5 giorni lavorativi prima della data prevista,
                salvo casi urgenti o motivati.

                Per ferie superiori a 5 giorni consecutivi è richiesta l'approvazione del responsabile diretto.
                Le ferie residue sono visibili nella sezione "Balance" del portale HR.

                ## Permessi straordinari

                I permessi straordinari possono essere richiesti per motivi familiari, sanitari o personali.
                La richiesta deve includere una breve motivazione e, quando richiesto, documentazione di supporto.

                ## Escalation

                Se una richiesta HR rimane senza risposta per più di 3 giorni lavorativi,
                il dipendente può aprire un ticket HR indicando il numero della richiesta originale.""");

        SAMPLE_DOCS.put("procurement_policy.md", """
                # Procurement Policy — Richieste di acquisto

                Una richiesta di acquisto deve contenere descrizione del bene o servizio,
                centro di costo, importo stimato, fornitore suggerito e motivazione business.

                Per importi inferiori a 5.000 euro è sufficiente l'approvazione del line manager.
                Per importi tra 5.000 e 25.000 euro è richiesta anche l'approvazione Procurement.
                Per importi superiori a 25.000 euro è richiesta una valutazione comparativa di almeno tre fornitori.

                ## Fornitori

                I fornitori devono essere presenti nell'anagrafica aziendale.
                Se il fornitore non è censito, il richiedente deve avviare la procedura di onboarding fornitore.

                ## Ordini urgenti

                Gli ordini urgenti devono essere marcati come "urgent" e motivati.
                Il team Procurement può respingere richieste urgenti prive di giustificazione.""");

        SAMPLE_DOCS.put("itsm_policy.md", """
                # ITSM Policy — Ticket e incident management

                Gli utenti devono aprire un ticket ITSM per problemi tecnici, richieste di accesso,
                malfunzionamenti applicativi o richieste di configurazione.

                ## Priorità

                Un ticket P1 indica un incidente critico con impatto su produzione o servizio essenziale.
                Un ticket P2 indica un problema rilevante con workaround disponibile.
                Un ticket P3 indica una richiesta ordinaria o un problema non bloccante.

                ## Ticket urgenti

                Per aprire un ticket urgente, l'utente deve indicare impatto, urgenza,
                servizio coinvolto, utenti impattati e orario di inizio del problema.

                ## Escalation

                Se un ticket P1 non riceve presa in carico entro 30 minuti, deve essere escalato al team on-call.
                Se un ticket P2 non riceve aggiornamenti entro 4 ore lavorative, può essere escalato al service manager.""");
    }

    private final Path dataDir = Path.of("data");

    public String setupSampleData() {
        try {
            Files.createDirectories(dataDir);
            for (var entry : SAMPLE_DOCS.entrySet()) {
                Path path = dataDir.resolve(entry.getKey());
                if (!Files.exists(path)) {
                    Files.writeString(path, entry.getValue());
                }
            }
            return "Dataset creato/verificato in: " + dataDir.toAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("Errore nella creazione del dataset", e);
        }
    }

    public Map<String, String> readDocuments() {
        setupSampleData();
        try {
            Map<String, String> docs = new TreeMap<>();
            try (var stream = Files.list(dataDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .forEach(path -> {
                            try {
                                docs.put(path.getFileName().toString(), Files.readString(path));
                            } catch (IOException e) {
                                throw new RuntimeException("Errore lettura " + path, e);
                            }
                        });
            }
            if (docs.isEmpty()) {
                throw new RuntimeException(
                        "Nessun documento trovato in " + dataDir + ". Esegui prima POST /api/morning/setup-data");
            }
            return docs;
        } catch (IOException e) {
            throw new RuntimeException("Errore lettura documenti", e);
        }
    }
}
