"""
day1_morning_rag/main.py

Esercitazione Giorno 1 mattina:
- caricamento documenti locali
- chunking semplice
- indicizzazione in Chroma
- retrieval
- answer(question) con mock oppure Vertex AI / Gemini

Modalità:
    LLM_MODE=mock    -> non chiama Gemini, utile per test locale
    LLM_MODE=vertex  -> chiama Gemini su Vertex AI usando service_account.json
    LLM_MODE="gemini_free" -> chiama Gemini Free usando GOOGLE_API_KEY (API key semplice, non service account)

Comandi:
    python main.py setup-data
    python main.py ingest
    python main.py retrieve "Come si apre un ticket urgente?"
    python main.py ask "Come si apre un ticket urgente?"
    python main.py test-llm
"""

from __future__ import annotations

import argparse
import hashlib
import math
import os
import re
import textwrap
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import chromadb
from dotenv import load_dotenv

from google import genai
from importlib_resources import contents

# Configurazione

load_dotenv()

BASE_DIR = Path(__file__).parent.resolve()
DATA_DIR = BASE_DIR / "data"

CHROMA_PATH = Path(os.getenv("CHROMA_PATH", "./chroma_db"))
if not CHROMA_PATH.is_absolute():
    CHROMA_PATH = BASE_DIR / CHROMA_PATH

COLLECTION_NAME = os.getenv("COLLECTION_NAME", "hcl_day1_rag")

LLM_MODE = os.getenv("LLM_MODE", "mock").lower().strip()

PROJECT_ID = os.getenv("GOOGLE_CLOUD_PROJECT", "hclsw-gcp-wrkld-auto")
LOCATION = os.getenv("GOOGLE_CLOUD_LOCATION", "us-east1")
SERVICE_ACCOUNT_FILE = os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "./service_account.json")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")

TOP_K = int(os.getenv("TOP_K", "3"))
CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", "700"))
CHUNK_OVERLAP = int(os.getenv("CHUNK_OVERLAP", "120"))

EMBEDDING_DIM = 384


# ---------------------------------------------------------------------
# Dataset didattico minimale
# ---------------------------------------------------------------------

SAMPLE_DOCS = {
    "hr_policy.md": """
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
il dipendente può aprire un ticket HR indicando il numero della richiesta originale.
""",
    "procurement_policy.md": """
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
Il team Procurement può respingere richieste urgenti prive di giustificazione.
""",
    "itsm_policy.md": """
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
Se un ticket P2 non riceve aggiornamenti entro 4 ore lavorative, può essere escalato al service manager.
""",
}


def setup_sample_data() -> None:
    DATA_DIR.mkdir(exist_ok=True)

    for filename, content in SAMPLE_DOCS.items():
        path = DATA_DIR / filename
        if not path.exists():
            path.write_text(textwrap.dedent(content).strip(), encoding="utf-8")

    print(f"Dataset creato/verificato in: {DATA_DIR}")


# ---------------------------------------------------------------------
# Chunking
# ---------------------------------------------------------------------

@dataclass
class Chunk:
    id: str
    text: str
    source: str
    chunk_index: int


