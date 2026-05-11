"""
download_corpus.py

Scarica un corpus pubblico per esercitazione bonus Giorno 1:
- NIST AI RMF
- NIST Generative AI Profile
- OWASP LLM Top 10 2025
- EU AI Act official publication page/PDF, se disponibile
- Google Cloud Responsible AI page
- Google SRE selected pages/resources

Nota:
alcuni link ufficiali possono cambiare. Se un download fallisce, il gruppo
deve registrarlo nel log e usare le fonti rimanenti.
"""

from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urlparse

import requests
from bs4 import BeautifulSoup
from pypdf import PdfReader


BASE_DIR = Path(__file__).parent.resolve()
RAW_DIR = BASE_DIR / "corpus" / "raw"
EXTRACTED_DIR = BASE_DIR / "corpus" / "extracted"

RAW_DIR.mkdir(parents=True, exist_ok=True)
EXTRACTED_DIR.mkdir(parents=True, exist_ok=True)


SOURCES = [
    {
        "id": "nist_ai_rmf_1_0",
        "title": "NIST AI Risk Management Framework 1.0",
        "domain": "governance",
        "type": "pdf",
        "url": "https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=936225",
        "filename": "nist_ai_rmf_1_0.pdf",
    },
    {
        "id": "nist_genai_profile_600_1",
        "title": "NIST AI RMF Generative AI Profile",
        "domain": "governance",
        "type": "pdf",
        "url": "https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=958388",
        "filename": "nist_genai_profile_600_1.pdf",
    },
    {
        "id": "owasp_llm_top10_2025",
        "title": "OWASP Top 10 for LLM Applications 2025",
        "domain": "security",
        "type": "pdf",
        "url": "https://owasp.org/www-project-top-10-for-large-language-model-applications/assets/PDF/OWASP-Top-10-for-LLMs-v2025.pdf",
        "filename": "owasp_llm_top10_2025.pdf",
    },
    {
        "id": "google_vertex_responsible_ai",
        "title": "Google Cloud Responsible AI for Vertex AI",
        "domain": "responsible_ai",
        "type": "html",
        "url": "https://cloud.google.com/vertex-ai/generative-ai/docs/learn/responsible-ai",
        "filename": "google_vertex_responsible_ai.html",
    },
    {
        "id": "google_sre_service_level_objectives",
        "title": "Google SRE Book - Service Level Objectives",
        "domain": "reliability",
        "type": "html",
        "url": "https://sre.google/sre-book/service-level-objectives/",
        "filename": "google_sre_service_level_objectives.html",
    },
    {
        "id": "google_sre_monitoring_distributed_systems",
        "title": "Google SRE Book - Monitoring Distributed Systems",
        "domain": "reliability",
        "type": "html",
        "url": "https://sre.google/sre-book/monitoring-distributed-systems/",
        "filename": "google_sre_monitoring_distributed_systems.html",
    },
    {
        "id": "google_sre_managing_incidents",
        "title": "Google SRE Book - Managing Incidents",
        "domain": "reliability",
        "type": "html",
        "url": "https://sre.google/sre-book/managing-incidents/",
        "filename": "google_sre_managing_incidents.html",
    },
]


def download_file(url: str, target: Path) -> bool:
    print(f"Downloading: {url}")
    headers = {
        "User-Agent": "Mozilla/5.0 educational-rag-downloader"
    }

    try:
        response = requests.get(url, headers=headers, timeout=45)
        response.raise_for_status()
    except Exception as exc:
        print(f"FAILED: {url}")
        print(f"Reason: {exc}")
        return False

    target.write_bytes(response.content)
    print(f"Saved: {target}")
    return True


def clean_text(text: str) -> str:
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def extract_pdf_text(path: Path) -> str:
    reader = PdfReader(str(path))
    pages = []

    for i, page in enumerate(reader.pages, start=1):
        page_text = page.extract_text() or ""
        page_text = clean_text(page_text)

        if page_text:
            pages.append(f"\n\n[PAGE {i}]\n{page_text}")

    return "\n".join(pages).strip()


def extract_html_text(path: Path) -> str:
    html = path.read_text(encoding="utf-8", errors="ignore")
    soup = BeautifulSoup(html, "html.parser")

    for tag in soup(["script", "style", "nav", "footer", "header"]):
        tag.decompose()

    title = soup.title.string.strip() if soup.title and soup.title.string else path.stem
    body_text = soup.get_text(separator="\n")
    body_text = clean_text(body_text)

    return f"# {title}\n\n{body_text}"


def write_extracted_text(source: dict, raw_path: Path) -> None:
    if source["type"] == "pdf":
        text = extract_pdf_text(raw_path)
    elif source["type"] == "html":
        text = extract_html_text(raw_path)
    else:
        raise ValueError(f"Tipo non supportato: {source['type']}")

    metadata_header = f"""
SOURCE_ID: {source['id']}
TITLE: {source['title']}
DOMAIN: {source['domain']}
TYPE: {source['type']}
URL: {source['url']}
""".strip()

    output = metadata_header + "\n\n" + text

    extracted_path = EXTRACTED_DIR / f"{source['id']}.txt"
    extracted_path.write_text(output, encoding="utf-8")

    print(f"Extracted text: {extracted_path}")


def main() -> None:
    for source in SOURCES:
        raw_path = RAW_DIR / source["filename"]

        if not raw_path.exists():
            ok = download_file(source["url"], raw_path)
            if not ok:
                continue
        else:
            print(f"Already exists: {raw_path}")

        try:
            write_extracted_text(source, raw_path)
        except Exception as exc:
            print(f"Extraction failed for {raw_path}: {exc}")

    print("\nDone.")
    print(f"Raw files       : {RAW_DIR}")
    print(f"Extracted texts : {EXTRACTED_DIR}")


if __name__ == "__main__":
    main()