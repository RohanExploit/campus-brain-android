"""General notice board, student handbook, events/holidays, attendance monitoring, and the
consolidated deadline tracker — the five document categories this task renders. Every fact
below is read out of corpus/student_world.py; nothing is invented in this module.

Same build/save split as corpus/render_academic.py: each builder returns an unsaved
NoticeDoc, corpus/build_student_corpus.py calls .save() on every entry in DOCS, and
tests/test_student_corpus_notices.py can build (never save) every document to check what
actually gets printed on the page without touching disk.
"""
import sys
from datetime import date as _date
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from corpus.render_base import NoticeDoc, weighted_col_widths  # noqa: E402
from corpus.student_world import (  # noqa: E402
    ACADEMIC_CALENDAR, ATTENDANCE_POLICY, ATTENDANCE_SPECIMEN, ATTENDANCE_TIERS,
    BONAFIDE_CERTIFICATE, BUS_TRANSPORT, CODE_OF_CONDUCT, CONDONATION_PROCEDURE,
    CONVOCATION, DEADLINES, DRESS_CODE, EVENTS, EXAM_MALPRACTICE, FEES, GRIEVANCE,
    HOLIDAYS, HOSTEL, HOSTEL_RULES, ID_CARD, INSTITUTE, IT_ACCEPTABLE_USE, LAB_SAFETY_RULES,
    LEAVE_PROCEDURE, NOTICE_LOG, PEOPLE, SEMESTER_REGISTRATION, dept_by_code, rupees,
    subject_by_code,
)

OUT = PROJECT_ROOT / "corpus" / "out"


def _fmt_date(iso: str) -> str:
    y, m, d = (int(x) for x in iso.split("-"))
    return _date(y, m, d).strftime("%d %B %Y")


def _fmt_window(window) -> str:
    lo, hi = window
    return f"{_fmt_date(lo)} to {_fmt_date(hi)}"


def _spoc_line(key: str) -> str:
    """A one-line "who to contact" sentence, resolved from PEOPLE — used so a notice names
    its SPOC in the body text, not only in the signature block, which is what lets a
    retriever answer "who handles X" from the notice alone."""
    p = PEOPLE[key]
    return f"{p['name']}, {p['designation']} ({p['email']}, {p['phone']}), during {p['hours']}."


def _tier_range_label(lo: int, hi: int) -> str:
    """ATTENDANCE_TIERS entries are (label, min_pct inclusive, max_pct exclusive,
    consequence); turn the numeric band into the phrase a notice actually prints."""
    if hi > 100:
        return f"{lo}% and above"
    if lo == 0:
        return f"Below {hi}%"
    return f"{lo}% to {hi - 1}%"


def _tier_rows():
    return [[_tier_range_label(lo, hi), cons] for _label, lo, hi, cons in ATTENDANCE_TIERS]


# ── 1. general notice board ───────────────────────────────────────────────────

def build_fee_payment_notice():
    f = FEES
    doc = NoticeDoc(
        filename=OUT / "07_notice_fee_payment.pdf",
        notice_no=NOTICE_LOG["fee_payment_odd"],
        date=_fmt_date("2026-07-01"),
        subject=(
            f"Odd-Term {ACADEMIC_CALENDAR['academic_year']} Tuition, Hostel and Bus Fee "
            "Payment"
        ),
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        "All students are notified to pay the odd-term tuition fee, and the hostel/bus fee "
        f"where applicable, on or before {_fmt_date(f['odd_term_due_date'])}. Tuition varies "
        "by department, as tabulated below."
    )
    doc.table(
        ["Department", "Tuition Fee per Semester"],
        [[dept_by_code(code)[0], f"Rs. {rupees(amt)}"] for code, amt in f["tuition_per_semester"].items()],
        col_widths=weighted_col_widths([3, 2]),
    )
    doc.para(
        f"<b>Hostel fee:</b> Rs. {rupees(f['hostel_fee_per_semester'])} per semester — see "
        f"the Hostel Room Allotment notice ({NOTICE_LOG['hostel_allotment']}) for allotment "
        "details. <b>Bus fee, by route zone:</b>"
    )
    doc.table(
        ["Zone", "Fee per Semester"],
        [[zone, f"Rs. {rupees(amt)}"] for zone, amt in f["bus_fee_per_semester"].items()],
    )
    doc.para(
        f"A late fee of Rs. {f['late_fee_per_day']} per day applies after the due date, "
        f"capped at Rs. {rupees(f['late_fee_cap'])}. Payment may be made through:"
    )
    doc.bullets(f["payment_modes"])
    doc.para(
        f"Fee portal: {f['portal']}. This due date also appears in the Deadline Tracker "
        f"circular ({NOTICE_LOG['deadline_tracker']}). Contact: {_spoc_line(f['spoc_key'])}"
    )
    doc.signature(f["spoc_key"])
    return doc


