import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import config
import json
import logging
from pathlib import Path
import requests
from utils.logging_config import setup_logging

setup_logging()

# Read Ollama model + base URL live from config at call time (not frozen into
# module constants at import) so an env override or test monkeypatch applies.

def summarize_community(nodes):
    prompt = f"""
You are an AI assistant analyzing a knowledge graph. Summarize the following cluster of related entities into a concise paragraph.
Entities: {', '.join(nodes)}
    """

    payload = {
        "model": config.OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        # temperature was unset, i.e. Ollama's default 0.8 — so re-running ingestion
        # produced different summaries from the same graph. Every other LLM call in the
        # serving path pins temperature 0; the ingest path is where reproducibility
        # matters most, because its output is persisted and served for weeks.
        "options": {"num_ctx": config.OLLAMA_NUM_CTX, "temperature": 0}
    }

    try:
        response = requests.post(f"{config.OLLAMA_BASE_URL}/api/generate", json=payload, timeout=120)
        response.raise_for_status()
        data = response.json()
        return data["response"].strip()
    except Exception as e:
        logging.error(f"Ollama summarization failed: {e}")
        return "Summary generation failed."

def process_community_summaries(graph_dir):
    graph_dir = Path(graph_dir)
    in_path = graph_dir / "communities.json"

    if not in_path.exists():
        logging.error("communities.json not found.")
        return

    with open(in_path, "r", encoding="utf-8") as f:
        communities = json.load(f)

    summaries = {}

    for c_id, nodes in communities.items():
        logging.info(f"Summarizing {c_id} ({len(nodes)} nodes)...")
        # In a real environment, we'd also pull the context chunks for these nodes
        summary = summarize_community(nodes)
        summaries[c_id] = summary

    out_path = graph_dir / "community_summaries.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(summaries, f, indent=2)

    logging.info(f"Generated {len(summaries)} community summaries. Saved to {out_path}")

if __name__ == "__main__":
    graph_dir = f"{PROJECT_ROOT}/data/tenants/tenant_1/graph"
    process_community_summaries(graph_dir)
