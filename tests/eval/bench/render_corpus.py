"""Render the bench corpus from world.py.

Each document is written the way an institute actually writes it — a handbook does not
repeat the annual report's placement table, and the vendor schedule does not name the
professor who runs the lab it equipped. That separation is what makes a multi-hop question
multi-hop, so it is preserved deliberately rather than flattened for convenience.

Output: Dataset/bench_v1/corpus/*.md
"""
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval.bench.world import (  # noqa: E402
    ACADEMIC_RULES, CALENDAR, COMMITTEES, DEPARTMENTS, DEPT_DETAIL, ELECTIVES, FACULTY,
    FEES, HOSTELS, INSTITUTE, LABS, LIBRARY, PLACEMENT, PROJECTS, SCHOLARSHIPS, VENDORS,
    dept_by_code, rupees,
)

OUT = PROJECT_ROOT / "Dataset" / "bench_v1" / "corpus"
R = ACADEMIC_RULES


def doc_institute():
    i = INSTITUTE
    return f"""# About the Institute

{i['name']} ({i['short']}) was established in {i['established']} at {i['city']},
district {i['district']}. The campus occupies {i['campus_acres']} acres. The Institute is
affiliated to {i['affiliation']} and offers eight undergraduate engineering programmes.

The Director is {i['director']}. The Registrar is {i['registrar']}, who heads all
non-academic administration including the estate, accounts and examination sections.

## Departments

The Institute operates {len(DEPARTMENTS)} academic departments. The oldest are Mechanical
Engineering and Civil Engineering, both established with the Institute in
{i['established']}. The newest is Artificial Intelligence and Data Science, established
in 2021.
"""


def doc_faculty_handbook():
    rows = "\n".join(
        f"- The Department of {name} is headed by {hod}. Sanctioned faculty strength "
        f"{faculty}. The department was established in {est} and admits {intake} students "
        f"per year."
        for name, _c, est, hod, faculty, intake, *_ in DEPARTMENTS)
    return f"""# Faculty Handbook

## Departmental leadership

{rows}

## Appointment

All Heads of Department are appointed by the Director for a term of three years. A Head of
Department reports to the Director on academic matters and to the Registrar on
administrative matters.

Note that two members of faculty share the surname Rane: Dr. Vasant Rane heads Mechanical
Engineering, while Dr. Vaishali Rane heads Chemical Engineering.
"""


def doc_annual_report():
    header = ("| Department | Intake | Pass % | Placement % | Average package (LPA) | "
              "Highest package (LPA) |\n|---|---|---|---|---|---|")
    rows = "\n".join(
        f"| {name} | {intake} | {p} | {pl} | {avg} | {high} |"
        for name, _c, _e, _h, _f, intake, p, pl, avg, high in DEPARTMENTS)
    return f"""# Annual Report Extract 2024-25

## Departmental performance

{header}
{rows}

Institute-wide, {PLACEMENT['companies_2024_25']} companies visited the campus and
{PLACEMENT['total_offers']} offers were made. The median package was
{PLACEMENT['median_lpa']} LPA.
"""


def doc_infrastructure():
    rows = "\n".join(
        f"- The {lab} belongs to the Department of {dept_by_code(code)[0]}. It was "
        f"commissioned in {year} and its equipment was supplied by {vendor}. The facility "
        f"was funded by {agency} with a grant of Rs. {rupees(grant)}. Custodian: {cust}."
        for lab, code, year, vendor, agency, grant, cust in LABS)
    return f"""# Infrastructure Register

## Laboratories

{rows}

## Custodianship

A laboratory custodian is answerable for the equipment register and for the annual
stock verification. The custodian is not necessarily the Head of the owning department.
"""


def doc_vendors():
    rows = "\n".join(
        f"| {name} | {service} | {rupees(val)} | {start} | {end} |"
        for name, service, val, start, end, _r in VENDORS)
    extra = "\n".join(
        f"- Beyond its contract, {name} is also a {role}."
        for name, _s, _v, _st, _e, role in VENDORS if role)
    return f"""# Vendor Contract Schedule

## Recurring contracts

| Vendor | Service | Annual value (Rs.) | From | To |
|---|---|---|---|---|
{rows}

## Relationships beyond supply

{extra}

All recurring contracts are reviewed by the Registrar before renewal.
"""