def build_id_card_reissue_notice():
    d = ID_CARD
    doc = NoticeDoc(
        filename=OUT / "08_notice_id_card_reissue.pdf",
        notice_no=NOTICE_LOG["id_card_reissue"],
        date=_fmt_date("2026-07-10"),
        subject="Procedure for Reissue of a Lost or Damaged Student ID Card",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        "A student who has lost or damaged their ID card must apply for a reissue at the "
        "Student Section. The ID card must be worn visibly at all times on campus — see the "
        f"Student Handbook ({NOTICE_LOG['student_handbook']}) for the full dress code rule. "
        "Documents required:"
    )
    doc.bullets(d["documents_required"])
    doc.para(
        f"Reissue fee: Rs. {d['reissue_fee']}. Turnaround time: {d['turnaround_days']} "
        f"working days from submission. Contact: {_spoc_line(d['spoc_key'])}"
    )
    doc.signature(d["spoc_key"])
    return doc


def build_bonafide_certificate_notice():
    d = BONAFIDE_CERTIFICATE
    doc = NoticeDoc(
        filename=OUT / "09_notice_bonafide_certificate.pdf",
        notice_no=NOTICE_LOG["bonafide_certificate"],
        date=_fmt_date("2026-07-10"),
        subject="Procedure for Obtaining a Bonafide Certificate",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        "A bonafide certificate confirming enrolment is issued by the Student Section on "
        "written request, free of charge, for the following purposes:"
    )
    doc.bullets(d["valid_purposes"])
    doc.para("Documents required:")
    doc.bullets(d["documents_required"])
    fee_text = "Free of charge" if not d["fee"] else f"Rs. {rupees(d['fee'])}"
    doc.para(
        f"Fee: {fee_text}. Turnaround time: {d['turnaround_days']} working days. Contact: "
        f"{_spoc_line(d['spoc_key'])}"
    )
    doc.signature(d["spoc_key"])
    return doc


def build_semester_registration_notice():
    r = SEMESTER_REGISTRATION
    lo, hi = r["even_term_window"]
    doc = NoticeDoc(
        filename=OUT / "10_notice_semester_registration_even.pdf",
        notice_no=NOTICE_LOG["semester_registration_even"],
        date=_fmt_date("2026-12-05"),
        subject=(
            f"Semester Registration for the Even Term, Academic Year "
            f"{ACADEMIC_CALENDAR['academic_year']}"
        ),
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        f"Registration for the even term, which begins "
        f"{_fmt_date(ACADEMIC_CALENDAR['even_term_start'])}, opens {_fmt_date(lo)} and "
        f"closes {_fmt_date(hi)}. A student who has not registered by the closing date is "
        "not permitted to attend even-term classes. Documents required:"
    )
    doc.bullets(r["documents_required"])
    doc.para(
        f"This closing date also appears in the Deadline Tracker circular "
        f"({NOTICE_LOG['deadline_tracker']}). Contact: {_spoc_line(r['spoc_key'])}"
    )
    doc.signature(r["spoc_key"])
    return doc


