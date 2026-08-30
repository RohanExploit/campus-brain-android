"""Ingest the bench corpus into a fresh tenant_bench.

A NEW tenant on purpose. Re-ingesting tenant_stress would silently move the instrument
that every frozen baseline in docs/PHASE_B_RUN_LOG.md was measured against, and those
comparisons would quietly stop meaning anything. Additive instead: tenant_stress keeps
serving the old numbers, tenant_bench carries the larger one.

Never touches tenant_1, tabular.duckdb or analytics.duckdb.

Usage (repo root, .venv312, Ollama up):
    python tests/eval/bench/ingest_bench.py fact    # copy + chunk + embed + faiss
    python tests/eval/bench/ingest_bench.py graph   # entities + graph + communities
    python tests/eval/bench/ingest_bench.py all
"""
import shutil
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

CORPUS = PROJECT_ROOT / "Dataset" / "bench_v1" / "corpus"
TEN = PROJECT_ROOT / "data" / "tenants" / "tenant_bench"
PARSED, CHUNKED, EMBED, GRAPH = TEN / "parsed", TEN / "chunked", TEN / "embeddings", TEN / "graph"


def stage_fact():
    PARSED.mkdir(parents=True, exist_ok=True)
    n = 0
    for md in sorted(CORPUS.glob("*.md")):
        shutil.copy2(md, PARSED / md.name)
        n += 1
    print(f"[fact] copied {n} documents -> {PARSED}")

    from ingestion.chunk import process_markdown_files
    process_markdown_files(str(PARSED), str(CHUNKED))

    from ingestion.embed import process_chunk_embeddings
    process_chunk_embeddings(str(CHUNKED), str(EMBED))

    # embed.py writes the vectors but not the index; VectorSearch needs faiss.index or it
    # silently returns empty context (and every answer becomes an abstention).
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
