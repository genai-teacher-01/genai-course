# Day 1 Morning RAG — Java/Quarkus

Versione Java/Quarkus dell'esercitazione Giorno 1 mattina del corso GenAI.
Corrisponde a `main.py` della versione Python.

## Pipeline RAG

1. Caricamento documenti locali (HR, Procurement, ITSM)
2. Chunking semplice a caratteri con overlap
3. Indicizzazione in vector store in-memory (sostituisce ChromaDB)
4. Retrieval per similarità (distanza L2)
5. Answer generation con mock o Gemini

## Avvio

```bash
mvn quarkus:dev
```

## Modalità LLM

Configurare `rag.llm.mode` in `application.properties`:

| Valore | Descrizione |
|--------|-------------|
| `mock` | Risposta finta — test locale senza API |
| `gemini_free` | Gemini Free via `GOOGLE_API_KEY` (Google AI Studio) |
| `vertex` | Gemini su Vertex AI via `service_account.json` |

Creare un file `.env` nella root del progetto (da inserire nel .gitignore):
```
GOOGLE_API_KEY=la_tua_api_key
```

## Endpoint

| Comando Python | Endpoint REST |
|----------------|---------------|
| `python main.py setup-data` | `POST /api/morning/setup-data` |
| `python main.py ingest` | `POST /api/morning/ingest` |
| `python main.py retrieve "domanda"` | `POST /api/morning/retrieve {"question": "..."}` |
| `python main.py ask "domanda"` | `POST /api/morning/ask {"question": "..."}` |
| `python main.py test-llm` | `POST /api/morning/test-llm` |

## Note

- Il vector store è in-memory: rieseguire `/ingest` ad ogni riavvio.
- Non servono Docker, ChromaDB o GPU.
