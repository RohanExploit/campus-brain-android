"""Concurrent /query requests must not crash the server.

FastAPI runs sync route handlers in a threadpool, and VectorSearch shares one
SentenceTransformer and one FAISS index across every request. Neither is thread-safe.
Two overlapping queries could therefore enter model.encode() or index.search() at the
same time, and on Windows that produced a hard SIGSEGV (exit 139) that took the whole
API down mid-request — the log ended on a truncated progress bar with no traceback.

Found by pointing four concurrent clients at a single uvicorn worker during a demo
rehearsal. A crash under concurrency is not an edge case for a product that is meant to
be shown to a room of people, so the encode and search calls are serialised and this
test holds that line.
"""
import sys
import threading
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import retrieval.vector_search as vs_mod  # noqa: E402


def test_module_exposes_a_lock_around_the_shared_model():
    """The shared model and index are process-global; the guard must be too."""
    assert isinstance(vs_mod._ENCODE_LOCK, type(threading.Lock()))


class _FakeModel:
    """Fails loudly if two threads are inside encode() at once.

    A real race here is a segfault, which no assertion can catch — so the fake makes
    the overlap observable instead, and the lock is what prevents it.
    """

    def __init__(self):
        self.inside = 0
        self.overlaps = 0
        self.calls = 0
        self._guard = threading.Lock()

    def encode(self, queries):
        import time
        with self._guard:
            self.inside += 1
            self.calls += 1
            if self.inside > 1:
                self.overlaps += 1
        time.sleep(0.01)          # widen the window a real encode would occupy
        with self._guard:
            self.inside -= 1
        import numpy as np
        return np.zeros((1, 384), dtype="float32")


class _FakeIndex:
    ntotal = 1

    def search(self, vec, top_k):
        import numpy as np
        return np.zeros((1, top_k), dtype="float32"), np.full((1, top_k), -1)


def _make_vs():
    vs = vs_mod.VectorSearch.__new__(vs_mod.VectorSearch)   # skip disk I/O
    vs.model = _FakeModel()
    vs.index = _FakeIndex()
    vs.chunks = []
    vs._query_cache = {}
    return vs


def test_concurrent_searches_never_overlap_inside_encode():
    vs = _make_vs()
    errors = []

    def hammer(n):
        try:
            for i in range(4):
                vs.search(f"query {n}-{i}")     # distinct strings defeat the cache
        except Exception as e:                  # noqa: BLE001
            errors.append(e)

    threads = [threading.Thread(target=hammer, args=(n,)) for n in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)

    assert not errors, f"concurrent search raised: {errors}"
    assert vs.model.calls == 32, f"expected 32 encodes, got {vs.model.calls}"
    assert vs.model.overlaps == 0, (
        f"{vs.model.overlaps} concurrent entries into encode() — the lock is not holding, "
        "and in production this is a segfault rather than a failed assertion"
    )


def test_the_query_cache_still_works_under_the_lock():
    """Serialising must not defeat the cache — the same query encodes once."""
    vs = _make_vs()
    for _ in range(5):
        vs.search("identical question")
    assert vs.model.calls == 1
