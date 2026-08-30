import sys
from pathlib import Path
import networkx as nx
sys.path.append(str(Path(__file__).resolve().parent.parent))

from config import tenant_dir
from utils.logging_config import setup_logging

setup_logging()

class GraphSearch:
    def __init__(self, tenant_id="tenant_1"):
        self.graph_dir = tenant_dir(tenant_id) / "graph"
        self.graph_path = self.graph_dir / "company_brain.graphml"
        self.G = None
        self.load_graph()

    def load_graph(self):
        if not self.graph_path.exists():
            return
        self.G = nx.read_graphml(str(self.graph_path))

    def get_neighborhood(self, entity_id: str, hops: int = 1):
        if not self.G or entity_id not in self.G:
            return []

        # Get egocentric network
        ego_graph = nx.ego_graph(self.G, entity_id, radius=hops)

        edges = []
        for u, v, data in ego_graph.edges(data=True):
            edges.append(f"{u} -> {data.get('relation', 'RELATED')} -> {v}")

        return edges

if __name__ == "__main__":
    gs = GraphSearch()
    print(gs.get_neighborhood("Company_A", hops=2))