def doc_fee_structure():
    rows = "\n".join(
        f"| {cat} | {rupees(t)} | {rupees(d)} | {rupees(e)} | {rupees(lib)} |"
        for cat, t, d, e, lib in FEES)
    return f"""# Fee Structure 2024-25

| Category | Tuition (Rs.) | Development fee (Rs.) | Examination fee (Rs.) | Library deposit (Rs.) |
|---|---|---|---|---|
{rows}

The library deposit is refundable on completion of the programme. The development fee is
charged once per academic year. Hostel and mess charges are billed separately and are
listed in the Hostel Rules.

Fees must be paid by {CALENDAR['fee_deadline']}. A late payment attracts a fine of
Rs. {rupees(CALENDAR['late_fee'])}.
"""


def doc_hostel_rules():
    rows = "\n".join(
        f"- {block} accommodates {gender} students, capacity {cap} beds, {rooms} rooms per "
        f"floor. Warden: {warden}. Annual hostel charge Rs. {rupees(fee)}."
        for block, gender, cap, warden, fee, rooms in HOSTELS)
    total = sum(h[2] for h in HOSTELS)
    return f"""# Hostel Rules

## Blocks and wardens

{rows}

Total sanctioned hostel capacity is {total} beds. Housekeeping and maintenance across all
blocks is contracted out; the contract is listed in the Vendor Contract Schedule.

## Discipline

Residents must be inside their block by 22:30. A warden may recommend removal from the
hostel for a repeated breach; the final decision rests with the Registrar.
"""


def doc_academic_regulations():
    return f"""# Academic Regulations

- A B.Tech degree requires {R['btech_credits']} credits.
- A student may register for at most {R['max_credits_per_semester']} credits and must
  register for at least {R['min_credits_per_semester']} credits in a semester.
- The final-year project carries {R['final_project_credits']} credits. A laboratory course
  carries {R['lab_course_credits']} credit.
- {R['failing_grade']} is the only failing grade.
- A minimum CGPA of {R['min_cgpa_award']} is required for the award of the degree.
- A student carrying more than {R['max_backlogs_to_promote']} backlogs is not promoted to
  the next academic year.
- The maximum permissible duration for completing the programme is
  {R['max_duration_years']} years from admission.

## Attendance

A student must maintain at least {R['min_attendance_pct']}% attendance, raised to
{R['min_attendance_final_year_pct']}% in the final year. Attendance below
{R['debarment_attendance_pct']}% results in debarment from the end-semester examination.

## Revaluation

An application for revaluation must be submitted within {R['revaluation_window_days']} days
of the result declaration, with a fee of Rs. {rupees(R['revaluation_fee'])} per subject.
"""


def doc_placement_policy():
    return f"""# Placement Policy

To register with the Training and Placement Cell a student must have a CGPA of at least
{PLACEMENT['eligibility_cgpa']} and {PLACEMENT['eligibility_backlogs']} live backlogs.

Every student must complete an industrial internship of {PLACEMENT['internship_weeks']}
weeks before the final semester.

A student who accepts an offer is withdrawn from further campus processes. The highest
package recorded in 2024-25 was {PLACEMENT['highest_lpa']} LPA.

Departmental placement percentages are published in the Annual Report Extract.
"""


def doc_library():
    lb = LIBRARY
    return f"""# Library Rules

The library holds {lb['titles']} titles and subscribes to {lb['journals']} journals and
{lb['e_resources']} e-resource platforms. Seating capacity is {lb['seats']}.

Opening hours are {lb['weekday_hours']} on weekdays and {lb['weekend_hours']} at weekends.

A student may borrow {lb['loan_limit']} books at a time for {lb['loan_days']} days. An
overdue book attracts a fine of Rs. {lb['fine_per_day']} per day. Reference section volumes
are not issued outside the library.
"""


def doc_scholarships():
    rows = "\n".join(
        f"- The {name} is awarded to students with {elig}. It is worth Rs. {rupees(amt)} "
        f"per year and was awarded to {n} students in 2024-25."
        for name, elig, amt, n in SCHOLARSHIPS)
    return f"""# Scholarship Policy

{rows}

A student may hold only one Institute scholarship at a time. Government scholarships are
administered separately by the accounts section under the Registrar.
"""


def doc_governance():
    rows = "\n".join(
        f"- The {name} is chaired by {chair}. It has {n} members and meets {m} times a year."
        for name, chair, n, m in COMMITTEES)
    return f"""# Governance and Committees

{rows}

Committee chairs are nominated by the Director. Minutes are circulated to the Registrar
within seven working days of a meeting.
"""


