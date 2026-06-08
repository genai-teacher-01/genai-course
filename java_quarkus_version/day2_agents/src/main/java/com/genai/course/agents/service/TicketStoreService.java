package com.genai.course.agents.service;

import com.genai.course.agents.model.TicketRecord;
import com.genai.course.agents.model.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TicketStoreService {

    private static final Map<String, TicketRecord> TICKETS = new LinkedHashMap<>();

    static {
        TICKETS.put("INC-1002", new TicketRecord(
                "INC-1002", "ITSM-1002", "incident",
                "Interruzione servizio e-mail per utenti area Finance",
                "Diversi utenti area Finance non accedono posta; client mostra timeout, webmail errore 503.",
                "P1", "In Progress", "Corporate Email", "production", "mail-gateway",
                1.0, "team-sre", "m.rossi", "finance.ops",
                180, "Chiusura mensile rallentata; rischio ritardo report CFO.",
                false,
                List.of("email", "finance", "production", "p1"),
                List.of("CHG-2201"),
                List.of(
                        new TicketRecord.Comment("finance.ops",
                                "Impatto confermato su più uffici.", "2026-05-11T09:20:00"),
                        new TicketRecord.Comment("team-sre",
                                "Verifica in corso su mail gateway e bilanciatore.", "2026-05-11T09:42:00")
                )
        ));

        TICKETS.put("INC-1003", new TicketRecord(
                "INC-1003", "ITSM-1003", "incident",
                "Degrado prestazionale API ordini procurement",
                "API creazione ordine rispondono lentamente; disponibile ma chiamate superano 12 secondi.",
                "P2", "Open", "Procurement Platform", "production", "purchase-order-api",
                3.6, "team-app-procurement", null, "s2p.business",
                35, "Rallentamento creazione ordini; workaround inserimento manuale disponibile.",
                true,
                List.of("api", "procurement", "latency", "p2"),
                List.of(),
                List.of(
                        new TicketRecord.Comment("s2p.business",
                                "Workaround scomodo ma utilizzabile.", "2026-05-11T10:15:00")
                )
        ));

        TICKETS.put("INC-1004", new TicketRecord(
                "INC-1004", "ITSM-1004", "incident",
                "Richiesta accesso dashboard sales",
                "Nuovo utente richiede accesso read-only alla dashboard commerciale.",
                "P3", "Open", "Sales Analytics", "production", "bi-dashboard",
                5.0, "team-bi", "l.bianchi", "sales.ops",
                1, "Nessun impatto bloccante; richiesta ordinaria.",
                true,
                List.of("access-request", "sales", "p3"),
                List.of(),
                List.of()
        ));

        TICKETS.put("INC-1005", new TicketRecord(
                "INC-1005", "ITSM-1005", "incident",
                "ABEND su job batch di fatturazione notturna",
                "Job batch BILLING_CLOSE_01 terminato con ABEND; catena reportistica non partita.",
                "P1", "Open", "Billing Batch", "production", "mainframe-batch",
                0.35, "team-mainframe", null, "batch.monitoring",
                12, "Rischio ritardo produzione report giornalieri e riconciliazione ricavi.",
                false,
                List.of("mainframe", "billing", "abend", "p1"),
                List.of("PRB-778"),
                List.of(
                        new TicketRecord.Comment("batch.monitoring",
                                "Codice ABEND rilevato: S0C7.", "2026-05-11T06:10:00")
                )
        ));
    }

    public ToolResponse lookupRecord(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return ToolResponse.fail("record_id vuoto.");
        }

        TicketRecord ticket = TICKETS.get(recordId.toUpperCase().trim());
        if (ticket == null) {
            return ToolResponse.fail("Record non trovato: " + recordId);
        }

        return ToolResponse.ok(List.of(ticket), Map.of("record_id", recordId));
    }

    public TicketRecord getTicket(String recordId) {
        return TICKETS.get(recordId.toUpperCase().trim());
    }
}
