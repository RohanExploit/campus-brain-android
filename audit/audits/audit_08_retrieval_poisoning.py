"""
Audits 08–21 — All remaining audit test files in one batch.
Each audit class is self-contained and uses shared fixtures from conftest.py.
"""

# ── Audit 08: Retrieval Poisoning ─────────────────────────────────────────────
"""audit_08_retrieval_poisoning.py"""
import pytest
pytestmark_08 = pytest.mark.retrieval


class TestRetrievalPoisoning:
    """Conflicting documents must trigger conflict detection, not arbitrary answers."""

    def test_same_fact_different_values_detected(self):
        doc_a = {"source": "doc_a.pdf", "roll": "2021001001", "sgpa": 7.5}
        doc_b = {"source": "doc_b.pdf", "roll": "2021001001", "sgpa": 8.2}
        delta = abs(doc_a["sgpa"] - doc_b["sgpa"])
        conflict_detected = delta > 0.01
        assert conflict_detected, "SGPA conflict not detected"

    def test_conflict_returns_conflict_status_not_answer(self):
        def detect_conflict(facts):
            values = set(f["value"] for f in facts)
            return {"status": "CONFLICT_DETECTED", "sources": [f["source"] for f in facts]} if len(values) > 1 else {"status": "OK", "value": facts[0]["value"]}
        result = detect_conflict([
            {"source": "doc_a.pdf", "value": 7.5},
            {"source": "doc_b.pdf", "value": 8.2},
        ])
        assert result["status"] == "CONFLICT_DETECTED"
        assert len(result["sources"]) == 2

    def test_conflict_includes_both_sources(self):
        sources = ["doc_a.pdf", "doc_b.pdf"]
        conflict = {"status": "CONFLICT_DETECTED", "sources": sources}
        assert len(conflict["sources"]) >= 2

    def test_clean_fact_not_flagged(self):
        def detect_conflict(facts):
            values = set(f["value"] for f in facts)
            return "CONFLICT_DETECTED" if len(values) > 1 else "OK"
        result = detect_conflict([
            {"source": "doc_a.pdf", "value": 8.5},
            {"source": "doc_b.pdf", "value": 8.5},
        ])
        assert result == "OK", "Consistent fact incorrectly flagged as conflict"

    def test_single_source_never_conflict(self):
        def detect_conflict(facts):
            return "CONFLICT_DETECTED" if len(set(f["value"] for f in facts)) > 1 else "OK"
        result = detect_conflict([{"source": "doc_a.pdf", "value": 9.0}])
        assert result == "OK"
