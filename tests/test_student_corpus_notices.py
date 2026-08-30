"""Invariants for corpus/render_notices.py's five document categories (general notice
board, student handbook, events/holidays, attendance monitoring, deadline tracker).

Hermetic, no rendering. Builds (never saves) every document in corpus.render_notices.DOCS to
check what actually gets printed, and cross-checks the world-model structures those builders
read against corpus/student_world.py — the same discipline as tests/test_student_world.py.
"""
import datetime
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from corpus import student_world as w  # noqa: E402
from corpus.render_notices import DOCS as NOTICES_DOCS  # noqa: E402


# ── every SPOC referenced by the notices/handbook/events/attendance world-model
# structures exists in PEOPLE ────────────────────────────────────────────────

def _notices_referenced_person_keys():
    keys = {
        w.FEES["spoc_key"], w.ID_CARD["spoc_key"], w.BONAFIDE_CERTIFICATE["spoc_key"],
        w.SEMESTER_REGISTRATION["spoc_key"], w.HOSTEL["warden_key"],
        w.BUS_TRANSPORT["incharge_key"], w.DRESS_CODE["spoc_key"], w.CONVOCATION["spoc_key"],
        w.CONDONATION_PROCEDURE["chair_key"],
    }
    keys |= {e["coordinator_key"] for e in w.EVENTS}
    return keys


def test_every_spoc_referenced_by_notices_exists_in_people():
    missing = _notices_referenced_person_keys() - set(w.PEOPLE)
    assert not missing, f"referenced but not in PEOPLE: {missing}"


def test_every_deadline_spoc_in_notices_range_exists_in_people():
    """The ten DEADLINES entries this task added (see student_world.py's "notices/events/
    attendance" comment block) must all resolve to a real PEOPLE key."""
    notices_notice_keys = {
        "hostel_allotment", "bus_pass_renewal", "fee_payment_odd", "event_tech_fest",
        "event_sports_meet", "event_alumni_meet", "event_nss_camp",
        "semester_registration_even", "event_cultural_fest", "convocation_registration",
    }
    seen = set()
    for item, _date, spoc_key, notice_no in w.DEADLINES:
        if notice_no in {w.NOTICE_LOG[k] for k in notices_notice_keys}:
            assert spoc_key in w.PEOPLE, f"deadline {item!r} names unknown SPOC {spoc_key!r}"
            seen.add(notice_no)
    assert seen == {w.NOTICE_LOG[k] for k in notices_notice_keys}, (
        "not every notices-category deadline was found in DEADLINES"
    )


# ── every deadline the tracker prints exists in DEADLINES, and every EVENTS
# registration deadline is mirrored into DEADLINES ────────────────────────────

def test_deadline_tracker_covers_exactly_deadlines():
    from corpus.render_notices import build_deadline_tracker

    doc = build_deadline_tracker()
    # The table is the second flowable appended (after the intro paragraph); count its body
    # rows (data rows only, excluding the header) against len(DEADLINES).
    from reportlab.platypus import Table
    tables = [f for f in doc.story if isinstance(f, Table)]
    assert len(tables) == 1
    body_rows = len(tables[0]._cellvalues) - 1  # minus header row
    assert body_rows == len(w.DEADLINES)


def test_every_event_registration_deadline_is_mirrored_into_deadlines():
    deadline_by_notice = {notice_no: (item, iso_date) for item, iso_date, _spoc, notice_no in w.DEADLINES}
    for event in w.EVENTS:
        if event["registration_deadline"] is None:
            continue
        notice_no = w.NOTICE_LOG[event["notice_key"]]
        assert notice_no in deadline_by_notice, (
            f"{event['name']} has a registration_deadline but no matching DEADLINES entry"
        )
        _item, iso_date = deadline_by_notice[notice_no]
        assert iso_date == event["registration_deadline"], (
            f"{event['name']}: EVENTS deadline {event['registration_deadline']!r} does not "
            f"match DEADLINES date {iso_date!r} for the same notice"
        )


def test_every_event_without_a_registration_deadline_has_no_deadlines_entry():
    notice_nos_in_deadlines = {notice_no for *_, notice_no in w.DEADLINES}
    for event in w.EVENTS:
        if event["registration_deadline"] is not None:
            continue
        notice_no = w.NOTICE_LOG[event["notice_key"]]
        assert notice_no not in notice_nos_in_deadlines, (
            f"{event['name']} has no registration_deadline but a DEADLINES entry exists "
            "for it anyway"
        )


def test_deadline_tracker_circular_date_precedes_every_deadline():
    """The tracker's "days remaining" column is only meaningful as an "upcoming deadlines"
    circular if every deadline is genuinely in the future relative to the circular's own
    issue date."""
    from corpus.render_notices import _TRACKER_ISSUE_DATE

    issue = datetime.date.fromisoformat(_TRACKER_ISSUE_DATE)
    for item, iso_date, _spoc, _notice in w.DEADLINES:
        due = datetime.date.fromisoformat(iso_date)
        assert due > issue, f"deadline {item!r} ({iso_date}) is not after the tracker's issue date {issue}"


