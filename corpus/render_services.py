"""Scholarships/schemes, placements, training, incubation, library and grievance/contacts —
the decision-support half of the student corpus. Every fact rendered below is read out of
corpus/student_world.py; nothing here is invented in the renderer.

Same build/save split as corpus/render_academic.py: each builder returns an unsaved
NoticeDoc, corpus/build_student_corpus.py calls .save() on every entry in DOCS, and
tests/test_student_corpus_services.py can build (never save) every document to check what
actually gets printed on the page without touching disk.
"""
import sys
from datetime import date as _date
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from corpus.render_base import NoticeDoc, weighted_col_widths  # noqa: E402
from corpus.student_world import (  # noqa: E402
    ATTENDANCE_POLICY, DEPARTMENTS, GRIEVANCE, INCUBATION, LIBRARY, NOTICE_LOG, PEOPLE,
    PLACEMENT_POLICY, PLACEMENTS, SCHEMES, SPOC_DIRECTORY, SPORTS_CULTURAL_BENEFITS,
    TRAINING, dept_by_code, person, rupees,
)

OUT = PROJECT_ROOT / "corpus" / "out"


def _fmt_date(iso: str) -> str:
    y, m, d = (int(x) for x in iso.split("-"))
    return _date(y, m, d).strftime("%d %B %Y")


def _dept_names(codes):
    return ", ".join(dept_by_code(c)[0] for c in codes)


def _money(n):
    return "No ceiling" if n is None else f"Rs. {rupees(n)} per annum"


def _cgpa(n):
    return "No minimum" if n is None else f"{n:.2f}"


def _person_name(key):
    return PEOPLE[key]["name"]


def _person_contact_line(key):
    p = PEOPLE[key]
    return f"{p['name']}, {p['role'] or p['designation']} (email: {p['email']}, phone: {p['phone']})"


# ── 1. scholarship eligibility matrix ────────────────────────────────────────
# The decision-support centrepiece: every column a student needs to self-assess eligibility,
# in one table, sourced entirely from student_world.SCHEMES.

def build_scholarship_eligibility_matrix():
    doc = NoticeDoc(
        filename=OUT / "svc_01_scholarship_eligibility_matrix.pdf",
        notice_no=NOTICE_LOG["scholarship_eligibility_matrix"],
        date=_fmt_date("2026-08-25"),
        subject="Scholarship and Fee-Waiver Scheme Eligibility Matrix, Academic Year 2026-27",
        to="All Students",
    )
    doc.para(
        "The two tables below together give every field needed to self-assess eligibility "
        "and to know what to do next, keyed by scheme name. A separate procedure notice for "
        "each scheme (cited by notice number below) explains how to apply step by step. "
        "Queries on any scheme go to the SPOC named in the second table."
    )
    doc.para("<b>Eligibility at a glance</b>")
    doc.table(
        ["Scheme", "Eligible Category", "Income Ceiling", "Min. Attendance", "Min. CGPA"],
        [
            [s["name"], s["category_eligible"], _money(s["income_ceiling"]),
             f"{s['min_attendance_pct']}%", _cgpa(s["min_cgpa"])]
            for s in SCHEMES
        ],
        # A wide first pass at a single 9-column table put "Min. Attendance"/"Min. CGPA" so
        # narrow that reportlab had to break words mid-token (e.g. "minimum" -> "min/imu/m")
        # to fit -- exactly the mid-word-wrap failure mode render_academic.py's FACULTY_CODE
        # comment warns about, and it corrupts extraction when a downstream parser joins a
        # cell's wrapped lines back into text. Splitting into two scheme-keyed tables (this
        # one plus "How to apply" below) gives every column enough width to wrap only on
        # whole words, at the cost of repeating the Scheme column once.
        col_widths=weighted_col_widths([3.4, 3.2, 2.0, 1.8, 1.6]),
    )
    doc.para("<b>How to apply</b>")
    doc.table(
        ["Scheme", "Documents Required", "Application Window", "SPOC",
         "Disbursement Timeline"],
        [
            [s["name"], "; ".join(s["documents_required"]), s["application_window"],
             _person_name(s["spoc"]), s["disbursement_timeline"]]
            for s in SCHEMES
        ],
        col_widths=weighted_col_widths([2.6, 4.2, 2.2, 2.0, 3.0]),
    )
    doc.para(
        "A separate procedure notice for each scheme above states its own notice number, "
        "the exact steps to apply, and what happens after submission."
    )
    doc.para(
        "<b>Note:</b> the Rajarshi Shahu Maharaj Merit Scholarship and the Rajarshi Shahu "
        "Maharaj Freeship for EBC are two distinct schemes with two distinct income "
        "ceilings; read the Eligible Category and Income Ceiling columns carefully rather "
        "than the scheme name alone. The Sports and Cultural Excellence Scholarship uses a "
        f"minimum attendance of {SPORTS_CULTURAL_BENEFITS['attendance_relaxation_pct']}%, "
        f"lower than the institute's general {ATTENDANCE_POLICY['min_attendance_pct']}% "
        "minimum in the Attendance Policy — that relaxation applies only to this scheme and "
        "to the sports/cultural benefits described in a separate notice."
    )
    doc.signature("scholarship_spoc")
    return doc


