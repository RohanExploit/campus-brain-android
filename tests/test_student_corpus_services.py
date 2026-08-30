"""Structural invariants for the services half of the student corpus (scholarships,
placements, training, incubation, library, grievance/contacts — corpus/render_services.py
and the world-model additions it depends on).

Mirrors tests/test_student_world.py's discipline: every cross-reference actually resolves,
every field a student needs to self-assess eligibility is actually present (not silently
missing), and the corpus's one decision-support promise — "a student can determine their own
eligibility from these documents alone" — is checked structurally rather than by hand.

Hermetic and fast: no PDF is rendered to disk. The one exception is
test_every_service_document_builds_with_a_unique_registered_notice_number, which builds (but
never saves) every document in corpus.render_services.DOCS, exactly as
test_student_world.test_notice_numbers_are_unique_across_rendered_documents does for
corpus.render_academic.DOCS.
"""
import re
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from corpus import student_world as w  # noqa: E402


# ── scholarships / SCHEMES ───────────────────────────────────────────────────

def test_every_scheme_spoc_exists_in_people():
    for s in w.SCHEMES:
        assert s["spoc"] in w.PEOPLE, f"{s['name']} names unknown SPOC {s['spoc']!r}"


def test_every_scheme_has_the_eligibility_fields_set():
    """A student must be able to determine eligibility from the SCHEMES table alone: the
    income-ceiling and CGPA fields may legitimately be None (meaning "no ceiling"/"no
    minimum" -- an explicit, decidable answer), but the key must always be present, and
    the attendance minimum -- always a hard requirement -- may never be silently None."""
    for s in w.SCHEMES:
        assert "income_ceiling" in s, f"{s['name']} is missing income_ceiling"
        assert "min_cgpa" in s, f"{s['name']} is missing min_cgpa"
        assert "min_attendance_pct" in s, f"{s['name']} is missing min_attendance_pct"
        assert s["min_attendance_pct"] is not None, (
            f"{s['name']} has a None attendance minimum -- eligibility is undecidable"
        )
        assert isinstance(s["min_attendance_pct"], (int, float))


def test_every_scheme_has_application_steps_and_after_submission():
    for s in w.SCHEMES:
        assert s.get("application_steps"), f"{s['name']} has no application_steps"
        assert all(isinstance(step, str) and step for step in s["application_steps"])
        assert s.get("after_submission"), f"{s['name']} has no after_submission text"
        assert s.get("documents_required"), f"{s['name']} has no documents_required"
        assert s.get("portal"), f"{s['name']} has no portal"


def test_every_scheme_notice_key_resolves_in_notice_log():
    for s in w.SCHEMES:
        assert s["notice_key"] in w.NOTICE_LOG, f"{s['name']} cites unknown notice_key"


def test_sports_scheme_attendance_relaxation_differs_from_general_policy():
    """Reasserted here (also covered in test_student_world.py) because it is the single
    fact a multi-hop "what attendance do I need" question depends on getting right."""
    sports = next(s for s in w.SCHEMES if "Sports" in s["name"])
    assert sports["min_attendance_pct"] != w.ATTENDANCE_POLICY["min_attendance_pct"]


# ── sports and cultural benefits ─────────────────────────────────────────────

def test_sports_cultural_benefits_attendance_relaxation_differs_from_general_policy():
    b = w.SPORTS_CULTURAL_BENEFITS
    assert b["attendance_relaxation_pct"] != w.ATTENDANCE_POLICY["min_attendance_pct"]
    assert b["attendance_relaxation_pct"] < w.ATTENDANCE_POLICY["min_attendance_pct"], (
        "the relaxation should actually relax the general minimum, not raise it"
    )


def test_sports_cultural_benefits_spoc_exists_in_people():
    assert w.SPORTS_CULTURAL_BENEFITS["spoc_key"] in w.PEOPLE


def test_sports_cultural_benefits_notice_key_resolves():
    assert w.SPORTS_CULTURAL_BENEFITS["notice_key"] in w.NOTICE_LOG