def build_hostel_allotment_notice():
    h = HOSTEL
    lo, hi = h["application_window"]
    doc = NoticeDoc(
        filename=OUT / "11_notice_hostel_allotment.pdf",
        notice_no=NOTICE_LOG["hostel_allotment"],
        date=_fmt_date(lo),
        subject=f"Hostel Room Allotment, Academic Year {ACADEMIC_CALENDAR['academic_year']}",
        to=f"All Students Seeking Hostel Accommodation, {INSTITUTE['short']}",
    )
    doc.para(
        f"{h['total_rooms']} rooms are available across {' and '.join(h['blocks'])}, with "
        f"{h['occupancy']}. Applications open {_fmt_date(lo)} and close {_fmt_date(hi)}. "
        f"Allotment basis: {h['allotment_basis']}. Documents required:"
    )
    doc.bullets(h["documents_required"])
    doc.para(
        f"Hostel fee: Rs. {rupees(FEES['hostel_fee_per_semester'])} per semester — see the "
        f"Fee Payment notice ({NOTICE_LOG['fee_payment_odd']}). Mess charges: Rs. "
        f"{rupees(h['mess_charges_per_month'])} per month, billed separately. This closing "
        f"date also appears in the Deadline Tracker circular "
        f"({NOTICE_LOG['deadline_tracker']}). Contact: {_spoc_line(h['warden_key'])}"
    )
    doc.signature(h["warden_key"])
    return doc


def build_bus_pass_renewal_notice():
    b = BUS_TRANSPORT
    lo, hi = b["renewal_window"]
    doc = NoticeDoc(
        filename=OUT / "12_notice_bus_pass_renewal.pdf",
        notice_no=NOTICE_LOG["bus_pass_renewal"],
        date=_fmt_date(lo),
        subject=f"Institute Bus Pass Renewal, Academic Year {ACADEMIC_CALENDAR['academic_year']}",
        to=f"All Bus Pass Holders, {INSTITUTE['short']}",
    )
    doc.para(
        f"Students availing the institute bus service must renew their pass between "
        f"{_fmt_date(lo)} and {_fmt_date(hi)}. Routes and fee zones:"
    )
    doc.table(
        ["Route", "Area Covered", "Fee Zone"],
        [[r[0], r[1], r[2]] for r in b["routes"]],
        col_widths=weighted_col_widths([2, 5, 4]),
    )
    doc.para("Fee by zone:")
    doc.table(
        ["Zone", "Fee per Semester"],
        [[zone, f"Rs. {rupees(amt)}"] for zone, amt in FEES["bus_fee_per_semester"].items()],
    )
    doc.para("Documents required:")
    doc.bullets(b["documents_required"])
    doc.para(
        f"This closing date also appears in the Deadline Tracker circular "
        f"({NOTICE_LOG['deadline_tracker']}). Contact: {_spoc_line(b['incharge_key'])}"
    )
    doc.signature(b["incharge_key"])
    return doc


def build_dress_code_notice():
    d = DRESS_CODE
    doc = NoticeDoc(
        filename=OUT / "13_notice_dress_code.pdf",
        notice_no=NOTICE_LOG["dress_code_reminder"],
        date=_fmt_date("2026-07-13"),
        subject="Reminder: Campus Dress Code",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        "All students are reminded of the dress code in effect on campus, effective from "
        "the start of the odd term:"
    )
    doc.bullets([
        f"Monday, Tuesday, Thursday, Friday: {d['weekday_code']}",
        f"Wednesday and Saturday: {d['wednesday_saturday_code']}",
        f"Laboratories: {d['lab_code']}",
        d["id_card_rule"],
    ])
    doc.para(
        f"The full code of conduct is set out in the Student Handbook "
        f"({NOTICE_LOG['student_handbook']}). Contact: {_spoc_line(d['spoc_key'])}"
    )
    doc.signature(d["spoc_key"])
    return doc