# ── 2. procedure notice per scheme ───────────────────────────────────────────

def build_scheme_procedure(scheme_name, seq):
    s = next(x for x in SCHEMES if x["name"] == scheme_name)
    slug = scheme_name.lower().replace(" ", "_").replace("/", "_")
    doc = NoticeDoc(
        filename=OUT / f"svc_{seq:02d}_scholarship_procedure_{slug}.pdf",
        notice_no=NOTICE_LOG[s["notice_key"]],
        date=_fmt_date("2026-08-25"),
        subject=f"How to Apply: {s['name']}",
        to="Eligible Students",
    )
    doc.para(
        f"<b>{s['name']}</b> is open to {s['category_eligible']} with family income up to "
        f"{_money(s['income_ceiling'])}, a minimum attendance of {s['min_attendance_pct']}% "
        f"and a minimum CGPA of {_cgpa(s['min_cgpa'])}. The application window is "
        f"{s['application_window']}. Apply through: <b>{s['portal']}</b>."
    )
    doc.para("<b>Documents to attach</b>")
    doc.bullets(s["documents_required"])
    doc.para("<b>Steps to apply</b>")
    doc.bullets(s["application_steps"])
    doc.para(f"<b>After submission:</b> {s['after_submission']}")
    doc.para(
        f"Disbursement timeline: {s['disbursement_timeline']}. For queries, contact the "
        f"scheme SPOC below."
    )
    doc.signature(s["spoc"])
    return doc


# ── 3. sports and cultural benefits ──────────────────────────────────────────

def build_sports_cultural_benefits():
    b = SPORTS_CULTURAL_BENEFITS
    doc = NoticeDoc(
        filename=OUT / "svc_07_sports_cultural_benefits.pdf",
        notice_no=NOTICE_LOG[b["notice_key"]],
        date=_fmt_date("2026-08-25"),
        subject="Sports and Cultural Benefits for Institute Representatives",
        to="All Students",
    )
    doc.para(
        "Students who represent KRIET at state or national level sports or cultural events "
        "are entitled to the benefits below, in addition to (and distinct from) the Sports "
        "and Cultural Excellence Scholarship described in the scholarship eligibility "
        f"matrix (notice {NOTICE_LOG['scholarship_eligibility_matrix']})."
    )
    doc.para(f"<b>Admission quota:</b> {b['quota_description']}")
    doc.para(
        f"<b>Attendance relaxation:</b> a counted attendance minimum of "
        f"{b['attendance_relaxation_pct']}% applies to institute representatives, against "
        f"the general {ATTENDANCE_POLICY['min_attendance_pct']}% minimum in the institute's "
        f"Attendance Policy. {b['on_duty_provision']}"
    )
    doc.para("<b>Prize money</b>")
    doc.table(
        ["Level", "Position", "Prize Money"],
        [[lvl, pos, f"Rs. {rupees(amt)}"] for lvl, pos, amt in b["prize_money"]],
    )
    doc.para(f"<b>Kit allowance:</b> Rs. {rupees(b['kit_allowance_per_year'])} per year.")
    doc.para("<b>Eligibility proof required</b>")
    doc.bullets(b["eligibility_proof_required"])
    doc.signature(b["spoc_key"])
    return doc


# ── 4. placement policy ──────────────────────────────────────────────────────

