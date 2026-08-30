"""
Safe deserialization: embeddings load from .npy/.json (no pickle), and the
additive pkl->safe migration never destroys the original pickle.
"""
import pickle
import sys
from pathlib import Path

import numpy as np

sys.path.append(str(Path(__file__).resolve().parent.parent))
from utils import safe_store


def _mk_chunks():
    return [
        {"page_content": "hello world", "metadata": {"source": "a.pdf", "page": 1}},
        {"page_content": "second chunk", "metadata": {"source": "b.pdf", "page": 2}},
    ]


def test_save_and_load_roundtrip(tmp_path):
    chunks = _mk_chunks()
    emb = np.random.rand(2, 8).astype("float32")
    safe_store.save_embeddings(tmp_path, chunks, emb)

    assert (tmp_path / safe_store.NPY_NAME).exists()
    assert (tmp_path / safe_store.JSON_NAME).exists()
    assert not (tmp_path / safe_store.PKL_NAME).exists()  # no pickle written by default

    out = safe_store.load_embeddings(tmp_path)
    assert out["chunks"] == chunks
    np.testing.assert_allclose(out["embeddings"], emb)
    assert safe_store.load_chunks(tmp_path) == chunks


def test_load_prefers_safe_over_pickle(tmp_path):
    safe_chunks = [{"page_content": "SAFE", "metadata": {}}]
    pkl_chunks = [{"page_content": "PICKLE", "metadata": {}}]
    safe_store.save_embeddings(tmp_path, safe_chunks, np.zeros((1, 4), dtype="float32"))
    with open(tmp_path / safe_store.PKL_NAME, "wb") as f:
        pickle.dump({"chunks": pkl_chunks, "embeddings": np.zeros((1, 4))}, f)

    assert safe_store.load_chunks(tmp_path) == safe_chunks  # safe wins


def test_pickle_fallback_when_no_safe(tmp_path):
    pkl_chunks = [{"page_content": "legacy", "metadata": {}}]
    with open(tmp_path / safe_store.PKL_NAME, "wb") as f:
        pickle.dump({"chunks": pkl_chunks, "embeddings": np.ones((1, 4))}, f)

    out = safe_store.load_embeddings(tmp_path)
    assert out["chunks"] == pkl_chunks


def test_migration_is_additive_and_nondestructive(tmp_path):
    chunks = _mk_chunks()
    emb = np.arange(16, dtype="float32").reshape(2, 8)
    pkl_path = tmp_path / safe_store.PKL_NAME
    with open(pkl_path, "wb") as f:
        pickle.dump({"chunks": chunks, "embeddings": emb}, f)
    original_bytes = pkl_path.read_bytes()

    status = safe_store.migrate_pickle_to_safe(tmp_path)
    assert status == "migrated"
    # sidecars created
    assert (tmp_path / safe_store.NPY_NAME).exists()
    assert (tmp_path / safe_store.JSON_NAME).exists()
    # original pickle UNTOUCHED (retention hold)
    assert pkl_path.exists()
    assert pkl_path.read_bytes() == original_bytes
    # migrated data matches
    out = safe_store.load_embeddings(tmp_path)
    np.testing.assert_allclose(out["embeddings"], emb)
    assert out["chunks"] == chunks

    assert safe_store.migrate_pickle_to_safe(tmp_path) == "already-safe"


def test_migration_no_pickle(tmp_path):
    assert safe_store.migrate_pickle_to_safe(tmp_path) == "no-pickle"