def read_documents(data_dir: Path) -> list[tuple[str, str]]:
    docs: list[tuple[str, str]] = []

    for path in sorted(data_dir.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        docs.append((path.name, text))

    if not docs:
        raise RuntimeError(
            f"Nessun documento trovato in {data_dir}. "
            "Esegui prima: python main.py setup-data"
        )

    return docs


def split_text(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    """
    Chunking semplice a caratteri.
    Non è ancora production-grade, ma è perfetto per capire il meccanismo.
    """
    cleaned = re.sub(r"\s+", " ", text).strip()

    if len(cleaned) <= chunk_size:
        return [cleaned]

    chunks: list[str] = []
    start = 0

    while start < len(cleaned):
        end = start + chunk_size
        chunk = cleaned[start:end].strip()
        if chunk:
            chunks.append(chunk)

        if end >= len(cleaned):
            break

        start = end - chunk_overlap

    return chunks


def build_chunks() -> list[Chunk]:
    docs = read_documents(DATA_DIR)
    all_chunks: list[Chunk] = []

    for source, text in docs:
        parts = split_text(text, CHUNK_SIZE, CHUNK_OVERLAP)

        for idx, part in enumerate(parts):
            chunk_id = f"{source}::chunk_{idx}"
            all_chunks.append(
                Chunk(
                    id=chunk_id,
                    text=part,
                    source=source,
                    chunk_index=idx,
                )
            )

    return all_chunks


# ---------------------------------------------------------------------
# Embedding didattico ultra-robusto
# ---------------------------------------------------------------------

def tokenize(text: str) -> list[str]:
    """
    Tokenizzazione minimale.
    Per il Giorno 1 non ci interessa ancora il modello di embedding perfetto.
    Ci interessa capire il flusso: testo -> vettore -> retrieval.
    """
    return re.findall(r"[a-zA-ZÀ-ÿ0-9]+", text.lower())


def hash_embedding(text: str, dim: int = EMBEDDING_DIM) -> list[float]:
    """
    Embedding locale deterministico basato su hashing dei token.

    Pro:
    - non richiede download da Hugging Face
    - funziona offline
    - funziona su Windows/macOS/Linux
    - è sufficiente per spiegare il retrieval

    Contro:
    - è lessicale, non veramente semantico
    - nel pomeriggio o nei giorni successivi potrà essere sostituito da embeddings migliori
    """
    vec = [0.0] * dim
    tokens = tokenize(text)

    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        idx = int(digest[:8], 16) % dim
        sign = 1.0 if int(digest[8:10], 16) % 2 == 0 else -1.0
        vec[idx] += sign

    norm = math.sqrt(sum(x * x for x in vec))
    if norm == 0:
        return vec

    return [x / norm for x in vec]


# ---------------------------------------------------------------------
# Chroma
# ---------------------------------------------------------------------

def get_chroma_collection(reset: bool = False):
    CHROMA_PATH.mkdir(parents=True, exist_ok=True)
    client = chromadb.PersistentClient(path=str(CHROMA_PATH))

    if reset:
        try:
            client.delete_collection(COLLECTION_NAME)
            print(f"Collection eliminata: {COLLECTION_NAME}")
        except Exception:
            pass

    collection = client.get_or_create_collection(name=COLLECTION_NAME)
    return collection


def ingest() -> None:
    setup_sample_data()
    chunks = build_chunks()

    collection = get_chroma_collection(reset=True)

    ids = [chunk.id for chunk in chunks]
    documents = [chunk.text for chunk in chunks]
    metadatas = [
        {
            "source": chunk.source,
            "chunk_index": chunk.chunk_index,
        }
        for chunk in chunks
    ]
    embeddings = [hash_embedding(chunk.text) for chunk in chunks]

    collection.upsert(
        ids=ids,
        documents=documents,
        metadatas=metadatas,
        embeddings=embeddings,
    )

    print(f"Indicizzati {len(chunks)} chunk in Chroma.")
    print(f"Percorso Chroma: {CHROMA_PATH}")
    print(f"Collection: {COLLECTION_NAME}")


def retrieve(question: str, top_k: int = TOP_K) -> list[dict]:
    collection = get_chroma_collection(reset=False)

    results = collection.query(
        query_embeddings=[hash_embedding(question)],
        n_results=top_k,
        include=["documents", "metadatas", "distances"],
    )

    retrieved: list[dict] = []

    docs = results.get("documents", [[]])[0]
    metas = results.get("metadatas", [[]])[0]
    distances = results.get("distances", [[]])[0]

    for doc, meta, distance in zip(docs, metas, distances):
        retrieved.append(
            {
                "text": doc,
                "source": meta.get("source", "unknown"),
                "chunk_index": meta.get("chunk_index", -1),
                "distance": distance,
            }
        )

    return retrieved


def print_retrieved(chunks: list[dict]) -> None:
    if not chunks:
        print("Nessun chunk recuperato.")
        return

    for i, chunk in enumerate(chunks, start=1):
        print("=" * 80)
        print(f"RISULTATO {i}")
        print(f"source      : {chunk['source']}")
        print(f"chunk_index : {chunk['chunk_index']}")
        print(f"distance    : {chunk['distance']:.4f}")
        print("-" * 80)
        print(chunk["text"])


# ---------------------------------------------------------------------
# Prompt e chiamata LLM
# ---------------------------------------------------------------------

def build_prompt(question: str, retrieved_chunks: list[dict]) -> str:
    context_blocks = []

    for i, chunk in enumerate(retrieved_chunks, start=1):
        context_blocks.append(
            f"[SOURCE {i}: {chunk['source']} | chunk {chunk['chunk_index']}]\n"
            f"{chunk['text']}"
        )

    context = "\n\n".join(context_blocks)

    prompt = f"""
Sei un assistente aziendale.
Rispondi alla domanda usando SOLO il contesto fornito.

Regole:
- Se il contesto non contiene l'informazione, scrivi: "Non trovo questa informazione nei documenti forniti."
- Non inventare policy.
- Rispondi in italiano.
- Alla fine cita le fonti usate nel formato: Fonti: nome_file.md.

CONTESTO:
{context}

DOMANDA:
{question}

RISPOSTA:
""".strip()

    return prompt


def call_mock_llm(prompt: str) -> str:
    """
    Modalità didattica per testare la pipeline senza chiamare Vertex AI.
    Non è intelligente: serve solo a dimostrare che retrieval e prompt funzionano.
    """
    return (
        "MOCK RESPONSE\n\n"
        "La pipeline RAG ha recuperato un contesto e ha costruito un prompt. "
        "In modalità reale, questo prompt verrebbe inviato a Gemini su Vertex AI.\n\n"
        "Controlla sopra i chunk recuperati: se sono pertinenti, la RAG ha buone probabilità "
        "di produrre una risposta corretta. Se i chunk non sono pertinenti, il problema è nel retrieval, "
        "non nel modello.\n\n"
        "Fonti: vedere i chunk recuperati."
    )


def call_vertex_gemini(prompt: str) -> str:
    """
    Chiamata a Gemini su Vertex AI usando service account JSON.
    Usa il modulo indicato dal cliente nel materiale hackathon.
    """
    service_account_path = Path(SERVICE_ACCOUNT_FILE)
    if not service_account_path.is_absolute():
        service_account_path = BASE_DIR / service_account_path

    if not service_account_path.exists():
        raise FileNotFoundError(
            f"File service account non trovato: {service_account_path}\n"
            "Soluzione: metti service_account.json nella cartella del progetto "
            "oppure torna a LLM_MODE=mock nel file .env."
        )

    from google.oauth2 import service_account
    import vertexai
    from vertexai.generative_models import GenerativeModel

    credentials = service_account.Credentials.from_service_account_file(
        str(service_account_path)
    )

    vertexai.init(
        project=PROJECT_ID,
        location=LOCATION,
        credentials=credentials,
    )

    model = GenerativeModel(GEMINI_MODEL)
    response = model.generate_content(prompt)

    text = getattr(response, "text", None)
    if not text:
        return str(response)

    return text

def call_gemini_free(prompt: str) -> str:
    api_key = os.getenv("GOOGLE_API_KEY")
    if not api_key:
        raise ValueError(
            "GOOGLE_API_KEY non impostata. Per usare la modalità gemini_free, "
            "devi creare una API key su Google Cloud Console e inserirla nel file .env."
        )
    
    client = genai.Client(api_key=api_key, http_options={"api_version": "v1"})

    response = client.models.generate_content(
        model="models/gemini-2.5-flash",
        contents=prompt
    )

    return response.text 


def call_llm(prompt: str) -> str:
    if LLM_MODE == "mock":
        return call_mock_llm(prompt)

    if LLM_MODE == "vertex":
        return call_vertex_gemini(prompt)
    
    if LLM_MODE == "gemini_free":
        return call_gemini_free(prompt)

    raise ValueError(
        f"LLM_MODE non valido: {LLM_MODE}. Usa 'mock' oppure 'vertex'."
    )


def answer(question: str) -> str:
    retrieved_chunks = retrieve(question, TOP_K)
    prompt = build_prompt(question, retrieved_chunks)
    return call_llm(prompt)


# ---------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------

def cmd_setup_data(_: argparse.Namespace) -> None:
    setup_sample_data()


def cmd_ingest(_: argparse.Namespace) -> None:
    ingest()


def cmd_retrieve(args: argparse.Namespace) -> None:
    chunks = retrieve(args.question, args.top_k)
    print_retrieved(chunks)


def cmd_ask(args: argparse.Namespace) -> None:
    chunks = retrieve(args.question, args.top_k)

    print("\nCHUNK RECUPERATI")
    print_retrieved(chunks)

    prompt = build_prompt(args.question, chunks)

    print("\n" + "=" * 80)
    print("PROMPT COSTRUITO")
    print("=" * 80)
    print(prompt)

    print("\n" + "=" * 80)
    print("RISPOSTA LLM")
    print("=" * 80)
    print(call_llm(prompt))


def cmd_test_llm(_: argparse.Namespace) -> None:
    prompt = "Rispondi solo con: Il più grande insegnante, il fallimento è."
    print(call_llm(prompt))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Esercitazione Giorno 1 mattina: RAG minimale con Chroma e Gemini/Vertex."
    )

    subparsers = parser.add_subparsers(required=True)

    setup_parser = subparsers.add_parser("setup-data")
    setup_parser.set_defaults(func=cmd_setup_data)

    ingest_parser = subparsers.add_parser("ingest")
    ingest_parser.set_defaults(func=cmd_ingest)

    retrieve_parser = subparsers.add_parser("retrieve")
    retrieve_parser.add_argument("question", type=str)
    retrieve_parser.add_argument("--top-k", type=int, default=TOP_K)
    retrieve_parser.set_defaults(func=cmd_retrieve)

    ask_parser = subparsers.add_parser("ask")
    ask_parser.add_argument("question", type=str)
    ask_parser.add_argument("--top-k", type=int, default=TOP_K)
    ask_parser.set_defaults(func=cmd_ask)

    test_parser = subparsers.add_parser("test-llm")
    test_parser.set_defaults(func=cmd_test_llm)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()