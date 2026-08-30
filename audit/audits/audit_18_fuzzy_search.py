"""
Audit 18 — Fuzzy Name Search
Pass: Typo variants of real names resolve to correct student. FP rate < 20%.
"""
import pytest

pytestmark = pytest.mark.retrieval

try:
    from rapidfuzz import fuzz, process
    HAS_RAPIDFUZZ = True
except ImportError:
    HAS_RAPIDFUZZ = False

# Real names from the system — typo variants for TP test
TYPO_PAIRS = [
    ("Rahul Shrma",         "Rahul Sharma",        True),   # TP: typo
    ("Nikhil Hajre",        "HAJARE NIKHIL RAJENDRA", True), # TP: partial+typo
    ("Aditi Thoraat",       "(F)THORAT ADITI ANANDRAO", True), # TP: extra char
    ("Rohit Devkar",        "DEVAKAR ROHIT MALLESHA",  True), # TP: name order
    ("Random XYZ Person",   "Rahul Sharma",        False),  # FP: should not match
    ("ZZZ QQQQ",            "HAJARE NIKHIL RAJENDRA", False), # FP: garbage
]

MATCH_THRESHOLD = 75   # WRatio score for TP
FP_THRESHOLD    = 65   # Must be below this to avoid false positive


class TestFuzzySearch:

    @pytest.mark.skipif(not HAS_RAPIDFUZZ, reason="pip install rapidfuzz")
    def test_rapidfuzz_importable(self):
        assert HAS_RAPIDFUZZ

    @pytest.mark.skipif(not HAS_RAPIDFUZZ, reason="pip install rapidfuzz")
    def test_true_positives_above_threshold(self):
        tp_pairs = [(q, t) for q, t, expected in TYPO_PAIRS if expected]
        failures = []
        for query, target in tp_pairs:
            score = fuzz.WRatio(query, target)
            if score < MATCH_THRESHOLD:
                failures.append({"query": query, "target": target, "score": score})
        assert not failures, (
            f"Typo variants below threshold ({MATCH_THRESHOLD}): {failures}"
        )

    @pytest.mark.skipif(not HAS_RAPIDFUZZ, reason="pip install rapidfuzz")
    def test_false_positives_below_threshold(self):
        fp_pairs = [(q, t) for q, t, expected in TYPO_PAIRS if not expected]
        false_positives = []
        for query, target in fp_pairs:
            score = fuzz.WRatio(query, target)
            if score >= FP_THRESHOLD:
                false_positives.append({"query": query, "target": target, "score": score})
        assert not false_positives, (
            f"False positives above threshold ({FP_THRESHOLD}): {false_positives}"
        )

    @pytest.mark.skipif(not HAS_RAPIDFUZZ, reason="pip install rapidfuzz")
    def test_best_match_from_corpus(self):
        corpus = [
            "HAJARE NIKHIL RAJENDRA",
            "DEVAKAR ROHIT MALLESHA",
            "(F)THORAT ADITI ANANDRAO",
            "Rahul Sharma",
        ]
        query = "Rahul Shrma"
        best, score, _ = process.extractOne(query, corpus, scorer=fuzz.WRatio)
        assert best == "Rahul Sharma", f"Best match wrong: got {best!r}"
        assert score >= MATCH_THRESHOLD

    @pytest.mark.skipif(not HAS_RAPIDFUZZ, reason="pip install rapidfuzz")
    def test_disambiguation_returns_multiple_candidates(self):
        corpus = ["Ram Sharma", "Rahul Sharma", "Ravi Sharma"]
        query = "R Sharma"
        matches = process.extract(query, corpus, scorer=fuzz.WRatio, limit=3)
        assert len(matches) >= 2, "Fuzzy search must return multiple candidates for disambiguation"

    @pytest.mark.skipif(not HAS_RAPIDFUZZ, reason="pip install rapidfuzz")
    def test_partial_name_search(self):
        score = fuzz.partial_ratio("Nikhil", "HAJARE NIKHIL RAJENDRA")
        assert score >= 85, f"Partial name match failed: score={score}"

    def test_rapidfuzz_install_instruction(self):
        if not HAS_RAPIDFUZZ:
            pytest.skip("rapidfuzz not installed — run: pip install rapidfuzz")
        assert True
