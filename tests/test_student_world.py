"""Structural invariants of the student-corpus world model (corpus/student_world.py).

Mirrors tests/test_bench_integrity.py's discipline for tests/eval/bench/world.py: the
properties that make this corpus trustworthy — every cross-reference actually resolves,
every deliberate distractor actually exists — are asserted here and run with the suite.

Hermetic and fast: no PDF is rendered to disk. The one exception is
test_notice_numbers_are_unique, which builds (but never saves) every document in
corpus.render_academic.DOCS to check the notice numbers actually printed on them — building
a NoticeDoc only appends reportlab flowables to an in-memory list; nothing touches disk
until .save() is called, which this test never does.
"""
import datetime
import re
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from corpus import student_world as w  # noqa: E402

PHONE_RE = re.compile(r"^\+91 90000 \d{5}$")


# ── people / cross-references ────────────────────────────────────────────────

def _all_referenced_person_keys():
    keys = set()
    keys |= {d[3] for d in w.DEPARTMENTS}
    keys |= {row[7] for row in w.TIMETABLE}
    keys |= {s["spoc"] for s in w.SCHEMES}
    keys |= {p[8] for p in w.PLACEMENTS}
    keys |= {t[6] for t in w.TRAINING}
    keys |= {d[2] for d in w.DEADLINES}
    keys.add(w.INSTITUTE["principal_key"])
    keys.add(w.INSTITUTE["registrar_key"])
    keys.add(w.INCUBATION["manager_key"])
    keys.add(w.LIBRARY["librarian_key"])
    keys.add(w.GRIEVANCE["chair_key"])
    keys |= set(w.GRIEVANCE["member_keys"])
    keys.add(w.GRIEVANCE["anti_ragging"]["spoc_key"])
    keys.add(w.ATTENDANCE_POLICY["committee_chair_key"])
    return keys


def test_every_referenced_person_key_exists_in_people():
    missing = _all_referenced_person_keys() - set(w.PEOPLE)
    assert not missing, f"referenced but not in PEOPLE: {missing}"


def test_every_scheme_spoc_exists_in_people():
    for s in w.SCHEMES:
        assert s["spoc"] in w.PEOPLE, f"{s['name']} names unknown SPOC {s['spoc']!r}"


def test_every_deadline_spoc_exists_in_people():
    for item, _date, spoc_key, _notice in w.DEADLINES:
        assert spoc_key in w.PEOPLE, f"deadline {item!r} names unknown SPOC {spoc_key!r}"


def test_no_person_key_is_orphaned_by_a_typo():
    """Every PEOPLE key with role=None is faculty-only (used only in TIMETABLE); every key
    with a role is a named SPOC. Catches a key that exists in PEOPLE but is never actually
    referenced anywhere, which would mean a dangling/typo'd cross-reference elsewhere."""
    referenced = _all_referenced_person_keys()
    unreferenced = set(w.PEOPLE) - referenced
    assert not unreferenced, f"PEOPLE entries never referenced anywhere: {unreferenced}"


# ── departments / subjects ───────────────────────────────────────────────────

def test_department_codes_and_names_are_unique():
    assert len({d[1] for d in w.DEPARTMENTS}) == len(w.DEPARTMENTS)
    assert len({d[0] for d in w.DEPARTMENTS}) == len(w.DEPARTMENTS)


def test_every_department_hod_exists_in_people():
    for name, code, _est, hod_key, _intake, _faculty in w.DEPARTMENTS:
        assert hod_key in w.PEOPLE, f"{name} names unknown HOD {hod_key!r}"


def test_no_duplicate_subject_codes():
    codes = [s[0] for s in w.SUBJECTS]
    assert len(codes) == len(set(codes)), "duplicate subject codes in SUBJECTS"


def test_every_subject_department_code_is_real():
    dept_codes = {d[1] for d in w.DEPARTMENTS}
    for code, title, dept_code, _sem, _cr, _type in w.SUBJECTS:
        assert dept_code in dept_codes, f"{code} ({title}) names unknown department {dept_code!r}"


def test_subject_types_are_from_the_declared_vocabulary():
    for code, _title, _dept, _sem, _cr, subj_type in w.SUBJECTS:
        assert subj_type in ("theory", "lab", "elective"), f"{code} has type {subj_type!r}"


def test_subjects_cover_semesters_3_to_6_for_two_departments():
    by_dept = {}
    for code, _title, dept, sem, _cr, _type in w.SUBJECTS:
        by_dept.setdefault(dept, set()).add(sem)
    full_coverage = [d for d, sems in by_dept.items() if {3, 4, 5, 6} <= sems]
    assert len(full_coverage) >= 2, f"only {full_coverage} cover semesters 3-6"


# ── timetable ─────────────────────────────────────────────────────────────────

