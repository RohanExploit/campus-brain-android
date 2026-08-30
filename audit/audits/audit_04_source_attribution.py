"""
Audit 04 — Source Attribution
Goal: Every answer must include source document, page, record identifier,
      and verification status.
Pass criterion: 100% attribution coverage on non-trivial answers.
"""
import pytest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

pytestmark = pytest.mark.retrieval

REQUIRED_ATTRIBUTION_FIELDS = [
    "source_doc",
    "page",
    "record_id",
    "verification_status",
]

VALID_VERIFICATION_STATUSES = {
    "VERIFIED",
    "UNVERIFIED",
    "CONFLICT",
    "INSUFFICIENT_EVIDENCE",
}

# ─── Mock query results (replace with real API calls for integration tests) ───

def _mock_query_with_attribution(query: str) -> dict:
    """
    Returns a response dict with `answer` and `metadata` fields.
    Metadata must contain attribution fields for production compliance.
    """
    return {
        "answer": "Student 2021001001 has SGPA 8.5 as recorded in result_sem3.pdf.",
        "metadata": {
            "source_doc": "result_sem3.pdf",
            "page": "3",
            "record_id": "2021001001",
            "verification_status": "VERIFIED",
            "query_type": "TABULAR",
        }
    }


def _mock_empty_response(query: str) -> dict:
    """Simulates a response with no attribution (should be caught)."""
    return {
        "answer": "The average SGPA is 8.2.",
        "metadata": {}  # Missing attribution — should fail audit
    }


# ─── Tests ────────────────────────────────────────────────────────────────────

class TestSourceAttribution:

    def test_response_has_source_doc(self):
        """Every answer must name the source document."""
        result = _mock_query_with_attribution("What is the SGPA of roll 2021001001?")
        assert "source_doc" in result["metadata"] and result["metadata"]["source_doc"], \
            "Missing 'source_doc' in response metadata"

    def test_response_has_page_reference(self):
        """Every answer must include a page reference."""
        result = _mock_query_with_attribution("What is the SGPA of roll 2021001001?")
        assert "page" in result["metadata"] and result["metadata"]["page"], \
            "Missing 'page' in response metadata"

    def test_response_has_record_id(self):
        """Every answer must include a record identifier (e.g., roll number)."""
        result = _mock_query_with_attribution("What is the SGPA of roll 2021001001?")
        assert "record_id" in result["metadata"] and result["metadata"]["record_id"], \
            "Missing 'record_id' in response metadata"

    def test_response_has_verification_status(self):
        """Every answer must carry a verification_status."""
        result = _mock_query_with_attribution("What is the SGPA of roll 2021001001?")
        status = result["metadata"].get("verification_status")
        assert status, "Missing 'verification_status' in response metadata"
        assert status in VALID_VERIFICATION_STATUSES, (
            f"verification_status '{status}' not in allowed set {VALID_VERIFICATION_STATUSES}"
        )

    @pytest.mark.parametrize("field", REQUIRED_ATTRIBUTION_FIELDS)
    def test_all_required_attribution_fields_present(self, field):
        """Parametrized: check each attribution field individually."""
        result = _mock_query_with_attribution("SGPA of student 2021001001?")
        assert field in result["metadata"], \
            f"Attribution field '{field}' missing from response metadata"
        assert result["metadata"][field], \
            f"Attribution field '{field}' is empty in response metadata"

    def test_missing_attribution_is_detected(self):
        """A response without attribution metadata must be detectable."""
        result = _mock_empty_response("What is the average SGPA?")
        missing = [
            f for f in REQUIRED_ATTRIBUTION_FIELDS
            if f not in result["metadata"] or not result["metadata"].get(f)
        ]
        assert len(missing) == len(REQUIRED_ATTRIBUTION_FIELDS), (
            f"Expected all attribution fields missing, but only {missing} were absent"
        )

    def test_verification_status_not_unknown(self):
        """Verification status must be an explicit known value, never None or 'UNKNOWN'."""
        result = _mock_query_with_attribution("SGPA of 2021001001?")
        status = result["metadata"].get("verification_status", "")
        assert status != "UNKNOWN" and status != "", \
            "Verification status must not be UNKNOWN or empty"

    def test_source_doc_extension_is_valid(self):
        """Source document must reference a real file extension."""
        valid_extensions = {".pdf", ".docx", ".xlsx", ".pptx", ".csv", ".txt"}
        result = _mock_query_with_attribution("SGPA of 2021001001?")
        src = result["metadata"].get("source_doc", "")
        if src:
            ext = "." + src.rsplit(".", 1)[-1].lower() if "." in src else ""
            assert ext in valid_extensions, (
                f"source_doc '{src}' has unexpected extension '{ext}'"
            )

    def test_batch_attribution_coverage(self):
        """
        Simulate 10 queries and verify 100% attribution coverage.
        All must have required fields.
        """
        queries = [
            "SGPA of 2021001001?",
            "Branch of Priya Patel?",
            "How many students failed BT301?",
            "List students with SGPA above 8.0",
            "What is the attendance of Sneha Nair?",
            "Who has the highest CGPA?",
            "Subjects taken by 2021001002?",
            "Grade in BT302 for roll 2021001001?",
            "How many students have backlogs?",
            "Which students graduated?",
        ]
        failures = []
        for q in queries:
            result = _mock_query_with_attribution(q)
            missing = [
                f for f in REQUIRED_ATTRIBUTION_FIELDS
                if f not in result["metadata"] or not result["metadata"].get(f)
            ]
            if missing:
                failures.append({"query": q, "missing_fields": missing})

        assert not failures, (
            f"{len(failures)}/10 queries missing attribution: {failures}"
        )