def build_placement_policy():
    p = PLACEMENT_POLICY
    doc = NoticeDoc(
        filename=OUT / "svc_08_placement_policy.pdf",
        notice_no=NOTICE_LOG["placement_policy"],
        date=_fmt_date("2026-08-20"),
        subject="Placement Policy, Academic Year 2026-27",
        to="Final and Pre-Final Year Students",
    )
    doc.para(
        "The following rules govern participation in campus placement drives conducted by "
        "the Training and Placement Cell."
    )
    doc.para(f"<b>Registration:</b> {p['registration_rule']}")
    doc.para(f"<b>One-offer rule:</b> {p['one_offer_rule']}")
    doc.para(f"<b>Backlog policy:</b> {p['backlog_policy']}")
    doc.para(f"<b>Dress code:</b> {p['dress_code']}")
    doc.para(f"<b>Absenteeism penalty:</b> {p['absenteeism_penalty']}")
    doc.para(
        "Individual company drive notices (company, role, package, eligibility, dates and "
        "selection rounds) are issued separately by the Placement Cell as each drive is "
        "confirmed."
    )
    doc.signature(p["spoc_key"])
    return doc


# ── 5. company drive notices ─────────────────────────────────────────────────

COMPANY_NOTICE_KEY = {
    "Ratnagiri Softworks": "placement_ratnagiri_softworks",
    "Konkangiri Analytics": "placement_konkangiri_analytics",
    "Malvan Robotics Pvt Ltd": "placement_malvan_robotics",
    "Vishalgad Power Systems": "placement_vishalgad_power",
    "Devbaug Structall Engineers": "placement_devbaug_structall",
    "Sindhu Cloud Systems": "placement_sindhu_cloud",
}


def build_placement_drive(company, seq):
    row = next(r for r in PLACEMENTS if r[0] == company)
    (company, role, package, min_cgpa, max_backlogs, branches, reg_deadline, drive_date,
     spoc_key, rounds) = row
    slug = company.lower().replace(" ", "_").replace(".", "")
    doc = NoticeDoc(
        filename=OUT / f"svc_{seq:02d}_placement_drive_{slug}.pdf",
        notice_no=NOTICE_LOG[COMPANY_NOTICE_KEY[company]],
        date=_fmt_date("2026-08-20"),
        subject=f"Campus Placement Drive: {company}",
        to=f"Eligible Students of {_dept_names(branches)}",
    )
    doc.para(
        f"{company} will conduct a campus placement drive on {_fmt_date(drive_date)}. "
        f"Registration closes on {_fmt_date(reg_deadline)}. Interested and eligible "
        f"students must register with the Placement Cell before the deadline; the "
        f"institute's Placement Policy (one-offer rule, backlog policy, dress code) "
        f"applies to this drive."
    )
    doc.table(
        ["Field", "Detail"],
        [
            ["Role", role],
            ["Package", f"{package} LPA"],
            ["Eligible Branches", _dept_names(branches)],
            ["Minimum CGPA", f"{min_cgpa:.1f}"],
            ["Maximum Live Backlogs Allowed", str(max_backlogs)],
            ["Registration Deadline", _fmt_date(reg_deadline)],
            ["Drive Date", _fmt_date(drive_date)],
            ["SPOC", _person_name(spoc_key)],
        ],
        col_widths=weighted_col_widths([3, 6]),
    )
    doc.para("<b>Selection rounds, in order</b>")
    doc.bullets(rounds)
    doc.signature(spoc_key)
    return doc


# ── 6. training programmes ───────────────────────────────────────────────────

def build_training_programmes():
    doc = NoticeDoc(
        filename=OUT / "svc_15_training_programmes.pdf",
        notice_no=NOTICE_LOG["training_programmes"],
        date=_fmt_date("2026-08-18"),
        subject="Training Programmes, Academic Year 2026-27",
        to="All Students",
    )
    doc.para(
        "The Training and Placement Cell and the departments below offer the following "
        "in-house and external training programmes this academic year, in addition to the "
        "regular curriculum."
    )
    doc.table(
        ["Programme", "Provider", "Duration", "Fee", "Eligibility", "Certification"],
        [
            [prog, provider, duration, f"Rs. {rupees(fee)}", eligibility, cert]
            for prog, provider, duration, fee, eligibility, cert, _coord, _sched, _enrol
            in TRAINING
        ],
        col_widths=weighted_col_widths([2.6, 2.0, 1.8, 1.1, 2.6, 2.6]),
    )
    doc.para("<b>Schedule, coordinator and how to enrol</b>")
    doc.table(
        ["Programme", "Schedule", "Coordinator", "How to Enrol"],
        [
            [prog, sched, _person_name(coord), enrol]
            for prog, _prov, _dur, _fee, _elig, _cert, coord, sched, enrol in TRAINING
        ],
        col_widths=weighted_col_widths([2.2, 2.6, 1.8, 4.4]),
    )
    doc.signature("tpo")
    return doc


# ── 7. incubation centre ─────────────────────────────────────────────────────

