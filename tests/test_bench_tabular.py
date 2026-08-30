"""The TABULAR half of the bench (tests/eval/bench/build_tabular.py).

TABULAR is the largest slice of real demand — 22 of the 46 questions in the production
golden set — and until this existed the only thing exercising it was those 22 questions
against the production database. That made the biggest surface of the product the least
covered by the instrument, and made every TABULAR change a change measured on real data.

What matters about this generator is that its golds are computed from the same rows the
system queries, and that the rows are a pure function of the code rather than of a seed.
Both are asserted here.
"""
import json
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

duckdb = pytest.importorskip("duckdb")

from tests.eval.bench import build_tabular as bt  # noqa: E402

DB = PROJECT_ROOT / "data" / "tenants" / "tenant_bench_sql" / "analytics.duckdb"
GOLD = PROJECT_ROOT / "tests" / "eval" / "golden_bench_sql.json"


def test_rows_are_a_pure_function_of_the_code():
    """No RNG, no seed: two builds must be byte-identical, or a 'regression' could just be
    a different roster."""
    assert bt.build_rows() == bt.build_rows()


def test_roster_is_deterministic_and_unique():
    r = bt.roster()
    assert len(r) == bt.N_STUDENTS
    assert len({x[0] for x in r}) == bt.N_STUDENTS, "duplicate roll numbers"
    assert r == bt.roster()


def test_only_ff_counts_as_a_failure():
    """Matches models/grades.py — a different failing set would silently change every
    pass-percentage and backlog answer."""
    rows = bt.build_rows()
    for r in rows:
        grade, is_fail = r[4], r[11]
        assert is_fail == (grade == "FF")


def test_sgpa_is_consistent_within_a_student():
    rows = bt.build_rows()
    by_roll = {}
    for r in rows:
        by_roll.setdefault(r[0], set()).add(r[6])
    assert all(len(v) == 1 for v in by_roll.values())


def test_result_matches_the_failure_count():
    rows = bt.build_rows()
    fails, result = {}, {}
    for r in rows:
        fails[r[0]] = fails.get(r[0], 0) + int(r[11])
        result[r[0]] = r[7]
    for roll, n in fails.items():
        assert result[roll] == ("FAIL" if n else "PASS")


def test_the_data_actually_exercises_the_query_templates():
    """A uniform roster would make several templates untestable: 'failed at least N' needs
    multi-subject failures, threshold queries need students on both sides of the line, and
    per-subject aggregates need subjects that differ from each other."""
    rows = bt.build_rows()
    fails = {}
    for r in rows:
        if r[11]:
            fails[r[0]] = fails.get(r[0], 0) + 1
    assert any(n >= 2 for n in fails.values()), "no student fails 2+ subjects"
    sgpas = {r[0]: r[6] for r in rows}
    assert any(s < 6.0 for s in sgpas.values()) and any(s >= 6.0 for s in sgpas.values())
    per_subject = {}
    for r in rows:
        per_subject[r[2]] = per_subject.get(r[2], 0) + int(r[11])
    assert len(set(per_subject.values())) > 1, "every subject fails equally — no ranking"


@pytest.mark.skipif(not DB.exists(), reason="tabular bench not built")
def test_every_gold_matches_a_live_query_of_the_database():
    """The golds were computed by SQL at build time; recompute them now and compare. This
    is what makes a TABULAR gold unable to drift from the rows the system will read."""
    golds = {q["id"]: q for q in json.loads(GOLD.read_text(encoding="utf-8"))["questions"]}
    con = duckdb.connect(str(DB), read_only=True)
    total = con.execute("SELECT COUNT(DISTINCT roll_no) FROM exam_results").fetchone()[0]
    passed = con.execute("SELECT COUNT(*) FROM (SELECT DISTINCT roll_no, result FROM "
                         "exam_results) WHERE result='PASS'").fetchone()[0]
    fail2 = con.execute("SELECT COUNT(*) FROM (SELECT roll_no FROM exam_results WHERE "
                        "is_fail GROUP BY roll_no HAVING COUNT(DISTINCT subject_code) >= 2)"
                        ).fetchone()[0]
    con.close()

    def anchor(qid):
        return golds[qid]["gold"]["required"][0][0]

    assert anchor("BT001") == str(total)
    assert anchor("BT002") == str(passed)
    assert anchor("BT003") == str(total - passed)
    assert anchor("BT005") == str(fail2)


@pytest.mark.skipif(not DB.exists(), reason="tabular bench not built")
def test_it_writes_to_its_own_tenant_not_the_production_one():
    """tenant_bench must NOT gain a tabular.duckdb: router.route_query takes the
    TABULAR->FACT fallback exactly when that file is absent, so creating one there would
    silently change every document-side number already measured on that tenant."""
    assert not (PROJECT_ROOT / "data" / "tenants" / "tenant_bench" / "tabular.duckdb").exists()
    assert (PROJECT_ROOT / "data" / "tenants" / "tenant_bench_sql" / "tabular.duckdb").exists()
