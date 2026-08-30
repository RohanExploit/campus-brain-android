"""The three document categories this task renders: the academic calendar, one timetable
per (department, semester) actually in session, and the combined exam schedule.

Each builder returns an unsaved NoticeDoc; corpus/build_student_corpus.py calls .save() on
every entry in DOCS. Splitting build from save keeps this module importable (and its output
inspectable) without touching disk — see tests/test_student_world.py, which builds every
document to check notice numbers and cross-references but never calls .save().
"""
import sys
from datetime import date as _date
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from corpus.render_base import NoticeDoc, weighted_col_widths  # noqa: E402
from corpus.student_world import (  # noqa: E402
    ACADEMIC_CALENDAR, EXAM_SCHEDULE, EXAM_SESSION_TIMES, HOLIDAYS, INSTITUTE,
    NOTICE_LOG, PEOPLE, PERIOD_TIMES, TIMETABLE, dept_by_code, subject_by_code,
)

OUT = PROJECT_ROOT / "corpus" / "out"

DAY_ORDER = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
# One row per day, columns are the six period slots; a lab block occupies one cell but
# spans the label of every period it covers (e.g. "P4-P6").
PERIOD_COLUMNS = ["P1", "P2", "P3", "P4", "P5", "P6"]

# Short faculty codes for the timetable grid, resolved to full names in the "Faculty" table
# under each timetable. A full name ("Prof. Ashwini Devrukhkar") in a ~2.3cm grid column
# wraps mid-word under pdfplumber's extraction (e.g. "Devrukhka\nr") — real Indian college
# timetables use exactly this code-plus-legend pattern for the same reason, so it is both
# more realistic and more cleanly extractable than the full name in every cell.
FACULTY_CODE = {
    "hod_comp": "MKAD", "hod_extc": "PKAD", "antiragging_spoc": "SBHA",
    "grievance_chair": "NSAL", "fac_panse": "RPAN", "fac_oak": "VOAK",
    "fac_ketkar": "OKET", "fac_bapat": "SBAP", "fac_puranik": "YPUR",
    "fac_devrukhkar": "ADEV",
}


def _fmt_date(iso: str) -> str:
    y, m, d = (int(x) for x in iso.split("-"))
    return _date(y, m, d).strftime("%d %B %Y")


def _fmt_window(window) -> str:
    lo, hi = window
    return f"{_fmt_date(lo)} to {_fmt_date(hi)}"


# ── 1. academic calendar ──────────────────────────────────────────────────────

def build_academic_calendar():
    c = ACADEMIC_CALENDAR
    doc = NoticeDoc(
        filename=OUT / "01_academic_calendar_2026_27.pdf",
        notice_no=NOTICE_LOG["academic_calendar"],
        date=_fmt_date("2026-06-20"),
        subject=f"Academic Calendar for the Academic Year {c['academic_year']}",
        to=f"All Students and Faculty, {INSTITUTE['short']}",
    )
    doc.para(
        f"The Academic Calendar for {c['academic_year']} is notified below for the "
        f"information of all students and faculty. All dates are subject to revision by "
        f"circular; the version on the Examination Section notice board is authoritative."
    )
    doc.table(
        ["Term", "Event", "Dates"],
        [
            ["Odd Term", "Teaching begins", _fmt_date(c["odd_term_start"])],
            ["Odd Term", "Teaching weeks", f"{c['odd_term_teaching_weeks']} weeks"],
            ["Odd Term", "Internal Assessment II window", _fmt_window(c["odd_term_ia2_window"])],
            ["Odd Term", "Practical examination window", _fmt_window(c["odd_term_practical_window"])],
            ["Odd Term", "End-Semester theory examination window", _fmt_window(c["odd_term_theory_exam_window"])],
            ["Odd Term", "Result declaration", _fmt_date(c["odd_term_result_date"])],
            ["Odd Term", "Winter vacation", _fmt_window(c["winter_vacation"])],
            ["Even Term", "Teaching begins", _fmt_date(c["even_term_start"])],
            ["Even Term", "Teaching weeks", f"{c['even_term_teaching_weeks']} weeks"],
            ["Even Term", "Internal Assessment II window", _fmt_window(c["even_term_ia2_window"])],
            ["Even Term", "Practical examination window", _fmt_window(c["even_term_practical_window"])],
            ["Even Term", "End-Semester theory examination window", _fmt_window(c["even_term_theory_exam_window"])],
            ["Even Term", "Result declaration", _fmt_date(c["even_term_result_date"])],
            ["Even Term", "Summer vacation", _fmt_window(c["summer_vacation"])],
        ],
        col_widths=weighted_col_widths([2, 4, 3]),
    )
    doc.para("<b>Holidays</b>")
    doc.table(
        ["Date", "Occasion", "Type"],
        [[_fmt_date(d), occ, typ.capitalize()] for d, occ, typ in HOLIDAYS],
    )
    doc.para(
        "A holiday falling within an examination window does not shift the examination "
        "unless a separate circular is issued by the Examination Section."
    )
    doc.signature("principal")
    return doc