# ── every holiday has a valid type ────────────────────────────────────────────

def test_every_holiday_rendered_by_the_holiday_list_has_a_valid_type():
    valid = {"national", "state", "institutional", "restricted"}
    for _date, occasion, htype in w.HOLIDAYS:
        assert htype in valid, f"{occasion!r} has type {htype!r}"


def test_holiday_list_table_has_one_row_per_holiday():
    from corpus.render_notices import build_holiday_list
    from reportlab.platypus import Table

    doc = build_holiday_list()
    tables = [f for f in doc.story if isinstance(f, Table)]
    assert len(tables) == 1
    body_rows = len(tables[0]._cellvalues) - 1
    assert body_rows == len(w.HOLIDAYS)


# ── attendance tiers are ordered and non-overlapping ──────────────────────────

def test_attendance_tiers_are_ordered_and_non_overlapping():
    tiers = w.ATTENDANCE_TIERS
    assert len(tiers) >= 2
    for (_l1, lo1, hi1, _c1), (_l2, lo2, hi2, _c2) in zip(tiers, tiers[1:]):
        assert hi1 == lo2, f"gap or overlap between tiers: {hi1} != {lo2}"
        assert lo1 < hi1
        assert lo2 < hi2
    assert tiers[0][1] == 0, "the lowest tier must start at 0%"
    assert tiers[-1][2] > 100, "the highest tier must extend past 100%"


def test_attendance_tiers_match_attendance_policy_thresholds():
    p = w.ATTENDANCE_POLICY
    tiers = {label: (lo, hi) for label, lo, hi, _cons in w.ATTENDANCE_TIERS}
    assert tiers["Below debarment threshold"] == (0, p["debarment_threshold_pct"])
    assert tiers["Condonable band"] == (p["condonation_band_low_pct"], p["condonation_band_high_pct"])
    assert tiers["Regular"] == (p["min_attendance_pct"], 101)


def test_condonation_procedure_band_matches_attendance_policy():
    p = w.ATTENDANCE_POLICY
    assert w.CONDONATION_PROCEDURE["eligible_band"] == (
        p["condonation_band_low_pct"], p["condonation_band_high_pct"],
    )
    assert w.CONDONATION_PROCEDURE["max_grant_pct"] == p["condonation_max_grant_pct"]


def test_attendance_specimen_subject_codes_exist_and_show_a_subject_below_the_minimum():
    """The specimen is deliberately shaped (see student_world.py's comment) to hide one
    at-risk subject (below the 75% minimum) behind a healthy overall percentage."""
    s = w.ATTENDANCE_SPECIMEN
    total_held = total_attended = 0
    below_minimum = []
    for code, held, attended in s["rows"]:
        w.subject_by_code(code)  # raises if the code does not exist
        assert 0 <= attended <= held
        pct = attended / held * 100
        if pct < w.ATTENDANCE_POLICY["min_attendance_pct"]:
            below_minimum.append(code)
        total_held += held
        total_attended += attended
    overall_pct = total_attended / total_held * 100
    assert below_minimum, "specimen should show at least one subject below the minimum"
    assert overall_pct >= w.ATTENDANCE_POLICY["min_attendance_pct"], (
        "specimen's overall percentage should stay at/above the minimum, to illustrate that "
        "the overall figure can hide a failing subject"
    )


# ── notice numbers are unique across the whole corpus ─────────────────────────

def test_notice_numbers_are_unique_across_the_whole_corpus():
    """Builds (never saves) every document across every renderer module registered so far —
    corpus.render_academic, corpus.render_notices, and corpus.render_services if it has
    already landed — and checks the notice_no actually printed on each is unique. The
    render_services import is best-effort: that module is being built by a different agent
    in parallel and may not exist yet when this test runs; when it lands (now or on a later
    pull), it is picked up automatically without editing this test."""
    from corpus.render_academic import DOCS as academic_docs

    all_docs = dict(academic_docs)
    all_docs.update(NOTICES_DOCS)
    try:
        from corpus.render_services import DOCS as services_docs
    except ModuleNotFoundError:
        pass
    else:
        all_docs.update(services_docs)

    seen = {}
    for name, builder in all_docs.items():
        doc = builder()
        assert doc.notice_no not in seen, (
            f"{name} and {seen[doc.notice_no]} both carry notice number {doc.notice_no}"
        )
        seen[doc.notice_no] = name


def test_all_22_notices_docs_build_without_error_and_have_unique_notice_numbers():
    assert len(NOTICES_DOCS) == 22
    seen_notice_nos = set()
    for name, builder in NOTICES_DOCS.items():
        doc = builder()
        assert doc.notice_no not in seen_notice_nos, f"{name} reuses a notice number"
        seen_notice_nos.add(doc.notice_no)


def test_every_notices_notice_number_is_registered_in_notice_log():
    log_values = set(w.NOTICE_LOG.values())
    for name, builder in NOTICES_DOCS.items():
        doc = builder()
        assert doc.notice_no in log_values, f"{name} prints an unregistered notice number {doc.notice_no!r}"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