def test_prize_money_and_kit_allowance_are_positive():
    for level, position, amount in w.SPORTS_CULTURAL_BENEFITS["prize_money"]:
        assert amount > 0, f"{level} {position} prize money is not positive"
    assert w.SPORTS_CULTURAL_BENEFITS["kit_allowance_per_year"] > 0


# ── placements ────────────────────────────────────────────────────────────────

def test_every_placement_eligible_branch_exists_in_departments():
    dept_codes = {d[1] for d in w.DEPARTMENTS}
    for row in w.PLACEMENTS:
        company, _role, _pkg, _cgpa, _backlog, branches, *_rest = row
        bad = set(branches) - dept_codes
        assert not bad, f"{company} names unknown department codes: {bad}"


def test_every_placement_spoc_exists_in_people():
    for row in w.PLACEMENTS:
        company = row[0]
        spoc_key = row[8]
        assert spoc_key in w.PEOPLE, f"{company} names unknown SPOC {spoc_key!r}"


def test_every_placement_has_selection_rounds():
    for row in w.PLACEMENTS:
        company = row[0]
        rounds = row[9]
        assert rounds, f"{company} has no selection rounds"
        assert all(isinstance(r, str) and r for r in rounds)


def test_every_placement_cgpa_and_backlog_cutoffs_are_sane():
    for row in w.PLACEMENTS:
        company, _role, package, min_cgpa, max_backlogs, *_rest = row
        assert package > 0, f"{company} has a non-positive package"
        assert 0 <= min_cgpa <= 10, f"{company} has an out-of-range CGPA cutoff"
        assert max_backlogs >= 0, f"{company} has a negative backlog limit"


def test_placement_policy_spoc_exists_in_people():
    assert w.PLACEMENT_POLICY["spoc_key"] in w.PEOPLE


def test_placement_policy_has_every_required_rule():
    for field in ("registration_rule", "one_offer_rule", "backlog_policy", "dress_code",
                  "absenteeism_penalty"):
        assert w.PLACEMENT_POLICY.get(field), f"PLACEMENT_POLICY missing {field}"


def test_every_placement_notice_key_resolves():
    from corpus.render_services import COMPANY_NOTICE_KEY
    for row in w.PLACEMENTS:
        company = row[0]
        assert company in COMPANY_NOTICE_KEY, f"{company} has no registered notice key"
        assert COMPANY_NOTICE_KEY[company] in w.NOTICE_LOG


# ── training ──────────────────────────────────────────────────────────────────

def test_every_training_coordinator_exists_in_people():
    for prog, *_rest, coord, _sched, _enrol in w.TRAINING:
        assert coord in w.PEOPLE, f"{prog} names unknown coordinator {coord!r}"


def test_every_training_programme_has_schedule_and_enrolment_procedure():
    for prog, _prov, _dur, fee, _elig, _cert, _coord, sched, enrol in w.TRAINING:
        assert fee > 0, f"{prog} has a non-positive fee"
        assert sched, f"{prog} has no schedule"
        assert enrol, f"{prog} has no enrolment procedure"


# ── incubation ────────────────────────────────────────────────────────────────

def test_incubation_manager_exists_in_people():
    assert w.INCUBATION["manager_key"] in w.PEOPLE


def test_incubation_has_ip_policy_and_seed_grant_terms():
    assert w.INCUBATION.get("ip_policy")
    assert w.INCUBATION.get("seed_grant_terms")


def test_incubation_funding_tiers_are_increasing():
    amounts = [amt for _name, amt, _stage in w.INCUBATION["funding_tiers"]]
    assert amounts == sorted(amounts), "funding tiers should be listed low to high"
    assert len(amounts) == len(set(amounts))


# ── library ───────────────────────────────────────────────────────────────────

def test_library_librarian_exists_in_people():
    assert w.LIBRARY["librarian_key"] in w.PEOPLE


def test_library_has_remote_access_renewal_and_lost_book_policy():
    for field in ("remote_access", "renewal_rule", "lost_book_policy"):
        assert w.LIBRARY.get(field), f"LIBRARY missing {field}"