def build_incubation_centre():
    inc = INCUBATION
    doc = NoticeDoc(
        filename=OUT / "svc_16_incubation_centre.pdf",
        notice_no=NOTICE_LOG["incubation_cohort5"],
        date=_fmt_date("2026-08-15"),
        subject=f"{inc['name']}: Offerings, Funding and Cohort 5/6 Applications",
        to="All Students",
    )
    doc.para(
        f"The {inc['name']} (established {inc['established']}) supports student "
        "entrepreneurship with the following offerings."
    )
    doc.bullets(inc["offers"])
    doc.para("<b>Funding tiers</b>")
    doc.table(
        ["Tier", "Amount", "Stage Required"],
        [[name, f"Rs. {rupees(amt)}", stage] for name, amt, stage in inc["funding_tiers"]],
    )
    doc.para(f"<b>Application procedure:</b> {inc['application_procedure']}")
    doc.para(
        f"Cohort 5 concept notes close on {_fmt_date(inc['application_deadline_cohort5'])}; "
        f"Cohort 6 concept notes close on {_fmt_date(inc['application_deadline_cohort6'])}."
    )
    doc.para(f"<b>IP policy:</b> {inc['ip_policy']}")
    doc.para(f"<b>Seed grant terms:</b> {inc['seed_grant_terms']}")
    doc.para("<b>Mentors</b>")
    doc.bullets(inc["mentors"])
    doc.para("<b>Cohort 2026 startups</b>")
    doc.table(
        ["Startup", "Description", "Tier Reached"],
        [[name, desc, tier] for name, desc, tier in inc["cohort_2026"]],
        col_widths=weighted_col_widths([2.5, 5, 2.5]),
    )
    doc.signature(inc["manager_key"])
    return doc


# ── 8. library services and rules ────────────────────────────────────────────

def build_library_services():
    lib = LIBRARY
    doc = NoticeDoc(
        filename=OUT / "svc_17_library_services_and_rules.pdf",
        notice_no=NOTICE_LOG["library_fine_waiver"],
        date=_fmt_date("2026-08-10"),
        subject=f"{lib['name']}: Services and Rules",
        to="All Students and Faculty",
    )
    doc.para(
        f"The Central Library holds {lib['titles']:,} titles and {lib['journals_print']} "
        f"print journals. Hours: Monday-Saturday {lib['weekday_hours']}, "
        f"Saturday {lib['saturday_hours']}, Sunday {lib['sunday_hours']}."
    )
    doc.para("<b>Borrowing limits by user category</b>")
    doc.table(
        ["Category", "Books Allowed", "Loan Period"],
        [[cat, str(n), f"{days} days"] for cat, (n, days) in lib["loan_limits"].items()],
    )
    doc.para(
        f"<b>Fine structure:</b> Rs. {lib['fine_per_day']} per day per book overdue, capped "
        f"at Rs. {lib['max_fine_per_book']} per book. {lib['overdue_notice']}"
    )
    doc.para(f"<b>Reservation:</b> {lib['reservation_rule']}")
    doc.para(f"<b>Renewal:</b> {lib['renewal_rule']}")
    doc.para(
        "<b>Digital subscriptions:</b> " + ", ".join(lib["e_resources"]) + "."
    )
    doc.para(f"<b>Remote access:</b> {lib['remote_access']}")
    doc.para(
        "Reference section titles are for in-library use only and are not loanable."
        if not lib["reference_section_loanable"] else ""
    )
    doc.para(f"<b>Lost-book policy:</b> {lib['lost_book_policy']}")
    doc.signature(lib["librarian_key"])
    return doc


# ── 9. grievance redressal and anti-ragging ──────────────────────────────────

def build_grievance_and_antiragging():
    g = GRIEVANCE
    ar = g["anti_ragging"]
    doc = NoticeDoc(
        filename=OUT / "svc_18_grievance_and_anti_ragging.pdf",
        notice_no=NOTICE_LOG["grievance_affidavit"],
        date=_fmt_date("2026-08-01"),
        subject=f"{g['committee_name']} and Anti-Ragging Policy",
        to="All Students",
    )
    doc.para(
        f"The {g['committee_name']} handles complaints in the following categories: "
        + "; ".join(g["categories"]) + "."
    )
    doc.para(
        "<b>Committee composition:</b> " +
        ", ".join(_person_name(k) for k in g["member_keys"]) + "."
    )
    doc.para(f"Online portal: {g['portal']}")
    doc.para("<b>Escalation levels</b>")
    doc.table(
        ["Level", "Description", "Timeline"],
        [[lvl, desc, tl] for lvl, desc, tl in g["escalation"]],
        col_widths=weighted_col_widths([1.2, 4.5, 3]),
    )
    doc.para(
        f"<b>Anti-ragging:</b> {ar['committee']}. {ar['affidavit_requirement']} "
        f"24x7 UGC Anti-Ragging Helpline: {ar['ugc_helpline']} "
        f"(email: {ar['ugc_helpline_email']})."
    )
    doc.signature(g["chair_key"])
    return doc


