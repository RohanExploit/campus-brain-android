import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import json
import logging
from pathlib import Path
import requests

logging.basicConfig(level=logging.INFO)

OLLAMA_API_URL = "http://localhost:11434/api/generate"
MODEL_NAME = "qwen3:4b-instruct-2507-q4_K_M"

def extract_entities_from_chunk(text: str) -> dict:
    entity_types = "Person, Organization, Location, Concept, Event, Data, System"
    prompt = f"""
You are an intelligent assistant that helps a human analyst extract knowledge graph entities and relationships from a text document.

-Goal-
Given a text document, extract all entities that match the entity specification and all relationships between them.
Entity specification: {entity_types}

-Steps-
1. Extract all named entities that match the predefined entity specification.
2. For each entity, identify if it has any relationships with other extracted entities.

Output strictly as a JSON object with 'nodes' and 'edges'.

-Examples-
Example 1:
Text: Alice is the CEO of Acme Corp, which is headquartered in London.
Output:
{{
  "nodes": [
    {{"id": "Alice", "type": "Person"}},
    {{"id": "Acme Corp", "type": "Organization"}},
    {{"id": "London", "type": "Location"}}
  ],
  "edges": [
    {{"source": "Alice", "target": "Acme Corp", "relation": "CEO_OF"}},
    {{"source": "Acme Corp", "target": "London", "relation": "HEADQUARTERED_IN"}}
  ]
}}

Example 2:
Text: High-frequency trading (HFT) causes market volatility.
Output:
{{
  "nodes": [
    {{"id": "High-frequency trading", "type": "Concept"}},
    {{"id": "market volatility", "type": "Concept"}}
  ],
  "edges": [
    {{"source": "High-frequency trading", "target": "market volatility", "relation": "CAUSES"}}
  ]
}}

-Real Data-
Text: {text}
Output:"""

    payload = {
        "model": MODEL_NAME,
        "prompt": prompt,
        "stream": False,
        "format": "json",
        "options": {
            "num_ctx": 2048
        }
    }

    try:
        response = requests.post(OLLAMA_API_URL, json=payload, timeout=600)
        response.raise_for_status()
        data = response.json()

        try:
            return json.loads(data["response"])
        except Exception:
            logging.error(f"Failed to parse JSON from Ollama. Raw output: {data.get('response', '')}")
            return {"nodes": [], "edges": []}
    except Exception as e:
        logging.error(f"Ollama extraction failed: {e}")
        return {"nodes": [], "edges": []}

def process_extractions(chunked_dir, graph_dir):
    chunked_dir = Path(chunked_dir)
    graph_dir = Path(graph_dir)
    graph_dir.mkdir(parents=True, exist_ok=True)

    chunk_files = list(chunked_dir.glob("*_chunks.json"))

    # Filter out testing datasets (CSV and FUNSD) and synthetic template files
    excluded_keywords = []
    chunk_files = [f for f in chunk_files if not any(kw in f.name.lower() for kw in excluded_keywords)]

    logging.info(f"Extracting entities from {len(chunk_files)} chunk files...")

    all_nodes = []
    all_edges = []

    chunk_count = 0
    for cfile in chunk_files:
        with open(cfile, "r", encoding="utf-8") as f:
            chunks = json.load(f)

        for chunk in chunks:
            logging.info(f"Extracting entities from chunk {chunk_count+1}...")
            result = extract_entities_from_chunk(chunk["page_content"])
            chunk_count += 1

            # Decorate with source chunks
            for node in result.get("nodes", []):
                if isinstance(node, str):
                    node = {"id": node, "type": "Entity"}
                elif not isinstance(node, dict):
                    continue
                node["source_chunk"] = chunk["metadata"].get("chunk_index")
                node["source_file"] = cfile.name
                all_nodes.append(node)

            for edge in result.get("edges", []):
                if isinstance(edge, dict):
                    edge["source_chunk"] = chunk["metadata"].get("chunk_index")
                    edge["source_file"] = cfile.name
                    all_edges.append(edge)

    out_path = graph_dir / "extracted_entities.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"nodes": all_nodes, "edges": all_edges}, f, indent=2)

    logging.info(f"Saved {len(all_nodes)} nodes and {len(all_edges)} edges to {out_path}")

if __name__ == "__main__":
    chunked_dir = f"{PROJECT_ROOT}/data/tenants/tenant_2/chunked"
    graph_dir = f"{PROJECT_ROOT}/data/tenants/tenant_2/graph"
    process_extractions(chunked_dir, graph_dir)
