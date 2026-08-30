"""
Safe (non-pickle) on-disk format for embeddings + chunks.

Motivation: pickle.load() executes arbitrary code from the file. Loading
embeddings.pkl is a deserialization risk. This module stores the same data as:
  - embeddings.npy          (numpy binary — data only, no code execution)
  - embeddings_chunks.json  (chunk dicts — plain JSON)

Readers PREFER the safe format and fall back to the legacy pickle only if the
safe files are absent. Writers emit the safe format.

RETENTION NOTE: this module never deletes, moves, or overwrites embeddings.pkl.
migrate_pickle_to_safe() only *adds* the .npy/.json sidecars next to it.
"""
import json
import logging
from pathlib import Path

import numpy as np

NPY_NAME = "embeddings.npy"
JSON_NAME = "embeddings_chunks.json"
PKL_NAME = "embeddings.pkl"


def save_embeddings(embed_dir, chunks, embeddings, keep_pickle: bool = False) -> None:
    """Write embeddings.npy + embeddings_chunks.json. Optionally also legacy pkl."""
    embed_dir = Path(embed_dir)
    embed_dir.mkdir(parents=True, exist_ok=True)
    np.save(embed_dir / NPY_NAME, np.asarray(embeddings))
    with open(embed_dir / JSON_NAME, "w", encoding="utf-8") as f:
        json.dump(chunks, f, ensure_ascii=False, default=str)
    if keep_pickle:
        import pickle
        with open(embed_dir / PKL_NAME, "wb") as f:
            pickle.dump({"chunks": chunks, "embeddings": np.asarray(embeddings)}, f)


def has_safe(embed_dir) -> bool:
    embed_dir = Path(embed_dir)
    return (embed_dir / NPY_NAME).exists() and (embed_dir / JSON_NAME).exists()


def load_embeddings(embed_dir) -> dict:
    """
    Return {"embeddings": np.ndarray|None, "chunks": list}.
    Prefers the safe .npy/.json format; falls back to legacy pickle with a warning.
    """
    embed_dir = Path(embed_dir)
    if has_safe(embed_dir):
        embeddings = np.load(embed_dir / NPY_NAME)
        with open(embed_dir / JSON_NAME, "r", encoding="utf-8") as f:
            chunks = json.load(f)
        return {"embeddings": embeddings, "chunks": chunks}

    pkl = embed_dir / PKL_NAME
    if pkl.exists():
        logging.warning(
            "safe_store: falling back to legacy pickle at %s (untrusted deserialization). "
            "Run migrate_pickle_to_safe() to generate safe sidecars.", pkl
        )
        import pickle
        with open(pkl, "rb") as f:
            data = pickle.load(f)
        return {"embeddings": data.get("embeddings"), "chunks": data.get("chunks", [])}

    return {"embeddings": None, "chunks": []}


def load_chunks(embed_dir) -> list:
    return load_embeddings(embed_dir).get("chunks", [])


def migrate_pickle_to_safe(embed_dir) -> str:
    """
    Additively generate .npy/.json sidecars from an existing embeddings.pkl.
    NEVER modifies/moves/deletes the pkl. Returns a status string.
    """
    embed_dir = Path(embed_dir)
    if has_safe(embed_dir):
        return "already-safe"
    pkl = embed_dir / PKL_NAME
    if not pkl.exists():
        return "no-pickle"
    import pickle
    with open(pkl, "rb") as f:            # one-time read of our own trusted file
        data = pickle.load(f)
    save_embeddings(embed_dir, data.get("chunks", []), data.get("embeddings"))
    return "migrated"