def doc_research():
    rows = "\n".join(
        f"- \"{title}\" is led by {pi} and funded by {agency} with a sanctioned amount of "
        f"Rs. {rupees(amt)}. The project began in {start} and runs for {yrs} years."
        for title, pi, agency, amt, start, yrs in PROJECTS)
    return f"""# Sponsored Research Register

{rows}

Overheads of 10% of the sanctioned amount are retained by the Institute. The Research
Advisory Board reviews progress annually.
"""


def doc_calendar():
    c = CALENDAR
    return f"""# Academic Calendar 2024-25

- The odd semester runs from {c['odd_semester_start']} to {c['odd_semester_end']}.
- The even semester runs from {c['even_semester_start']} to {c['even_semester_end']}.
- Convocation is held on {c['convocation']}.
- Foundation Day is {c['foundation_day']}.

Examination schedules are notified by the examination section under the Registrar.
"""


def doc_grievance():
    return """# Grievance and Conduct Policy

A student grievance is first raised with the Head of Department. If unresolved within ten
working days it is escalated to the Grievance Redressal Cell, and thereafter to the
Director.

Complaints of harassment are handled exclusively by the Internal Complaints Committee and
are not routed through the Head of Department.

Personal data of students and staff is processed in accordance with the Digital Personal
Data Protection Act, 2023. Academic records are retained for ten years after graduation;
disciplinary records are retained for two years.
"""


def doc_it_policy():
    return """# IT Acceptable Use Policy

Campus network accounts are issued to enrolled students and to staff on appointment. An
account is deactivated thirty days after a student graduates.

Network and security appliances are maintained under an annual maintenance contract listed
in the Vendor Contract Schedule. Incident logs are retained for ninety days.

Use of the campus network to run a commercial service is prohibited. A breach is referred
to the Grievance Redressal Cell.
"""


def doc_department_profile(code):
    """One profile per department, all with the SAME shape and vocabulary.

    This is where retrieval precision is actually tested: eight documents that read alike
    and differ only in names, numbers and specialisations. Landing in "a department
    profile" is not the same as landing in the right one, and on a small corpus that
    distinction never gets exercised, because top-k returns most of everything.
    """
    name, _c, est, hod, faculty_n, intake, pass_pct, place_pct, avg, high = dept_by_code(code)
    staff = FACULTY[code]
    electives = ELECTIVES[code]
    accred_from, accred_to, phds, assignments, tier = DEPT_DETAIL[code]
    # NOTE: a department profile deliberately does NOT list its laboratories. The
    # infrastructure register is the single place mapping a laboratory to a department,
    # which is what keeps "who heads the department that runs lab X" a two-document
    # question rather than a lookup in one — validate_bench.py enforces this.
    return f"""# Department Profile: {name}

## Overview

The Department of {name} was established in {est} and is headed by {hod}. It admits
{intake} students each year against a sanctioned faculty strength of {faculty_n}.
Accreditation status is {tier}, valid from {accred_from} to {accred_to}.

## Faculty

The department lists {len(staff)} named faculty in the current handbook: {', '.join(staff)}.
{phds} members hold a doctorate awarded in the last five years.

## Electives

The department offers the following departmental electives: {', '.join(electives)}.
Each elective carries 3 credits and is offered in the seventh semester subject to a
minimum enrolment of 20 students.

## Outcomes

In 2024-25 the department recorded a pass percentage of {pass_pct} and placed
{place_pct}% of its eligible students. The average package was {avg} LPA and the highest
{high} LPA. The department completed {assignments} sponsored or consultancy assignments in
the same period.
"""


def doc_examination_procedure():
    return f"""# Examination Procedure

The examination section operates under the Registrar, {INSTITUTE['registrar']}.

## Hall tickets

A hall ticket is issued only after fees are cleared and attendance is certified at or above
{ACADEMIC_RULES['min_attendance_pct']}%. A student debarred under the
{ACADEMIC_RULES['debarment_attendance_pct']}% rule is not issued a hall ticket.

## Conduct

Examinations are conducted in two sessions. A candidate reporting more than thirty minutes
after the start is not admitted. Use of a programmable calculator requires prior written
permission from the Head of Department.

## Unfair means

A first instance of unfair means voids the paper concerned. A second instance voids the
entire examination for that semester. Cases are heard by a committee constituted by the
Director and the decision is communicated within twenty-one days.

## Results and revaluation

Results are declared within thirty days of the last paper. Revaluation is applied for
within {ACADEMIC_RULES['revaluation_window_days']} days at Rs.
{rupees(ACADEMIC_RULES['revaluation_fee'])} per subject; the revised mark stands whether it
is higher or lower.
"""


