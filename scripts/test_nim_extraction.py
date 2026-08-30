import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import json
import sys
from pathlib import Path
from dotenv import load_dotenv

project_root = Path(f"{PROJECT_ROOT}")
sys.path.append(str(project_root))
load_dotenv(project_root / ".env")

from ingestion.extract_entities import extract_entities_from_chunk

text = "High-frequency trading (HFT) methods work on sub-second time scales and often cause complicated market dynamics that are difficult to explain. Retrieval-Augmented Generation (RAG) has evolved as a strategy to ground large language models (LLMs) with factual context, including domain-specific financial data[1][2]. However, RAG systems often rely on historical records or textual data and can struggle with rare or hypothetical scenarios[3]. We present RAG-MicroSim, a novel hybrid architecture that augments RAG with an integrated micro-simulation engine. RAG-MicroSim dynamically generates synthetic limit order book (LOB) states and trade events on demand, which are used alongside retrieved historical data. real-time risk assessment and anomaly identification, and permits testing of possible algorithmic behaviors not apparent in past logs.We go into detail on RAG-MicroSim's architecture, market microstructure simulation techniques, and integration plan with the RAG pipeline. In conclusion,"

def run_extract():
    print("Extracting with LLaMA-3.1-70B via NVIDIA API...")
    res = extract_entities_from_chunk(text)
    print(json.dumps(res, indent=2))

if __name__ == "__main__":
    run_extract()