# ── 2. timetables ─────────────────────────────────────────────────────────────

def _periods_for_label(label):
    """"P1" -> ["P1"]; "P4-P6" -> ["P4", "P5", "P6"]. Period numbers are single digits, so
    a plain range over the digit is enough to expand a block label."""
    if "-" not in label:
        return [label]
    lo, hi = label.split("-")
    return [f"P{n}" for n in range(int(lo[1:]), int(hi[1:]) + 1)]


def _timetable_grid(dept_code, semester, division):
    rows = [
        r for r in TIMETABLE
        if r[0] == dept_code and r[1] == semester and r[2] == division
    ]
    grid = {day: {p: "--" for p in PERIOD_COLUMNS} for day in DAY_ORDER}
    for _dept, _sem, _div, day, period_label, _time, subj_code, fac_key, room in rows:
        cell = f"{subj_code}<br/>{FACULTY_CODE[fac_key]}<br/>{room}"
        for p in _periods_for_label(period_label):
            grid[day][p] = cell
    return grid


def build_timetable(dept_code, semester, division="A"):
    dept_name = dept_by_code(dept_code)[0]
    grid = _timetable_grid(dept_code, semester, division)

    header = ["Day"] + [f"{p}<br/>{PERIOD_TIMES[p]}" for p in PERIOD_COLUMNS]
    rows = []
    for day in DAY_ORDER:
        row = [day]
        p = 0
        columns = list(PERIOD_COLUMNS)
        while p < len(columns):
            cell = grid[day][columns[p]]
            # Count how many consecutive columns share this exact cell content (a lab
            # block written into every period it spans) so it renders once, not repeated.
            span = 1
            while p + span < len(columns) and grid[day][columns[p + span]] == cell:
                span += 1
            row.append(cell)
            for _ in range(span - 1):
                row.append("")
            p += span
        rows.append(row)

    # File-name sequence numbers 02-05, fixed explicitly (not derived from iteration order)
    # so a re-run always names the same document the same thing.
    file_seq = {("COMP", 3): 2, ("COMP", 5): 3, ("EXTC", 3): 4, ("EXTC", 5): 5}[(dept_code, semester)]
    doc = NoticeDoc(
        filename=OUT / f"0{file_seq}_timetable_{dept_code.lower()}_sem{semester}_div{division}.pdf",
        notice_no=NOTICE_LOG[f"timetable_{dept_code.lower()}_sem{semester}"],
        date=_fmt_date("2026-07-06"),
        subject=(
            f"Weekly Timetable, Semester {semester} (Division {division}), {dept_name} "
            f"— Odd Term {ACADEMIC_CALENDAR['academic_year']}"
        ),
        to=f"Students of Semester {semester}, Division {division}, {dept_name}",
    )
    doc.para(
        f"The weekly timetable for Semester {semester}, Division {division}, {dept_name} "
        f"for the odd term of Academic Year {ACADEMIC_CALENDAR['academic_year']} is notified "
        f"below. Each cell lists the subject code, subject title, faculty and room."
    )
    doc.table(header, rows, col_widths=weighted_col_widths([3] + [2] * 6))
    doc.para(
        "12:15-12:45 is the lunch recess every day. Saturday classes end after the fourth "
        "period. Periods marked \"--\" are free for library use, mentoring or self-study. "
        "Faculty are identified by the codes below; full names are in the Faculty table."
    )

    dept_rows = [
        r for r in TIMETABLE if r[0] == dept_code and r[1] == semester and r[2] == division
    ]
    theory_codes = sorted({
        r[6] for r in dept_rows if subject_by_code(r[6])[5] in ("theory", "elective")
    })
    lab_codes = sorted({r[6] for r in dept_rows if subject_by_code(r[6])[5] == "lab"})
    doc.para("<b>Subjects covered</b>")
    doc.table(
        ["Code", "Title", "Type", "Credits"],
        [
            [s[0], s[1], s[5].capitalize(), str(s[4])]
            for code in theory_codes + lab_codes
            for s in [subject_by_code(code)]
        ],
        col_widths=weighted_col_widths([2, 5, 2, 2]),
    )

    faculty_keys = sorted({r[7] for r in dept_rows}, key=lambda k: FACULTY_CODE[k])
    doc.para("<b>Faculty</b>")
    doc.table(
        ["Code", "Name", "Designation"],
        [[FACULTY_CODE[k], PEOPLE[k]["name"], PEOPLE[k]["designation"]] for k in faculty_keys],
        col_widths=weighted_col_widths([2, 5, 6]),
    )

    doc.signature(f"hod_{dept_code.lower()}")
    return doc


