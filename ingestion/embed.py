import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import config
from utils.safe_store import save_embeddings
import json
import logging
import re
from collections import Counter
from pathlib import Path
from sentence_transformers import SentenceTransformer
from utils.logging_config import setup_logging

setup_logging()

# Phase -1.3: bulk-PII guard for the vector index. An email address is the cheap,
# high-precision signal for "this source is third-party personal data" (a payment
# or enrolment CSV rendered to markdown), as opposed to institutional documents.
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")


def bulk_pii_sources(chunks, threshold):
    """Return the set of `source`s whose email-bearing chunk count EXCEEDS
    `threshold`. Such a source is bulk third-party PII and must not enter the FACT
    vector index (retrieval poison + exfiltration risk). A source with only a few
    email-bearing chunks (e.g. a paper's own author-contact line — the ground
    truth for an "author email" FACT question) is kept. threshold<=0 disables."""
    if threshold <= 0:
        return set()
    email_counts = Counter()
    for c in chunks:
        if _EMAIL_RE.search(c.get("page_content", "")):
            email_counts[c.get("metadata", {}).get("source", "?")] += 1
    return {s for s, n in email_counts.items() if n > threshold}

def process_chunk_embeddings(chunked_dir, embed_dir):
    chunked_dir = Path(chunked_dir)
    embed_dir = Path(embed_dir)
    embed_dir.mkdir(parents=True, exist_ok=True)

    logging.info("Loading SentenceTransformer model (all-MiniLM-L6-v2)...")
    model = SentenceTransformer('all-MiniLM-L6-v2')

    chunk_files = list(chunked_dir.glob("*_chunks.json"))
    logging.info(f"Found {len(chunk_files)} chunk files to embed")

    all_chunks = []

    for cfile in chunk_files:
        with open(cfile, "r", encoding="utf-8") as f:
            chunks = json.load(f)
            all_chunks.extend(chunks)

    if not all_chunks:
        logging.warning("No chunks found!")
        return

    # Snapshot every chunked source BEFORE the PII filter, for the coverage assert.
    all_chunk_sources = {c.get("metadata", {}).get("source", "?") for c in all_chunks}

    # Phase -1.3 PII guard: keep bulk third-party PII out of the FACT vector index.
    # Log source names + counts only — never chunk contents.
    drop = bulk_pii_sources(all_chunks, config.VECTOR_PII_EMAIL_BULK_THRESHOLD)
    if drop:
        before = len(all_chunks)
        all_chunks = [
            c for c in all_chunks
            if c.get("metadata", {}).get("source", "?") not in drop
        ]
        logging.warning(
            "PII guard excluded %d chunk(s) from %d bulk-PII source(s) %s "
            "(email-bearing count > threshold=%d); %d chunks remain for indexing.",
            before - len(all_chunks), len(drop), sorted(drop),
            config.VECTOR_PII_EMAIL_BULK_THRESHOLD, len(all_chunks),
        )

    # Coverage assert (Phase-A A.2): every chunked source must either be embedded
    # or intentionally dropped by the PII guard. Catches the stale-index bug where a
    # source was chunked but never entered the served index (ICETIS-2026 brochure).
    def _src(c):
        return c.get("metadata", {}).get("source", "?")
    embedded_sources = {_src(c) for c in all_chunks}
    unexpected = (all_chunk_sources - embedded_sources) - drop
    if unexpected:
        raise AssertionError(
            f"Ingestion coverage gap: {len(unexpected)} chunked source(s) absent from "
            f"the index and NOT dropped by the PII guard: {sorted(unexpected)}. "
            "Every chunked/* source must be embedded or explicitly PII-evicted."
        )
    logging.info("Coverage assert OK: %d sources embedded, %d PII-dropped.",
                 len(embedded_sources), len(drop))

    logging.info(f"Generating embeddings for {len(all_chunks)} total chunks...")
    texts = [c["page_content"] for c in all_chunks]

    embeddings = model.encode(texts, show_progress_bar=True)

    # Save in the safe .npy/.json format (no pickle). keep_pickle=True also writes
    # embeddings.pkl for backward compat with any older reader still expecting it.
    save_embeddings(embed_dir, all_chunks, embeddings, keep_pickle=True)

    logging.info(f"Saved {len(embeddings)} embeddings to {embed_dir} (embeddings.npy + chunks.json)")

if __name__ == "__main__":
    chunked_dir = f"{PROJECT_ROOT}/data/tenants/tenant_1/chunked"
    embed_dir = f"{PROJECT_ROOT}/data/tenants/tenant_1/embeddings"
    process_chunk_embeddings(chunked_dir, embed_dir)
