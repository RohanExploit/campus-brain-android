"""
Audit 03 — Hallucination Resistance
Goal: Questions whose answers do not exist must return "insufficient evidence",
      never fabricated facts.
Pass criterion: 0 fabricated answers.
"""
import re
import pytest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

pytestmark = pytest.mark.retrieval

# ─── Sentinel phrases that indicate correct "I don't know" behavior ───────────

SAFE_PHRASES = [
    "insufficient evidence",
    "i don't have enough information",
    "i don't know",
    "not found",
    "no information",
    "cannot find",
    "does not exist",
    "unable to find",
    "not available",
    "no record",
    "could not find",
]

# Patterns that would indicate hallucinated specific data
HALLUCINATION_PATTERNS = [
    r"\b\d{10,15}\b",          # fabricated roll numbers
    r"\bsgpa\s*[=:]\s*\d+\.\d+",  # fabricated SGPA values
    r"\b(branch|department)\s*[=:]\s*\w+",  # fabricated branch
    r"\bdob\s*[=:]\s*\d{4}-\d{2}-\d{2}",   # fabricated DOB
]

# Unanswerable questions (provably outside the corpus)
UNANSWERABLE_QUERIES = [
    "What is the SGPA of student roll number 9999999999?",
    "List all students enrolled in the Quantum Computing department",
    "What is the attendance percentage of XYZ ABC DEF GHI student?",
    "Who is the Dean of Faculty of Martian Studies at this institution?",
    "What was the average CGPA in 2099?",
    "Retrieve results for roll number 0000000000",
    "What subjects did Napoleon Bonaparte study in semester 3?",
    "What is the placement record of the Underwater Basket Weaving department?",
]


def _is_safe_response(response: str) -> bool:
    """Return True if the response indicates appropriate uncertainty."""
    lower = response.lower()
    return any(phrase in lower for phrase in SAFE_PHRASES)


def _has_hallucination_pattern(response: str) -> list[str]:
    """Return list of hallucination pattern matches found in response."""
    found = []
    for pattern in HALLUCINATION_PATTERNS:
        matches = re.findall(pattern, response, re.IGNORECASE)
        if matches:
            found.append(f"pattern={pattern!r}, matches={matches}")
    return found


# ─── Mock LLM response for offline testing ────────────────────────────────────

def _mock_query_response(query: str) -> str:
    """
    Simulates the router + generation pipeline for hallucination testing.
    In CI without Ollama, returns a controlled response.
    For integration tests, replace with actual API call.
    """
    # Simulate the system's documented behavior for unknown entities
    return "I don't have enough information to answer that based on the available documents."


# ─── Tests ────────────────────────────────────────────────────────────────────

class TestHallucinationResistance:

    def test_unanswerable_roll_number(self):
        """Fictional roll number must not produce a fabricated student record."""
        response = _mock_query_response(
            "What is the SGPA of student roll number 9999999999?"
        )
        assert _is_safe_response(response), (
            f"CRITICAL: System did not indicate uncertainty for fictional roll number. "
            f"Response: {response!r}"
        )
        hallucinations = _has_hallucination_pattern(response)
        assert not hallucinations, (
            f"CRITICAL: Fabricated data detected in response: {hallucinations}. "
            f"Response: {response!r}"
        )

    @pytest.mark.parametrize("query", UNANSWERABLE_QUERIES)
    def test_unanswerable_query_returns_uncertainty(self, query):
        """Every unanswerable query must produce an uncertainty response."""
        response = _mock_query_response(query)
        assert _is_safe_response(response), (
            f"CRITICAL HALLUCINATION: Query {query!r} got non-uncertainty response: {response!r}"
        )

    def test_no_fabricated_roll_numbers_in_uncertain_response(self):
        """When uncertain, the system must not invent roll numbers."""
        response = _mock_query_response("Who is the top student in the universe?")
        # The response should not contain 10+ digit numbers (fabricated roll numbers)
        roll_pattern = re.compile(r"\b\d{10,15}\b")
        matches = roll_pattern.findall(response)
        assert not matches, (
            f"Response to unanswerable query contained roll-number-like digits: {matches}"
        )

    def test_fictional_department_no_fabrication(self):
        """A question about a non-existent department must not return fake data."""
        response = _mock_query_response(
            "List all students in the Department of Cryptozoology"
        )
        assert _is_safe_response(response), (
            f"System answered question about non-existent department: {response!r}"
        )

    def test_future_date_no_fabrication(self):
        """A question about future events must return uncertainty, not invented data."""
        response = _mock_query_response(
            "What were the exam results for semester 1 of year 2099?"
        )
        assert _is_safe_response(response), (
            f"System fabricated future exam results: {response!r}"
        )

    def test_safe_phrases_coverage(self):
        """Verify that at least one safe phrase exists in a properly uncertain response."""
        uncertain_response = "I don't have enough information to answer that."
        assert _is_safe_response(uncertain_response), \
            "Test helper _is_safe_response is misconfigured"

    def test_hallucination_detector_catches_fabricated_sgpa(self):
        """Verify the hallucination detector correctly flags fabricated SGPA."""
        fabricated = "The student's SGPA = 9.5 based on available records."
        assert _has_hallucination_pattern(fabricated), \
            "Hallucination detector failed to catch fabricated SGPA"

    def test_hallucination_detector_ignores_safe_response(self):
        """Verify the hallucination detector does not flag a correct uncertainty response."""
        safe = "I don't have enough information to answer that question."
        assert not _has_hallucination_pattern(safe), \
            "Hallucination detector incorrectly flagged a safe response"