# ── 10. SPOC directory ────────────────────────────────────────────────────────

def build_spoc_directory():
    doc = NoticeDoc(
        filename=OUT / "svc_19_spoc_directory.pdf",
        notice_no=NOTICE_LOG["spoc_directory"],
        date=_fmt_date("2026-08-01"),
        subject="Consolidated SPOC Directory: Who to Contact for What",
        to="All Students",
    )
    doc.para(
        "The two tables below together give every field needed to reach the right person, "
        "keyed by task. A single 8-column table put Designation/Department/Email so narrow "
        "that reportlab had to break words mid-token to fit (the same failure mode fixed in "
        "the scholarship eligibility matrix above) — splitting into two task-keyed tables "
        "gives every column enough width to wrap only on whole words or at the '@' in an "
        "email address, at the cost of repeating the Task column once."
    )
    doc.para("<b>Person and designation</b>")
    doc.table(
        ["Task", "Person", "Designation", "Email", "Phone"],
        [
            [task, PEOPLE[key]["name"], PEOPLE[key]["designation"], PEOPLE[key]["email"],
             PEOPLE[key]["phone"]]
            for task, key in SPOC_DIRECTORY
        ],
        col_widths=weighted_col_widths([2.2, 1.7, 2.1, 2.5, 1.3]),
    )
    doc.para("<b>Department, office and availability</b>")
    doc.table(
        ["Task", "Department", "Office", "Availability"],
        [
            [task, dept_by_code(PEOPLE[key]["department"])[0] if PEOPLE[key]["department"] else "-",
             PEOPLE[key]["office"], PEOPLE[key]["hours"]]
            for task, key in SPOC_DIRECTORY
        ],
        col_widths=weighted_col_widths([2.0, 2.4, 2.2, 3.0]),
    )
    doc.signature("registrar")
    return doc


# ── registry ──────────────────────────────────────────────────────────────────
# Filenames are fixed here (not derived from dict-iteration order) so a re-run always
# produces the same set of files, and are prefixed svc_ (distinct from render_academic's
# numeric prefixes) so the two agents' output never collides on a filename.
_SCHEME_PROC_SEQ = {
    "Post-Matric Scholarship for SC/ST Students": 2,
    "Rajarshi Shahu Maharaj Merit Scholarship": 3,
    "Rajarshi Shahu Maharaj Freeship for EBC": 4,
    "KRIET Alumni Merit Grant": 5,
    "Sports and Cultural Excellence Scholarship": 6,
}
_DRIVE_SEQ = {
    "Ratnagiri Softworks": 9,
    "Konkangiri Analytics": 10,
    "Malvan Robotics Pvt Ltd": 11,
    "Vishalgad Power Systems": 12,
    "Devbaug Structall Engineers": 13,
    "Sindhu Cloud Systems": 14,
}


DOCS = {"svc_01_scholarship_eligibility_matrix.pdf": build_scholarship_eligibility_matrix}
for _name, _seq in _SCHEME_PROC_SEQ.items():
    _slug = _name.lower().replace(" ", "_").replace("/", "_")
    DOCS[f"svc_{_seq:02d}_scholarship_procedure_{_slug}.pdf"] = (
        lambda n=_name, s=_seq: build_scheme_procedure(n, s)
    )
DOCS["svc_07_sports_cultural_benefits.pdf"] = build_sports_cultural_benefits
DOCS["svc_08_placement_policy.pdf"] = build_placement_policy
for _company, _seq in _DRIVE_SEQ.items():
    _slug = _company.lower().replace(" ", "_").replace(".", "")
    DOCS[f"svc_{_seq:02d}_placement_drive_{_slug}.pdf"] = (
        lambda c=_company, s=_seq: build_placement_drive(c, s)
    )
DOCS["svc_15_training_programmes.pdf"] = build_training_programmes
DOCS["svc_16_incubation_centre.pdf"] = build_incubation_centre
DOCS["svc_17_library_services_and_rules.pdf"] = build_library_services
DOCS["svc_18_grievance_and_anti_ragging.pdf"] = build_grievance_and_antiragging
DOCS["svc_19_spoc_directory.pdf"] = build_spoc_directory