def build_convocation_registration_notice():
    c = CONVOCATION
    lo, hi = c["registration_window"]
    doc = NoticeDoc(
        filename=OUT / "14_notice_convocation_registration.pdf",
        notice_no=NOTICE_LOG["convocation_registration"],
        date=_fmt_date(lo),
        subject=f"Convocation {_fmt_date(c['ceremony_date'])} — Registration for Graduating Students",
        to="Final-Year Students Eligible for Graduation",
    )
    doc.para(
        f"The convocation ceremony will be held on {_fmt_date(c['ceremony_date'])}. "
        f"Registration opens {_fmt_date(lo)} and closes {_fmt_date(hi)}. Eligibility: "
        f"{c['eligibility']}. Documents required:"
    )
    doc.bullets(c["documents_required"])
    doc.para(
        f"Registration fee: Rs. {rupees(c['registration_fee'])}. Gown hire fee: Rs. "
        f"{rupees(c['gown_hire_fee'])}. This closing date also appears in the Deadline "
        f"Tracker circular ({NOTICE_LOG['deadline_tracker']}). Contact: "
        f"{_spoc_line(c['spoc_key'])}"
    )
    doc.signature(c["spoc_key"])
    return doc


# ── 2. rules and guidelines (student handbook) ────────────────────────────────

def build_student_handbook():
    doc = NoticeDoc(
        filename=OUT / "15_student_handbook.pdf",
        notice_no=NOTICE_LOG["student_handbook"],
        date=_fmt_date("2026-07-01"),
        subject=f"Student Handbook (Extract), Academic Year {ACADEMIC_CALENDAR['academic_year']}",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        "This extract consolidates the code of conduct, examination regulations, hostel and "
        "laboratory safety rules, IT acceptable-use policy, anti-ragging regulations, dress "
        "code and leave procedure that every student is expected to know."
    )
    doc.para("<b>1. Code of Conduct</b>")
    doc.bullets(CODE_OF_CONDUCT)
    doc.para("<b>2. Examination Malpractice — Offences and Penalties</b>")
    doc.table(["Offence", "Penalty"], EXAM_MALPRACTICE, col_widths=weighted_col_widths([5, 4]))
    doc.para("<b>3. Hostel Rules</b>")
    doc.bullets(HOSTEL_RULES)
    doc.para("<b>4. Laboratory Safety Rules</b>")
    doc.bullets(LAB_SAFETY_RULES)
    doc.para("<b>5. IT and Network Acceptable Use</b>")
    doc.bullets(IT_ACCEPTABLE_USE)
    ar = GRIEVANCE["anti_ragging"]
    doc.para("<b>6. Anti-Ragging Regulations</b>")
    doc.bullets([
        ar["affidavit_requirement"],
        f"Institute committee: {ar['committee']}.",
        f"UGC Anti-Ragging Helpline (toll-free, 24x7): {ar['ugc_helpline']}, email "
        f"{ar['ugc_helpline_email']}.",
        f"Institute Anti-Ragging SPOC: {_spoc_line(ar['spoc_key'])}",
    ])
    doc.para("<b>7. Dress Code</b>")
    doc.bullets([
        DRESS_CODE["weekday_code"], DRESS_CODE["wednesday_saturday_code"],
        DRESS_CODE["lab_code"], DRESS_CODE["id_card_rule"],
    ])
    doc.para("<b>8. Leave Procedure</b>")
    lp = LEAVE_PROCEDURE
    doc.bullets([
        f"Types of leave: {', '.join(lp['types'])}.",
        lp["application_route"],
        f"Planned (non-medical) leave requires at least {lp['advance_notice_days']} days' "
        "advance notice.",
        f"Medical leave: {ATTENDANCE_POLICY['medical_leave_doc']}, up to "
        f"{ATTENDANCE_POLICY['medical_leave_max_days']} days.",
        lp["on_duty_approval"],
    ])
    doc.signature("principal")
    return doc


# ── 3. events and holidays ─────────────────────────────────────────────────────

def build_holiday_list():
    doc = NoticeDoc(
        filename=OUT / "16_holiday_list_2026_27.pdf",
        notice_no=NOTICE_LOG["holiday_list"],
        date=_fmt_date("2026-06-20"),
        subject=f"Holiday List, Academic Year {ACADEMIC_CALENDAR['academic_year']}",
        to=f"All Students and Faculty, {INSTITUTE['short']}",
    )
    doc.para(
        "The list of holidays observed by the institute during the academic year "
        f"{ACADEMIC_CALENDAR['academic_year']} is notified below."
    )
    doc.table(
        ["Date", "Day", "Occasion", "Type"],
        [
            [
                _fmt_date(d),
                _date(*(int(x) for x in d.split("-"))).strftime("%A"),
                occ, typ.capitalize(),
            ]
            for d, occ, typ in HOLIDAYS
        ],
        col_widths=weighted_col_widths([2, 2, 4, 2]),
    )
    doc.para(
        "A holiday falling within an examination window does not shift the examination "
        "unless a separate circular is issued by the Examination Section."
    )
    doc.signature("principal")
    return doc


