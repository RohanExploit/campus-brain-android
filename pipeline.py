import logging
import sys
from pathlib import Path
sys.path.append(str(Path(__file__).resolve().parent))

from config import tenant_dir

from ingestion.parse import main as parse_main
from ingestion.manifest import _get_manifest_conn, _manifest_get, _sha256
from ingestion.chunk import process_markdown_files
from ingestion.embed import process_chunk_embeddings
from ingestion.vector_store import build_faiss_index
from ingestion.extract_entities import process_extractions
from ingestion.build_graph import build_graph
from ingestion.build_communities import detect_communities
from ingestion.summarize_communities import process_community_summaries
from utils.logging_config import setup_logging

setup_logging()

def check_for_changes(raw_dir: str, tenant_id: str) -> bool:
    """True if any file in raw_dir is new, or its sha256 differs from the manifest.

    Reads the manifest through ingestion.parse's helpers rather than defining a
    second schema. The two definitions had drifted apart -- this gate used to
    CREATE a manifest table of (filepath, hash, last_indexed_at) while parse.py
    creates (doc_id, file_hash, parse_status, ...). Whichever ran first won the
    CREATE TABLE IF NOT EXISTS, and on every existing tenant parse.py had, so
    this function raised `no such column: hash` and run_pipeline could not get
    past its own change gate.

    Read-only on purpose. The previous version wrote each file's hash at gate
    time, before parsing had happened, so a crash mid-parse left those files
    recorded as up to date and they were never parsed on any later run.
    parse._manifest_update is now the only writer, and it records a file only
    once Docling has actually returned.

    Keyed on the bare filename because that is what parse.py uses as doc_id.
    Scanned non-recursively for the same reason: run_pipeline only copies the
    top level of raw/ into the parse temp dir, so a nested file is never parsed
    and must not hold the gate permanently open.
    """
    raw_path = Path(raw_dir)
    if not raw_path.exists():
        logging.warning(f"Raw directory {raw_dir} does not exist.")
        return False

    conn = _get_manifest_conn(raw_path.parent)
    try:
        for f in raw_path.iterdir():
            if not f.is_file():
                continue
            row = _manifest_get(conn, f.name)
            if row is None or row["file_hash"] != _sha256(f):
                logging.info("Change detected in %s", f.name)
                return True
        return False
    finally:
        conn.close()

def run_pipeline(tenant_id="tenant_1"):
    _td = tenant_dir(tenant_id)
    raw_dir = str(_td / "raw")
    parsed_dir = str(_td / "parsed")
    chunked_dir = str(_td / "chunked")
    embed_dir = str(_td / "embeddings")
    graph_dir = str(_td / "graph")

    if not check_for_changes(raw_dir, tenant_id):
        logging.info(f"No changes detected in {raw_dir}. Skipping ingestion pipeline for {tenant_id}.")
        return

    logging.info("=== 1. Parsing Documents (Docling) ===")
    import shutil
    import tempfile
    try:
        from utils.encryption import decrypt_file
        encryption_available = True
    except ImportError:
        encryption_available = False

    with tempfile.TemporaryDirectory() as temp_raw_dir:
        # Decrypt files to temp_raw_dir if encryption is available
        raw_path = Path(raw_dir)
        if raw_path.exists():
            for f in raw_path.iterdir():
                if f.is_file():
                    temp_f = Path(temp_raw_dir) / f.name
                    if encryption_available:
                        # Attempt to decrypt. If it fails, maybe it wasn't encrypted, so just copy it.
                        if not decrypt_file(f, temp_f):
                            shutil.copy2(f, temp_f)
                    else:
                        shutil.copy2(f, temp_f)

        parse_main(temp_raw_dir, parsed_dir)

    logging.info("=== 2. Semantic Chunking ===")
    process_markdown_files(parsed_dir, chunked_dir)

    logging.info("=== 3. Embedding Generation ===")
    process_chunk_embeddings(chunked_dir, embed_dir)

    logging.info("=== 4. FAISS Vector Store Indexing ===")
    build_faiss_index(embed_dir)

    logging.info("=== 5. Entity Extraction (Ollama) ===")
    process_extractions(chunked_dir, graph_dir)

    logging.info("=== 6. Graph Construction (NetworkX) ===")
    build_graph(graph_dir)

    logging.info("=== 7. Community Detection (Louvain) ===")
    detect_communities(graph_dir)

    logging.info("=== 8. Community Summarization ===")
    process_community_summaries(graph_dir)

    logging.info("Pipeline Complete!")

if __name__ == "__main__":
    run_pipeline()