def test_every_timetable_subject_code_exists_in_subjects():
    subj_codes = {s[0] for s in w.SUBJECTS}
    bad = {row[6] for row in w.TIMETABLE} - subj_codes
    assert not bad, f"TIMETABLE references unknown subject codes: {bad}"


def test_every_timetable_faculty_key_exists_in_people():
    bad = {row[7] for row in w.TIMETABLE} - set(w.PEOPLE)
    assert not bad, f"TIMETABLE references unknown faculty keys: {bad}"


def test_every_timetable_row_references_its_own_department():
    """A COMP timetable row must schedule a CO-coded subject, not an EC one, etc. — catches
    a copy-paste error where a row's dept_code field and its subject_code field disagree."""
    for dept_code, _sem, _div, _day, _per, _time, subj_code, _fac, _room in w.TIMETABLE:
        subj = w.subject_by_code(subj_code)
        assert subj[2] == dept_code, (
            f"TIMETABLE row for {dept_code} schedules {subj_code}, which belongs to {subj[2]}"
        )


def test_every_theory_subject_meets_three_periods_a_week():
    from collections import Counter
    counts = Counter()
    for dept_code, sem, div, _day, _per, _time, subj_code, _fac, _room in w.TIMETABLE:
        subj = w.subject_by_code(subj_code)
        if subj[5] in ("theory", "elective"):
            counts[(dept_code, sem, div, subj_code)] += 1
    short = {k: v for k, v in counts.items() if v != 3}
    assert not short, f"theory/elective subjects not scheduled 3x/week: {short}"


# ── exam schedule ─────────────────────────────────────────────────────────────

def test_every_exam_subject_code_exists_in_subjects():
    subj_codes = {s[0] for s in w.SUBJECTS}
    bad = {row[0] for row in w.EXAM_SCHEDULE} - subj_codes
    assert not bad, f"EXAM_SCHEDULE references unknown subject codes: {bad}"


def test_every_exam_date_falls_inside_its_declared_window():
    windows = {
        "Internal Assessment II": w.ACADEMIC_CALENDAR["odd_term_ia2_window"],
        "Practical Examination": w.ACADEMIC_CALENDAR["odd_term_practical_window"],
        "End-Semester Theory": w.ACADEMIC_CALENDAR["odd_term_theory_exam_window"],
    }
    for code, exam_type, iso_date, _session, _dur, _venue, _seat in w.EXAM_SCHEDULE:
        lo, hi = (datetime.date.fromisoformat(x) for x in windows[exam_type])
        d = datetime.date.fromisoformat(iso_date)
        assert lo <= d <= hi, f"{code} {exam_type} on {iso_date} falls outside {windows[exam_type]}"


def test_every_theory_subject_scheduled_gets_ia_and_ese_and_labs_get_practical():
    theory_scheduled = {
        row[6] for row in w.TIMETABLE if w.subject_by_code(row[6])[5] in ("theory", "elective")
    }
    lab_scheduled = {row[6] for row in w.TIMETABLE if w.subject_by_code(row[6])[5] == "lab"}
    exams_by_code = {}
    for code, exam_type, *_ in w.EXAM_SCHEDULE:
        exams_by_code.setdefault(code, set()).add(exam_type)
    for code in theory_scheduled:
        assert exams_by_code.get(code) == {"Internal Assessment II", "End-Semester Theory"}, (
            f"{code} exam entries: {exams_by_code.get(code)}"
        )
    for code in lab_scheduled:
        assert exams_by_code.get(code) == {"Practical Examination"}, (
            f"{code} exam entries: {exams_by_code.get(code)}"
        )


# ── holidays ──────────────────────────────────────────────────────────────────

def test_holiday_types_are_from_the_declared_vocabulary():
    for _date, _occasion, htype in w.HOLIDAYS:
        assert htype in ("national", "state", "institutional", "restricted"), htype


def test_all_four_holiday_types_are_represented():
    assert {t for _d, _o, t in w.HOLIDAYS} == {"national", "state", "institutional", "restricted"}


def test_holiday_dates_are_valid_and_sorted():
    dates = [datetime.date.fromisoformat(d) for d, _o, _t in w.HOLIDAYS]
    assert dates == sorted(dates), "HOLIDAYS is not in chronological order"


def test_no_holiday_falls_inside_an_exam_window():
    windows = [
        w.ACADEMIC_CALENDAR["odd_term_ia2_window"],
        w.ACADEMIC_CALENDAR["odd_term_practical_window"],
        w.ACADEMIC_CALENDAR["odd_term_theory_exam_window"],
        w.ACADEMIC_CALENDAR["even_term_ia2_window"],
        w.ACADEMIC_CALENDAR["even_term_practical_window"],
        w.ACADEMIC_CALENDAR["even_term_theory_exam_window"],
    ]
    bounds = [(datetime.date.fromisoformat(a), datetime.date.fromisoformat(b)) for a, b in windows]
    for iso_date, occasion, _type in w.HOLIDAYS:
        d = datetime.date.fromisoformat(iso_date)
        for lo, hi in bounds:
            assert not (lo <= d <= hi), f"holiday {occasion!r} on {iso_date} falls inside exam window {lo}-{hi}"


