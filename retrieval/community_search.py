import json
import sys
from pathlib import Path
sys.path.append(str(Path(__file__).resolve().parent.parent))

from config import tenant_dir
from utils.logging_config import setup_logging

setup_logging()

class CommunitySearch:
    def __init__(self, tenant_id="tenant_1"):
        self.graph_dir = tenant_dir(tenant_id) / "graph"
        self.summaries_path = self.graph_dir / "community_summaries.json"
        self.summaries = {}
        self.load_summaries()

    def load_summaries(self):
        if not self.summaries_path.exists():
            return
        with open(self.summaries_path, "r", encoding="utf-8") as f:
            self.summaries = json.load(f)

    def get_all_summaries(self):
        # Return all summaries to the LLM for global reasoning
        return "\n\n".join([f"Community {k}:\n{v}" for k, v in self.summaries.items()])

if __name__ == "__main__":
    cs = CommunitySearch()
    print(cs.get_all_summaries())
