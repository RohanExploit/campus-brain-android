import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
from utils.safe_store import load_embeddings, has_safe
import logging
import faiss
from pathlib import Path
from utils.logging_config import setup_logging

setup_logging()

def build_faiss_index(embed_dir):
    embed_dir = Path(embed_dir)

    # Prefer safe .npy/.json; falls back to legacy pickle inside load_embeddings.
    if not has_safe(embed_dir) and not (embed_dir / "embeddings.pkl").exists():
        logging.error("No embeddings found to index.")
        return

    data = load_embeddings(embed_dir)
    embeddings = data["embeddings"]

    if embeddings is None or len(embeddings) == 0:
        logging.error("Empty embeddings array.")
        return

    dimension = embeddings.shape[1]

    # Using L2 distance FAISS index
    index = faiss.IndexFlatL2(dimension)

    # Normalize embeddings for cosine similarity (optional, but good for MiniLM)
    faiss.normalize_L2(embeddings)

    index.add(embeddings)

    # Save the index
    faiss_path = embed_dir / "faiss.index"
    faiss.write_index(index, str(faiss_path))

    logging.info(f"Built FAISS index with {index.ntotal} vectors of dimension {dimension}.")

if __name__ == "__main__":
    embed_dir = f"{PROJECT_ROOT}/data/tenants/tenant_1/embeddings"
    build_faiss_index(embed_dir)