def test_library_loan_limits_cover_every_category_with_positive_values():
    for category, (books, days) in w.LIBRARY["loan_limits"].items():
        assert books > 0 and days > 0, f"{category} has a non-positive loan limit"


# ── grievance / anti-ragging / SPOC directory ────────────────────────────────

def test_grievance_escalation_levels_have_increasing_cumulative_timelines():
    """Each level's own duration need not exceed the previous level's (a later stage can
    have a tighter SLA than an earlier one), but the cumulative time-from-complaint must
    strictly increase at every numerically-timed level, and the final level must be the
    explicit external escalation with no institute-set timeline."""
    days_re = re.compile(r"(\d+) working days")
    cumulative = 0
    numeric_levels = w.GRIEVANCE["escalation"][:-1]
    for _level, desc, timeline in numeric_levels:
        m = days_re.search(timeline)
        assert m, f"{desc!r} timeline {timeline!r} has no parseable day count"
        new_cumulative = cumulative + int(m.group(1))
        assert new_cumulative > cumulative, f"{desc!r} does not add positive time"
        cumulative = new_cumulative

    last_level, last_desc, last_timeline = w.GRIEVANCE["escalation"][-1]
    assert "external" in last_desc.lower() or "University" in last_desc
    assert "University" in last_timeline or "external" in last_timeline.lower()


def test_grievance_committee_and_anti_ragging_spoc_exist_in_people():
    assert w.GRIEVANCE["chair_key"] in w.PEOPLE
    for key in w.GRIEVANCE["member_keys"]:
        assert key in w.PEOPLE, f"grievance committee member {key!r} not in PEOPLE"
    assert w.GRIEVANCE["anti_ragging"]["spoc_key"] in w.PEOPLE


def test_spoc_directory_covers_every_role_in_people():
    """The consolidated SPOC directory is the answer to "who do I contact for X"; every
    PEOPLE entry that carries a role (as opposed to the subject-teaching-only faculty whose
    role is None) must have a directory entry, or the directory is silently incomplete."""
    directory_keys = {key for _task, key in w.SPOC_DIRECTORY}
    roled_keys = {key for key, p in w.PEOPLE.items() if p.get("role") is not None}
    missing = roled_keys - directory_keys
    assert not missing, f"PEOPLE roles missing from SPOC_DIRECTORY: {missing}"
    extra = directory_keys - roled_keys
    assert not extra, f"SPOC_DIRECTORY references non-SPOC or unknown keys: {extra}"


def test_spoc_directory_has_no_duplicate_tasks():
    tasks = [task for task, _key in w.SPOC_DIRECTORY]
    assert len(tasks) == len(set(tasks)), "duplicate task lines in SPOC_DIRECTORY"


def test_spoc_directory_notice_key_resolves():
    assert "spoc_directory" in w.NOTICE_LOG


# ── notice numbers / rendered documents ──────────────────────────────────────

def test_every_service_document_builds_with_a_unique_registered_notice_number():
    """Builds (never saves) every document in render_services.DOCS and checks the
    notice_no actually printed on each is unique and matches NOTICE_LOG -- the same check
    test_student_world.py runs for render_academic.DOCS."""
    from corpus.render_services import DOCS

    seen = {}
    for name, builder in DOCS.items():
        doc = builder()
        assert doc.notice_no not in seen, (
            f"{name} and {seen[doc.notice_no]} both carry notice number {doc.notice_no}"
        )
        seen[doc.notice_no] = name
    assert set(seen) <= set(w.NOTICE_LOG.values())
    assert len(seen) == len(DOCS)


def test_service_and_academic_notice_numbers_never_collide():
    """The two renderer modules (render_academic.py, this task's render_services.py) must
    never both claim the same notice number, or a citation would be ambiguous."""
    from corpus.render_academic import DOCS as academic_docs
    from corpus.render_services import DOCS as services_docs

    academic_notices = {academic_docs[name]().notice_no for name in academic_docs}
    service_notices = {services_docs[name]().notice_no for name in services_docs}
    assert not (academic_notices & service_notices)


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