# ── 3. exam schedule (Internal Assessment II, Practical, End-Semester Theory) ─

EXAM_TABLE_HEADERS = ["Code", "Subject", "Dept", "Date", "Session", "Duration", "Venue", "Seating"]
EXAM_TABLE_WIDTHS = weighted_col_widths([2, 4, 2, 3, 3, 3, 3, 4])


def _exam_section_rows(exam_type):
    rows = []
    for code, etype, iso_date, session, duration, venue, seating in EXAM_SCHEDULE:
        if etype != exam_type:
            continue
        subj = subject_by_code(code)
        time_range = EXAM_SESSION_TIMES[etype][session]
        rows.append([
            code, subj[1], subj[2], _fmt_date(iso_date), f"{session}<br/>{time_range}",
            duration, venue, seating,
        ])
    rows.sort(key=lambda r: (r[3], r[4]))
    return rows


def build_exam_schedule():
    c = ACADEMIC_CALENDAR
    doc = NoticeDoc(
        filename=OUT / "06_exam_schedule_odd_2026_27.pdf",
        notice_no=NOTICE_LOG["exam_schedule_odd"],
        date=_fmt_date("2026-10-12"),
        subject=(
            "Examination Schedule: Internal Assessment II, Practical and End-Semester "
            f"Theory Examinations, Odd Term {c['academic_year']}"
        ),
        to=(
            "Students of Semester 3 and Semester 5, Computer Engineering and Electronics "
            "and Telecommunication Engineering (Division A)"
        ),
    )
    doc.para(
        "The examination schedule below covers Internal Assessment II, the Practical "
        "Examination and the End-Semester Theory Examination for the odd term of Academic "
        f"Year {c['academic_year']}, for Semester 3 and Semester 5 of Computer Engineering "
        "and Electronics and Telecommunication Engineering (Division A). A hall ticket is "
        "required for every examination listed below and is issued only after fees are "
        "cleared and attendance is certified."
    )

    doc.para("<b>Internal Assessment II</b> "
             f"(window: {_fmt_window(c['odd_term_ia2_window'])})")
    doc.table(EXAM_TABLE_HEADERS, _exam_section_rows("Internal Assessment II"), col_widths=EXAM_TABLE_WIDTHS)

    doc.para("<b>Practical Examination</b> "
             f"(window: {_fmt_window(c['odd_term_practical_window'])})")
    doc.table(EXAM_TABLE_HEADERS, _exam_section_rows("Practical Examination"), col_widths=EXAM_TABLE_WIDTHS)

    doc.para("<b>End-Semester Theory Examination</b> "
             f"(window: {_fmt_window(c['odd_term_theory_exam_window'])})")
    doc.table(EXAM_TABLE_HEADERS, _exam_section_rows("End-Semester Theory"), col_widths=EXAM_TABLE_WIDTHS)

    doc.para(
        "A candidate reporting more than thirty minutes after the start of a session is "
        "not admitted. Results for the odd term are declared on "
        f"{_fmt_date(c['odd_term_result_date'])}."
    )
    doc.signature("exam_controller")
    return doc


# ── registry ──────────────────────────────────────────────────────────────────
# Rendered by corpus/build_student_corpus.py. Filenames are fixed here (not derived from
# dict-iteration order) so a re-run always produces the same set of files.
DOCS = {
    "01_academic_calendar_2026_27.pdf": build_academic_calendar,
    "02_timetable_comp_sem3_divA.pdf": lambda: build_timetable("COMP", 3, "A"),
    "03_timetable_comp_sem5_divA.pdf": lambda: build_timetable("COMP", 5, "A"),
    "04_timetable_extc_sem3_divA.pdf": lambda: build_timetable("EXTC", 3, "A"),
    "05_timetable_extc_sem5_divA.pdf": lambda: build_timetable("EXTC", 5, "A"),
    "06_exam_schedule_odd_2026_27.pdf": build_exam_schedule,
}
