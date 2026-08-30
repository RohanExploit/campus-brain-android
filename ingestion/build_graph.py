import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import json
import logging
from pathlib import Path
import networkx as nx
from utils.logging_config import setup_logging

setup_logging()

def build_graph(graph_dir):
    graph_dir = Path(graph_dir)
    entity_file = graph_dir / "extracted_entities.json"

    if not entity_file.exists():
        logging.error("No extracted_entities.json found.")
        return

    with open(entity_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    G = nx.Graph()

    for node in data.get("nodes", []):
        node_id = node.get("id")
        if node_id:
            G.add_node(node_id, type=node.get("type", "Unknown"), source_chunk=node.get("source_chunk"))

    for edge in data.get("edges", []):
        src = edge.get("source")
        tgt = edge.get("target")
        rel = edge.get("relation", "RELATED_TO")
        if src and tgt:
            G.add_edge(src, tgt, relation=rel)

    out_path = graph_dir / "company_brain.graphml"
    nx.write_graphml(G, str(out_path))
    logging.info(f"Built graph with {G.number_of_nodes()} nodes and {G.number_of_edges()} edges. Saved to {out_path}")

if __name__ == "__main__":
    graph_dir = f"{PROJECT_ROOT}/data/tenants/tenant_1/graph"
    build_graph(graph_dir)
