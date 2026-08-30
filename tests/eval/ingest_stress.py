"""Phase-0 instrumentation: ingest the stresskit corpus into a fresh tenant_stress.

The stresskit corpus is already clean markdown, so we skip Docling parse and copy
straight into parsed/. Then run the normal ingestion module functions dir-by-dir.
Never touches tenant_1 or tabular.duckdb — tenant_stress is a throwaway eval tenant.

Usage (from repo root, in .venv312 with Ollama up):
    python tests/eval/ingest_stress.py fact    # copy + chunk + embed + faiss   (FACT route)
    python tests/eval/ingest_stress.py graph   # entities + graph + communities (GLOBAL/LOCAL)
    python tests/eval/ingest_stress.py all
"""
import shutil
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

CORPUS = PROJECT_ROOT / "Dataset" / "Untested stresskit as of 4pm 18-08-2026" / "corpus"
TEN = PROJECT_ROOT / "data" / "tenants" / "tenant_stress"
PARSED, CHUNKED, EMBED, GRAPH = TEN / "parsed", TEN / "chunked", TEN / "embeddings", TEN / "graph"


def stage_fact():
    PARSED.mkdir(parents=True, exist_ok=True)
    n = 0
    for md in sorted(CORPUS.glob("*.md")):
        shutil.copy2(md, PARSED / md.name)
        n += 1
    print(f"[fact] copied {n} corpus md -> {PARSED}")

    from ingestion.chunk import process_markdown_files
    process_markdown_files(str(PARSED), str(CHUNKED))

    from ingestion.embed import process_chunk_embeddings
    process_chunk_embeddings(str(CHUNKED), str(EMBED))

    # embed.py writes .npy/.pkl/.json but NOT faiss.index — VectorSearch requires
    # faiss.index or it returns empty context. Build it (separate pipeline step).
    from ingestion.vector_store import build_faiss_index
    build_faiss_index(str(EMBED))
    print("[fact] embeddings + faiss.index built")


def stage_graph():
    from ingestion.extract_entities import process_extractions
    process_extractions(str(CHUNKED), str(GRAPH))
    from ingestion.build_graph import build_graph
    build_graph(str(GRAPH))
    from ingestion.build_communities import detect_communities
    detect_communities(str(GRAPH))
    from ingestion.summarize_communities import process_community_summaries
    process_community_summaries(str(GRAPH))
    print("[graph] entity graph + community summaries built")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode in ("fact", "all"):
        stage_fact()
    if mode in ("graph", "all"):
        stage_graph()
    print("DONE:", mode)
