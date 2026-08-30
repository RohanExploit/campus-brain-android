"""
Unit tests for the analytical template matcher (retrieval/sql_templates).

The hermetic tests assert match_template() maps varied phrasings to the right
template WITHOUT a DB or LLM — they prove the new templates (student_count,
result_count, bottom_by_sgpa, count_sgpa_at_least, supplementary_count)
generalize across wording and do NOT shadow the pre-existing templates.

The real-data tests (skipped when tenant_1 analytics is absent, e.g. CI) check
the templates return the correct known values.
"""
import sys
from pathlib import Path

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import config
from retrieval import sql_templates as T


def _fn(q):
    m = T.match_template(q)
    return m[0] if m else None


# ── Hermetic matcher routing (no DB) ──────────────────────────────────────────

def test_total_student_count_matches():
    for q in ["how many students are there?",
              "what is the total number of students",
              "total students in the database"]:
        assert _fn(q) is T.student_count, q


def test_passed_count_matches():
    for q in ["how many students passed", "number of students who passed"]:
        assert _fn(q) is T.result_count, q


def test_lowest_sgpa_matches():
    for q in ["which student has the lowest sgpa",
              "bottom 5 students by sgpa",
              "who has the worst sgpa"]:
        assert _fn(q) is T.bottom_by_sgpa, q


def test_sgpa_at_least_matches():
    for q in ["how many students have an sgpa of 9 or above",
              "how many students scored sgpa greater than 8",
              "number of students with sgpa at least 7"]:
        assert _fn(q) is T.count_sgpa_at_least, q


def test_supplementary_matches():
    assert _fn("how many students appeared for a supplementary examination") is T.supplementary_count


def test_bottom_by_sgpa_parses_limit():
    """Regression: the limit must be parsed from 'bottom/lowest/worst N', not _TOP_N."""
    fn, kw = T.match_template("bottom 5 students by sgpa")
    assert fn is T.bottom_by_sgpa and kw == {"limit": 5}
    fn, kw = T.match_template("which student has the lowest sgpa")
    assert fn is T.bottom_by_sgpa and kw == {}


def test_sgpa_threshold_anchored_not_first_number():
    """Regression: a semester/year number must not be taken as the SGPA threshold."""
    fn, kw = T.match_template("how many students in semester 3 have an sgpa above 7")
    assert fn is T.count_sgpa_at_least and kw == {"threshold": 7.0}


def test_pass_count_ignores_subject_scoped():
    """Subject-scoped pass questions must NOT hit the global pass count."""
    assert T.match_template("how many students passed subject BTCOC501") is None


def test_failed_overall_count_routes_to_result_count():
    fn, kw = T.match_template("how many students failed their semester result overall")
    assert fn is T.result_count and kw == {"status": "FAIL"}
    # but "failed at least N subjects" stays with the subject-level template
    assert _fn("how many students failed at least 2 subjects") is T.students_failed_at_least


def test_existing_templates_not_shadowed():
    """Regression: the new branches must not swallow the older templates."""
    assert _fn("list students who failed at least 4 subjects") is T.students_failed_at_least
    assert _fn("which student failed the most subjects") is T.students_failed_most
    assert _fn("what is the pass percentage") is T.pass_percentage
    assert _fn("top 10 students by sgpa") is T.toppers_by_sgpa


def test_non_template_shapes_fall_through():
    """These must return None so the intent cascade / dynamic SQL handles them."""
    assert T.match_template("how many students scored below sgpa 6") is None   # -> below_sgpa intent
    assert T.match_template("what is the sgpa of student 23063181242025") is None  # -> record lookup


# ── Real-data value checks (skip if tenant_1 analytics absent) ─────────────────

_ANALYTICS = config.tenant_dir("tenant_1") / "analytics.duckdb"
_real = pytest.mark.skipif(not _ANALYTICS.exists(), reason="tenant_1 analytics.duckdb not present")


@_real
def test_student_count_value():
    assert "369" in T.student_count(tenant_id="tenant_1")["answer"]


@_real
def test_passed_count_value():
    assert "334" in T.result_count(status="PASS", tenant_id="tenant_1")["answer"]


@_real
def test_failed_count_value():
    assert "35" in T.result_count(status="FAIL", tenant_id="tenant_1")["answer"]


@_real
def test_bottom_by_sgpa_value():
    assert "5.18" in T.bottom_by_sgpa(tenant_id="tenant_1")["answer"]


@_real
def test_count_sgpa_at_least_9_is_zero():
    ans = T.count_sgpa_at_least(9.0, tenant_id="tenant_1")["answer"].lower()
    assert "no students" in ans


@_real
def test_supplementary_is_zero():
    assert "no students" in T.supplementary_count(tenant_id="tenant_1")["answer"].lower()