def doc_admission_procedure():
    return f"""# Admission Procedure

Admission to the first year is through the state Common Entrance Test. Fifteen per cent of
seats in each programme are filled through the all-India quota.

Direct second-year admission against lateral entry is available to diploma holders, capped
at ten per cent of the sanctioned intake of {sum(d[5] for d in DEPARTMENTS)} across all
departments.

Documents required at reporting are the allotment letter, the CET scorecard, the qualifying
marksheet, a transfer certificate and a category certificate where a concession is claimed.
Original documents are returned within seven working days of verification.

An admission is provisional until fees are paid by {CALENDAR['fee_deadline']}.
"""


def doc_transport():
    return """# Transport Policy

The Institute contracts student transport on six routes serving Karad, Umbraj, Masur,
Ogalewadi, Malkapur and Vita. The contract is listed in the Vendor Contract Schedule.

A transport pass is issued per semester and is not transferable. The pass fee is Rs. 9,600
per year for routes up to twenty kilometres and Rs. 12,400 beyond that.

Buses depart the campus at 17:15 on working days. A student staying beyond that hour for
laboratory work must obtain a late pass from the department office.
"""


def doc_sports():
    return """# Sports and Cultural Policy

The Institute maintains a 400-metre track, two basketball courts, a cricket ground and an
indoor hall used for badminton and table tennis.

A student representing the Institute at an inter-collegiate event is granted attendance
relief of up to ten per cent, subject to certification by the Physical Director. Relief
under this clause cannot take a student below the debarment threshold.

The annual cultural festival is held in the even semester and is funded from the student
activity fee. Participation in more than two committees simultaneously is not permitted.
"""


def doc_alumni():
    return """# Alumni Relations

The alumni association was registered in 1996 and has 11,400 registered members. It funds
two prizes each year: the Best Outgoing Student award of Rs. 25,000 and the Best Project
award of Rs. 15,000.

Alumni are invited to the convocation and to the departmental industry interaction held in
the odd semester. An alumnus wishing to offer an internship applies through the Training and
Placement Cell rather than directly to a department.
"""


def doc_internship():
    return f"""# Internship Guidelines

The compulsory industrial internship runs for {PLACEMENT['internship_weeks']} weeks in the
vacation preceding the final year. It carries 2 credits and is graded pass or fail.

A student secures an internship through the Training and Placement Cell or independently
with prior approval from the Head of Department. Approval requires a letter from the host
organisation naming a supervisor.

The deliverables are a daily log, a certificate from the host, and a seminar presented
within three weeks of the semester starting. A student who fails the internship repeats it
in the following vacation and cannot graduate until it is cleared.
"""


DOCS = {
    "01_about_institute.md": doc_institute,
    "02_faculty_handbook.md": doc_faculty_handbook,
    "03_annual_report_extract.md": doc_annual_report,
    "04_infrastructure_register.md": doc_infrastructure,
    "05_vendor_contracts.md": doc_vendors,
    "06_fee_structure.md": doc_fee_structure,
    "07_hostel_rules.md": doc_hostel_rules,
    "08_academic_regulations.md": doc_academic_regulations,
    "09_placement_policy.md": doc_placement_policy,
    "10_library_rules.md": doc_library,
    "11_scholarship_policy.md": doc_scholarships,
    "12_governance_committees.md": doc_governance,
    "13_sponsored_research.md": doc_research,
    "14_academic_calendar.md": doc_calendar,
    "15_grievance_policy.md": doc_grievance,
    "16_it_acceptable_use.md": doc_it_policy,
    "17_examination_procedure.md": doc_examination_procedure,
    "18_admission_procedure.md": doc_admission_procedure,
    "19_transport_policy.md": doc_transport,
    "20_sports_cultural.md": doc_sports,
    "21_alumni_relations.md": doc_alumni,
    "22_internship_guidelines.md": doc_internship,
}

# One profile per department, registered programmatically so the document set cannot
# drift out of step with the world model.
for _code in [d[1] for d in DEPARTMENTS]:
    DOCS[f"30_dept_{_code.lower()}_profile.md"] = (
        lambda c=_code: doc_department_profile(c))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, fn in DOCS.items():
        text = fn()
        (OUT / name).write_text(text, encoding="utf-8")
        total += len(text)
    print(f"wrote {len(DOCS)} documents ({total} chars) -> {OUT}")


if __name__ == "__main__":
    main()