# Issue date for each event notice — hardcoded here (not in the world model), mirroring how
# corpus/render_academic.py hardcodes each notice's own issue date inline.
_EVENT_ISSUE_DATE = {
    "event_tech_fest": "2026-08-25",
    "event_cultural_fest": "2027-01-15",
    "event_sports_meet": "2026-10-20",
    "event_industry_visit": "2026-08-20",
    "event_guest_lecture": "2026-09-25",
    "event_alumni_meet": "2026-11-25",
    "event_nss_camp": "2026-12-01",
}

# File-name sequence, fixed explicitly (not derived from EVENTS' list order) so a re-run
# always names the same document the same thing, mirroring render_academic.build_timetable's
# file_seq pattern.
_EVENT_FILE_SEQ = {
    "event_tech_fest": 17, "event_cultural_fest": 18, "event_sports_meet": 19,
    "event_industry_visit": 20, "event_guest_lecture": 21, "event_alumni_meet": 22,
    "event_nss_camp": 23,
}


def build_event_notice(event):
    lo, hi = event["date_start"], event["date_end"]
    when = _fmt_date(lo) if lo == hi else f"{_fmt_date(lo)} to {_fmt_date(hi)}"
    notice_key = event["notice_key"]
    slug = notice_key.removeprefix("event_")
    doc = NoticeDoc(
        filename=OUT / f"{_EVENT_FILE_SEQ[notice_key]:02d}_event_{slug}.pdf",
        notice_no=NOTICE_LOG[notice_key],
        date=_fmt_date(_EVENT_ISSUE_DATE[notice_key]),
        subject=f"{event['name']} — {when}",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(event["description"])
    doc.para(f"<b>Venue:</b> {event['venue']}. <b>Dates:</b> {when}.")
    if event["registration_deadline"]:
        fee_text = (
            "no registration fee" if not event["registration_fee"]
            else f"registration fee Rs. {rupees(event['registration_fee'])}"
        )
        doc.para(
            f"Registration closes {_fmt_date(event['registration_deadline'])} ({fee_text}). "
            f"This date also appears in the Deadline Tracker circular "
            f"({NOTICE_LOG['deadline_tracker']})."
        )
    else:
        doc.para("Attendance is open to all students; no registration is required.")
    doc.para(f"Coordinator: {_spoc_line(event['coordinator_key'])}")
    doc.signature(event["coordinator_key"])
    return doc


# ── 4. attendance monitoring and defaulter management ─────────────────────────

def build_attendance_policy_notice():
    p = ATTENDANCE_POLICY
    doc = NoticeDoc(
        filename=OUT / "24_attendance_policy.pdf",
        notice_no=NOTICE_LOG["attendance_policy"],
        date=_fmt_date("2026-07-15"),
        subject=f"Attendance Policy, Academic Year {ACADEMIC_CALENDAR['academic_year']}",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        f"A minimum of {p['min_attendance_pct']}% attendance, calculated subject-wise, is "
        "required to be eligible to appear for the End-Semester examination without "
        "condonation. The attendance tiers below apply."
    )
    doc.table(["Attendance Range", "Consequence"], _tier_rows(), col_widths=weighted_col_widths([2, 5]))
    doc.para(
        f"Medical leave of up to {p['medical_leave_max_days']} days is accepted with: "
        f"{p['medical_leave_doc']}. Students on the defaulter list may appeal to the "
        f"{p['committee']} — see the Defaulter List Procedure "
        f"({NOTICE_LOG['attendance_defaulter_procedure']}) and the Condonation Procedure "
        f"({NOTICE_LOG['attendance_condonation']})."
    )
    doc.para(f"Appeal route: {p['appeal_route']}. Contact: {_spoc_line(p['committee_chair_key'])}")
    doc.signature(p["committee_chair_key"])
    return doc


def build_attendance_defaulter_procedure():
    p = ATTENDANCE_POLICY
    doc = NoticeDoc(
        filename=OUT / "25_attendance_defaulter_procedure.pdf",
        notice_no=NOTICE_LOG["attendance_defaulter_procedure"],
        date=_fmt_date("2026-09-15"),
        subject="Procedure for Publication of the Attendance Defaulter List",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        "The Attendance Coordinator publishes a subject-wise defaulter list on the notice "
        "board once every month, listing every student whose counted attendance in any "
        "subject falls in the tiers below."
    )
    doc.table(["Attendance Range", "Consequence"], _tier_rows(), col_widths=weighted_col_widths([2, 5]))
    doc.para(
        f"A student on the defaulter list whose attendance falls in the condonable band "
        f"({p['condonation_band_low_pct']}% to {p['condonation_band_high_pct'] - 1}%) may "
        f"apply for condonation — see the Condonation Procedure "
        f"({NOTICE_LOG['attendance_condonation']}). {p['appeal_route']}."
    )
    doc.signature(p["committee_chair_key"])
    return doc


def build_attendance_condonation_notice():
    c = CONDONATION_PROCEDURE
    lo, hi = c["eligible_band"]
    doc = NoticeDoc(
        filename=OUT / "26_attendance_condonation.pdf",
        notice_no=NOTICE_LOG["attendance_condonation"],
        date=_fmt_date("2026-09-20"),
        subject="Attendance Condonation Application Procedure",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        f"A student with subject-wise attendance between {lo}% and {hi - 1}% may apply for "
        f"condonation. Condonation can raise counted attendance by at most "
        f"{c['max_grant_pct']} percentage points and must be supported by documentation."
    )
    doc.para("<b>Application form fields</b>")
    doc.bullets(c["form_fields"])
    doc.para("<b>Supporting documents</b>")
    doc.bullets(c["supporting_documents"])
    doc.para(
        f"Applications must be submitted within {c['submission_deadline_days_after_list']} "
        f"days of the defaulter list being published, to the {c['committee']}. Decisions are "
        f"communicated within {c['decision_timeline_days']} working days. Contact: "
        f"{_spoc_line(c['chair_key'])}"
    )
    doc.signature(c["chair_key"])
    return doc


def build_attendance_specimen_statement():
    s = ATTENDANCE_SPECIMEN
    rows = []
    total_held = total_attended = 0
    for code, held, attended in s["rows"]:
        subj = subject_by_code(code)
        pct = round(attended / held * 100, 1)
        total_held += held
        total_attended += attended
        rows.append([code, subj[1], str(held), str(attended), f"{pct}%"])
    overall_pct = round(total_attended / total_held * 100, 1)
    rows.append(["Overall", "", str(total_held), str(total_attended), f"{overall_pct}%"])

    doc = NoticeDoc(
        filename=OUT / "27_attendance_specimen_statement.pdf",
        notice_no=NOTICE_LOG["attendance_specimen_statement"],
        date=_fmt_date("2026-10-01"),
        subject=f"Specimen Monthly Attendance Statement — {s['month']}",
        to=(
            f"Illustrative Example, {dept_by_code(s['dept_code'])[0]} Semester "
            f"{s['semester']} Division {s['division']}"
        ),
    )
    doc.para(f"<i>{s['note']}</i>")
    doc.para(
        f"{s['student_label']}, {dept_by_code(s['dept_code'])[0]}, Semester "
        f"{s['semester']}, Division {s['division']}. Month: {s['month']}."
    )
    doc.table(
        ["Code", "Subject", "Lectures Held", "Lectures Attended", "Percentage"],
        rows,
        col_widths=weighted_col_widths([2, 5, 2, 2, 2]),
    )
    below = [r for r in rows[:-1] if float(r[4].rstrip("%")) < ATTENDANCE_POLICY["min_attendance_pct"]]
    if below:
        names = ", ".join(f"{r[0]} ({r[4]})" for r in below)
        doc.para(
            f"A student reads this statement subject-wise, not only by the overall figure: "
            f"the overall percentage above ({overall_pct}%) is comfortably above the "
            f"{ATTENDANCE_POLICY['min_attendance_pct']}% minimum, but {names} falls below it "
            "and would place this specimen on the defaulter list for that subject alone — "
            f"see the Defaulter List Procedure ({NOTICE_LOG['attendance_defaulter_procedure']})."
        )
    doc.signature("attendance_coordinator")
    return doc


# ── 5. deadline tracker ────────────────────────────────────────────────────────

# Circular issue date, chosen (see corpus/student_world.py's DEADLINES additions) to fall
# before every single deadline in DEADLINES, so "days remaining" below is always a positive,
# genuinely "upcoming" number rather than a mix of past and future.
_TRACKER_ISSUE_DATE = "2026-06-15"


def build_deadline_tracker():
    issue = _date.fromisoformat(_TRACKER_ISSUE_DATE)
    rows = []
    for item, iso_date, spoc_key, notice_no in sorted(DEADLINES, key=lambda d: d[1]):
        due = _date.fromisoformat(iso_date)
        days_remaining = (due - issue).days
        p = PEOPLE[spoc_key]
        rows.append([item, _fmt_date(iso_date), str(days_remaining), f"{p['name']} ({p['role']})", notice_no])

    doc = NoticeDoc(
        filename=OUT / "28_deadline_tracker.pdf",
        notice_no=NOTICE_LOG["deadline_tracker"],
        date=_fmt_date(_TRACKER_ISSUE_DATE),
        subject=f"Upcoming Deadlines — Academic Year {ACADEMIC_CALENDAR['academic_year']}",
        to=f"All Students, {INSTITUTE['short']}",
    )
    doc.para(
        "The consolidated list below tracks every deadline a student needs to act on during "
        f"the academic year {ACADEMIC_CALENDAR['academic_year']}, with the number of days "
        f"remaining as of the date of this circular ({_fmt_date(_TRACKER_ISSUE_DATE)}) and "
        "the notice that first announced each deadline. Students should re-check the notice "
        "board close to each date, since a subsequent circular can revise it."
    )
    doc.table(
        ["Item", "Date", "Days Remaining", "Owning SPOC", "Announced By"],
        rows,
        col_widths=weighted_col_widths([3, 2, 2, 3, 4]),
    )
    doc.signature("registrar")
    return doc


# ── registry ──────────────────────────────────────────────────────────────────
# Rendered by corpus/build_student_corpus.py. Filenames are fixed here (not derived from
# dict-iteration order) so a re-run always produces the same set of files.
DOCS = {
    "07_notice_fee_payment.pdf": build_fee_payment_notice,
    "08_notice_id_card_reissue.pdf": build_id_card_reissue_notice,
    "09_notice_bonafide_certificate.pdf": build_bonafide_certificate_notice,
    "10_notice_semester_registration_even.pdf": build_semester_registration_notice,
    "11_notice_hostel_allotment.pdf": build_hostel_allotment_notice,
    "12_notice_bus_pass_renewal.pdf": build_bus_pass_renewal_notice,
    "13_notice_dress_code.pdf": build_dress_code_notice,
    "14_notice_convocation_registration.pdf": build_convocation_registration_notice,
    "15_student_handbook.pdf": build_student_handbook,
    "16_holiday_list_2026_27.pdf": build_holiday_list,
    "24_attendance_policy.pdf": build_attendance_policy_notice,
    "25_attendance_defaulter_procedure.pdf": build_attendance_defaulter_procedure,
    "26_attendance_condonation.pdf": build_attendance_condonation_notice,
    "27_attendance_specimen_statement.pdf": build_attendance_specimen_statement,
    "28_deadline_tracker.pdf": build_deadline_tracker,
}
for _event in EVENTS:
    _key = _event["notice_key"]
    _slug = _key.removeprefix("event_")
    DOCS[f"{_EVENT_FILE_SEQ[_key]:02d}_event_{_slug}.pdf"] = (lambda e=_event: build_event_notice(e))