# ── notices / deadlines ───────────────────────────────────────────────────────

def test_notice_log_has_no_duplicate_numbers():
    nums = list(w.NOTICE_LOG.values())
    assert len(nums) == len(set(nums)), "duplicate notice numbers in NOTICE_LOG"


def test_deadline_notices_are_all_in_the_notice_log():
    log_values = set(w.NOTICE_LOG.values())
    for item, _date, _spoc, notice_no in w.DEADLINES:
        assert notice_no in log_values, f"deadline {item!r} cites unregistered notice {notice_no!r}"


def test_notice_numbers_are_unique_across_rendered_documents():
    """Builds (never saves) every document in render_academic.DOCS and checks the
    notice_no actually printed on each is unique and matches NOTICE_LOG."""
    from corpus.render_academic import DOCS

    seen = {}
    for name, builder in DOCS.items():
        doc = builder()
        assert doc.notice_no not in seen, (
            f"{name} and {seen[doc.notice_no]} both carry notice number {doc.notice_no}"
        )
        seen[doc.notice_no] = name
    assert set(seen) <= set(w.NOTICE_LOG.values())


# ── phone numbers ─────────────────────────────────────────────────────────────

def test_every_phone_number_matches_the_reserved_fiction_pattern():
    assert PHONE_RE.match(w.INSTITUTE["main_phone"]), w.INSTITUTE["main_phone"]
    for key, p in w.PEOPLE.items():
        assert PHONE_RE.match(p["phone"]), f"{key}: {p['phone']!r}"


def test_no_duplicate_phone_numbers():
    phones = [p["phone"] for p in w.PEOPLE.values()] + [w.INSTITUTE["main_phone"]]
    assert len(phones) == len(set(phones)), "duplicate phone numbers assigned"


def test_every_email_is_on_the_institute_domain():
    domain = "@kriet.ac.in"
    for key, p in w.PEOPLE.items():
        assert p["email"].endswith(domain), f"{key}: {p['email']!r}"


# ── deliberate distractors ────────────────────────────────────────────────────

def test_two_hods_share_a_surname_but_head_different_departments():
    """Dr. Manasi Kadam (Computer Engineering) and Dr. Prashant Kadam (Electronics and
    Telecommunication) — a retriever that lands on "Dr. Kadam, HOD" must still pick the
    right department."""
    kadams = [p for p in w.PEOPLE.values() if p["name"].split()[-1] == "Kadam"]
    assert len(kadams) == 2
    depts = {p["department"] for p in kadams}
    assert depts == {"COMP", "EXTC"}


def test_two_similarly_named_schemes_have_different_income_ceilings():
    shahu = [s for s in w.SCHEMES if "Rajarshi Shahu Maharaj" in s["name"]]
    assert len(shahu) == 2
    ceilings = {s["income_ceiling"] for s in shahu}
    assert len(ceilings) == 2, "the two Shahu Maharaj schemes have the same income ceiling"


def test_sports_scheme_attendance_threshold_differs_from_general_policy():
    sports = next(s for s in w.SCHEMES if "Sports" in s["name"])
    assert sports["min_attendance_pct"] != w.ATTENDANCE_POLICY["min_attendance_pct"]


def test_two_subjects_have_adjacent_codes_in_different_semesters():
    co404 = w.subject_by_code("CO404")
    co405 = w.subject_by_code("CO405")
    assert co404[3] != co405[3], "CO404 and CO405 are meant to sit in different semesters"


def test_two_departments_offer_a_subject_with_the_same_title():
    titles = {}
    for code, title, dept, _sem, _cr, _type in w.SUBJECTS:
        titles.setdefault(title, set()).add(dept)
    same_title_diff_dept = [t for t, depts in titles.items() if len(depts) > 1]
    assert same_title_diff_dept, "no subject title is shared across departments"


# ── rupees formatting (reused from the bench pattern) ────────────────────────

def test_indian_digit_grouping():
    assert w.rupees(142000) == "1,42,000"
    assert w.rupees(29635000) == "2,96,35,000"
    assert w.rupees(750) == "750"


# ── anti-ragging helpline (checked against a live source, see student_world.py) ─

def test_ugc_anti_ragging_helpline_is_the_documented_number():
    ar = w.GRIEVANCE["anti_ragging"]
    assert "1800-180-5522" in ar["ugc_helpline"]
    assert ar["ugc_helpline_email"] == "helpline@antiragging.in"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
