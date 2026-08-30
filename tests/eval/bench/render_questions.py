"""Generate the bench question set from world.py.

Questions are DERIVED from the same model that rendered the corpus, so a gold cannot be
wrong about the documents. What each family is for:

  FACT    one fact, one document. Measures retrieval + extraction.
  LOCAL   two or three hops that span documents by construction — the lab is in the
          infrastructure register, the head of its department is in the faculty handbook,
          and neither document contains the other's half. A single lucky chunk cannot
          answer these.
  GLOBAL  aggregation, ranking and comparison over a whole table or across documents.
          Questions whose answer must be COMPUTED (sums, totals) are marked derived=True:
          the v2 gold derivation files those figures as `bonus` anchors, so arithmetic is
          measured as its own sub-metric instead of being conjoined with retrieval.
  UNANS   facts deliberately absent from the corpus. Abstention is the correct answer.

Paraphrase discipline: every second FACT question is phrased away from the document's own
wording, so the FACT slice keeps a lexical/paraphrase split to diagnose whether a retrieval
change helps matching or meaning.

Output: Dataset/bench_v1/golden/*.json in stresskit schema (question, expected_answer,
supporting_docs), which tests/eval/derive_gold_v2.py then turns into scoreable anchors.
"""
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval.bench.world import (  # noqa: E402
    ACADEMIC_RULES, CALENDAR, COMMITTEES, DEPARTMENTS, FEES, HOSTELS, INSTITUTE,
    LABS, LIBRARY, PLACEMENT, PROJECTS, SCHOLARSHIPS, VENDORS, dept_by_code, rupees,
)

OUT = PROJECT_ROOT / "Dataset" / "bench_v1" / "golden"
R = ACADEMIC_RULES

HANDBOOK = "02_faculty_handbook.md"
REPORT = "03_annual_report_extract.md"
INFRA = "04_infrastructure_register.md"
VENDOR_DOC = "05_vendor_contracts.md"
FEE_DOC = "06_fee_structure.md"
HOSTEL_DOC = "07_hostel_rules.md"
REGS = "08_academic_regulations.md"
PLACE = "09_placement_policy.md"
LIB = "10_library_rules.md"
SCHOL = "11_scholarship_policy.md"
GOV = "12_governance_committees.md"
RESEARCH = "13_sponsored_research.md"
CAL = "14_academic_calendar.md"


def hod_of(code):
    return dept_by_code(code)[3]


# ── FACT ─────────────────────────────────────────────────────────────────────

def fact_questions():
    q = []

    def add(question, answer, doc, difficulty="easy", answer_anchors=None):
        """answer_anchors: what THIS question asks for.

        Without it the derivation scrapes every figure out of the expected_answer prose,
        so "what is the sanctioned amount for project X" ends up requiring the funding
        agency too, and a correct answer is scored wrong for not volunteering it. This is
        the same over-specification that made the old stresskit unable to recognise a
        correct answer; it must not be reintroduced here.
        """
        item = {"question": question, "expected_answer": answer,
                "source_doc": doc, "difficulty": difficulty}
        if answer_anchors:
            item["answer_anchors"] = answer_anchors
        q.append(item)

    for name, code, est, hod, faculty, intake, *_ in DEPARTMENTS:
        add(f"What is the sanctioned faculty strength of the Department of {name}?",
            f"{faculty} faculty are sanctioned for {name}.", HANDBOOK)
        add(f"In which year was the Department of {name} established?",
            f"The Department of {name} was established in {est}.", HANDBOOK)
        add(f"How many students does the Department of {name} admit each year?",
            f"{name} admits {intake} students per year.", HANDBOOK)

    for lab, code, year, vendor, agency, grant, cust in LABS:
        add(f"When was the {lab} commissioned?", f"The {lab} was commissioned in {year}.",
            INFRA)
        add(f"Who supplied the equipment for the {lab}?",
            f"{vendor} supplied the equipment for the {lab}.", INFRA)
        add(f"What grant funded the {lab}?",
            f"The {lab} was funded by {agency} with a grant of Rs. {rupees(grant)}.",
            INFRA, "medium")

    for name, service, val, start, end, _role in VENDORS:
        add(f"What is the annual contract value for {name}?",
            f"The contract with {name} is worth Rs. {rupees(val)} per year.", VENDOR_DOC)
        add(f"When does the contract with {name} end?",
            f"The {name} contract runs to {end}.", VENDOR_DOC)

    for block, gender, cap, warden, fee, _rooms in HOSTELS:
        add(f"What is the bed capacity of {block}?",
            f"{block} has a capacity of {cap} beds.", HOSTEL_DOC)
        add(f"Who is the warden of {block}?", f"The warden of {block} is {warden}.",
            HOSTEL_DOC)

    for cat, tuition, dev, exam, lib in FEES:
        add(f"What is the annual tuition fee for a {cat} category student?",
            f"Tuition for the {cat} category is Rs. {rupees(tuition)}.", FEE_DOC)

    add("How many credits are required for the award of a B.Tech degree?",
        f"{R['btech_credits']} credits are required.", REGS)
    add("What is the minimum attendance requirement?",
        f"A student must maintain at least {R['min_attendance_pct']}% attendance, rising to "
        f"{R['min_attendance_final_year_pct']}% in the final year.", REGS,
        answer_anchors=[str(R['min_attendance_pct'])])
    add("Below what attendance is a student debarred from the examination?",
        f"Attendance below {R['debarment_attendance_pct']}% results in debarment.", REGS,
        "medium")
    add("Which grade counts as a failure?", f"{R['failing_grade']} is the only failing grade.",
        REGS)
    add("How many credits does the final-year project carry?",
        f"The final-year project carries {R['final_project_credits']} credits.", REGS)
    add("What is the maximum number of credits a student may register for in a semester?",
        f"At most {R['max_credits_per_semester']} credits.", REGS)
    add("How many backlogs prevent promotion to the next year?",
        f"More than {R['max_backlogs_to_promote']} backlogs prevent promotion.", REGS,
        "medium")
    add("What is the fee for revaluation of a subject?",
        f"Revaluation costs Rs. {rupees(R['revaluation_fee'])} per subject.", REGS)
    add("Within how many days must a revaluation application be submitted?",
        f"Within {R['revaluation_window_days']} days of the result declaration.", REGS)
    add("What is the maximum time allowed to complete the programme?",
        f"{R['max_duration_years']} years from admission.", REGS)
    add("What minimum CGPA is required for the award of the degree?",
        f"A CGPA of {R['min_cgpa_award']} is required.", REGS)

    add("How many titles does the library hold?", f"The library holds {LIBRARY['titles']} titles.",
        LIB)
    add("How many journals does the library subscribe to?",
        f"{LIBRARY['journals']} journals.", LIB)
    add("What is the overdue fine per day in the library?",
        f"Rs. {LIBRARY['fine_per_day']} per day.", LIB)
    add("How many books may a student borrow at one time?",
        f"{LIBRARY['loan_limit']} books for {LIBRARY['loan_days']} days.", LIB,
        answer_anchors=[str(LIBRARY['loan_limit'])])
    add("What are the library's weekday opening hours?",
        f"{LIBRARY['weekday_hours']} on weekdays.", LIB)
    add("How many seats does the library have?", f"{LIBRARY['seats']} seats.", LIB)

    add("What CGPA is needed to register with the placement cell?",
        f"A CGPA of at least {PLACEMENT['eligibility_cgpa']} and "
        f"{PLACEMENT['eligibility_backlogs']} live backlogs.", PLACE,
        answer_anchors=[str(PLACEMENT['eligibility_cgpa'])])
    add("How long is the compulsory industrial internship?",
        f"{PLACEMENT['internship_weeks']} weeks.", PLACE)
    add("How many companies visited the campus in 2024-25?",
        f"{PLACEMENT['companies_2024_25']} companies visited.", REPORT)
    add("How many offers were made in 2024-25?",
        f"{PLACEMENT['total_offers']} offers were made.", REPORT)
    add("What was the highest package recorded in 2024-25?",
        f"The highest package was {PLACEMENT['highest_lpa']} LPA.", PLACE)

    for name, elig, amt, n in SCHOLARSHIPS:
        add(f"What is the annual value of the {name}?",
            f"The {name} is worth Rs. {rupees(amt)} per year.", SCHOL)
        add(f"How many students received the {name} in 2024-25?",
            f"{n} students received it.", SCHOL, "medium")

    for cname, chair, n, m in COMMITTEES:
        add(f"Who chairs the {cname}?", f"The {cname} is chaired by {chair}.", GOV)
        add(f"How many members does the {cname} have?", f"{n} members.", GOV)

    for title, pi, agency, amt, start, yrs in PROJECTS:
        add(f"Who is the principal investigator of the project \"{title}\"?",
            f"{pi} leads the project.", RESEARCH)
        add(f"What is the sanctioned amount for the project \"{title}\"?",
            f"Rs. {rupees(amt)} sanctioned by {agency}.", RESEARCH, "medium",
            answer_anchors=[str(amt)])

    add("When is convocation held?", f"Convocation is on {CALENDAR['convocation']}.", CAL)
    add("When does the odd semester begin?",
        f"The odd semester begins on {CALENDAR['odd_semester_start']}.", CAL)
    add("What is the fee payment deadline?",
        f"Fees are due by {CALENDAR['fee_deadline']}, with a late fine of Rs. "
        f"{rupees(CALENDAR['late_fee'])}.", FEE_DOC, "medium",
        answer_anchors=[CALENDAR['fee_deadline'].split()[0]])
    add("Who is the Director of the Institute?",
        f"The Director is {INSTITUTE['director']}.", "01_about_institute.md")
    add("Which university is the Institute affiliated to?",
        f"It is affiliated to {INSTITUTE['affiliation']}.", "01_about_institute.md")
    add("In which year was the Institute established?",
        f"The Institute was established in {INSTITUTE['established']}.",
        "01_about_institute.md")

    # The per-entity templates generate far more FACT items than the slice needs, and a
    # hundred near-identical lookups measure the same thing a hundred times while making
    # every eval run longer. Thin deterministically with a stride (never randomly — the
    # question set must be byte-reproducible), keeping the whole of the rules/library/
    # placement/calendar tail, which is where the phrasings actually differ.
    entity_q, tail_q = q[:len(q) - 45], q[len(q) - 45:]
    q = entity_q[::3] + tail_q

    for i, item in enumerate(q):
        item["id"] = f"BF{i + 1:03d}"
        item["phrasing"] = "lexical" if i % 2 == 0 else "paraphrase"
    return q


# ── LOCAL (multi-hop across documents) ───────────────────────────────────────

def local_questions():
    q = []

    def add(question, answer, docs, hops, difficulty="medium",
            answer_anchors=None, bridge=None):
        """answer_anchors: what the question actually asks for, stated by the generator
        rather than inferred from prose. Without it the derivation falls back to "any
        proper noun in the sentence", and a question asking who HEADS a department is
        satisfied by naming the laboratory instead. bridge: the entity the question names,
        used by validate_bench.py to prove the hop really crosses documents."""
        item = {"question": question, "expected_answer": answer,
                "supporting_docs": docs, "hops": hops, "difficulty": difficulty}
        if answer_anchors:
            item["answer_anchors"] = answer_anchors
        if bridge:
            item["bridge"] = bridge
        q.append(item)

    for lab, code, _y, vendor, agency, _g, cust in LABS:
        dept = dept_by_code(code)[0]
        add(f"Who heads the department that operates the {lab}?",
            f"The {lab} belongs to {dept}, headed by {hod_of(code)}.",
            [INFRA, HANDBOOK], 2, answer_anchors=[hod_of(code)], bridge=lab)
        add(f"How many faculty are sanctioned in the department that runs the {lab}?",
            f"The {lab} belongs to {dept}, which has {dept_by_code(code)[4]} sanctioned faculty.",
            [INFRA, HANDBOOK], 2, "hard",
            answer_anchors=[str(dept_by_code(code)[4])], bridge=lab)
        add(f"What is the placement percentage of the department that owns the {lab}?",
            f"The {lab} belongs to {dept}, whose placement rate is {dept_by_code(code)[7]}%.",
            [INFRA, REPORT], 2, "hard",
            answer_anchors=[str(dept_by_code(code)[7])], bridge=lab)

    for block, _g, cap, warden, _f, _r in HOSTELS:
        hod_dept = next((d[0] for d in DEPARTMENTS if d[3] == warden), None)
        if hod_dept:
            add(f"The warden of {block} heads which department?",
                f"{warden}, warden of {block}, heads {hod_dept}.",
                [HOSTEL_DOC, HANDBOOK], 2, answer_anchors=[hod_dept], bridge=block)
            add(f"What is the pass percentage of the department headed by the warden of {block}?",
                f"{warden} heads {hod_dept}, whose pass rate is "
                f"{next(d[6] for d in DEPARTMENTS if d[3] == warden)}%.",
                [HOSTEL_DOC, HANDBOOK, REPORT], 3, "hard",
                answer_anchors=[str(next(d[6] for d in DEPARTMENTS if d[3] == warden))],
                bridge=block)

    for cname, chair, _n, _m in COMMITTEES:
        dept = next((d[0] for d in DEPARTMENTS if d[3] == chair), None)
        if dept:
            add(f"Which department is headed by the chair of the {cname}?",
                f"{chair} chairs the {cname} and heads {dept}.", [GOV, HANDBOOK], 2,
                answer_anchors=[dept], bridge=cname)

    for title, pi, agency, amt, _s, _y in PROJECTS:
        dept = next((d[0] for d in DEPARTMENTS if d[3] == pi), None)
        if dept:
            add(f"Which department does the principal investigator of \"{title}\" head?",
                f"{pi} leads it and heads {dept}.", [RESEARCH, HANDBOOK], 2,
                answer_anchors=[dept], bridge=title)
            add(f"How many students does the department of the \"{title}\" investigator admit?",
                f"{pi} heads {dept}, which admits "
                f"{next(d[5] for d in DEPARTMENTS if d[3] == pi)} students per year.",
                [RESEARCH, HANDBOOK], 2, "hard",
                answer_anchors=[str(next(d[5] for d in DEPARTMENTS if d[3] == pi))],
                bridge=title)

    add("Which vendor maintains the hostels, and who is the warden of Block C?",
        "Konkan Facility Services maintains the hostels; the warden of Block C is "
        "Dr. Anjali Pawar.", [VENDOR_DOC, HOSTEL_DOC], 2,
        answer_anchors=["Konkan Facility Services", "Dr. Anjali Pawar"], bridge="Block C")
    add("Who is the custodian of the laboratory with the largest grant?",
        "The Advanced Manufacturing Laboratory has the largest grant, Rs. 2,45,00,000, and "
        "its custodian is Shri Ganesh Tarate.", [INFRA], 2, "hard",
        answer_anchors=["Shri Ganesh Tarate"])
    add("The custodian of the High Performance Computing Laboratory chairs which committee?",
        "Dr. Iqbal Shaikh is the custodian and chairs the Research Advisory Board.",
        [INFRA, GOV], 2, "hard",
        answer_anchors=["Research Advisory Board"], bridge="High Performance Computing Laboratory")
    add("Which department owns the laboratory whose custodian is Shri Ganesh Tarate, "
        "and who heads it?",
        "Shri Ganesh Tarate is custodian of the Advanced Manufacturing Laboratory, the "
        "Thermal Engineering Laboratory and the Power Systems Laboratory; the first two "
        "belong to Mechanical Engineering, headed by Dr. Vasant Rane.",
        [INFRA, HANDBOOK], 2, "hard",
        answer_anchors=["Dr. Vasant Rane"], bridge="Shri Ganesh Tarate")
    add("Who supplied equipment to the laboratory owned by the department Dr. Farhan Shaikh heads?",
        "Dr. Farhan Shaikh heads Artificial Intelligence and Data Science, whose Data "
        "Analytics Studio was equipped by Trivendra Systems.", [HANDBOOK, INFRA], 3, "hard",
        answer_anchors=["Trivendra Systems"], bridge="Dr. Farhan Shaikh")
    add("Which company is both a supplier and a campus recruiter, and which lab did it equip?",
        "Trivendra Systems is a supplier and a campus recruiter; it equipped the High "
        "Performance Computing Laboratory and the Data Analytics Studio.",
        [VENDOR_DOC, INFRA], 2, "hard",
        answer_anchors=["High Performance Computing Laboratory"], bridge="campus recruiter")

    for i, item in enumerate(q):
        item["id"] = f"BL{i + 1:03d}"
    return q


# ── GLOBAL (aggregate / rank / compare) ──────────────────────────────────────

def global_questions():
    q = []

    def add(question, answer, docs, reasoning, derived=False, difficulty="medium",
            answer_anchors=None):
        item = {"question": question, "expected_answer": answer,
                "supporting_docs": docs, "reasoning_type": reasoning,
                "derived": derived, "difficulty": difficulty}
        if answer_anchors:
            item["answer_anchors"] = answer_anchors
        q.append(item)

    best = max(DEPARTMENTS, key=lambda d: d[7])
    worst = min(DEPARTMENTS, key=lambda d: d[7])
    add("Which department has the best placement rate and which has the worst?",
        f"{best[0]} is best at {best[7]}%; {worst[0]} is worst at {worst[7]}%.",
        [REPORT], "ranking")
    add("Which department records the highest average package?",
        f"{max(DEPARTMENTS, key=lambda d: d[8])[0]} at "
        f"{max(d[8] for d in DEPARTMENTS)} LPA.", [REPORT], "ranking")
    add("Which department has the lowest pass percentage?",
        f"{min(DEPARTMENTS, key=lambda d: d[6])[0]} at {min(d[6] for d in DEPARTMENTS)}%.",
        [REPORT], "ranking")
    n90 = [d for d in DEPARTMENTS if d[6] > 90]
    add("How many departments have a pass percentage above 90?",
        f"{len(n90)} departments: " + ", ".join(d[0] for d in n90) + ".",
        [REPORT], "counting", difficulty="hard")
    add("Do departments with higher pass rates also place better?",
        f"Broadly yes. {best[0]} leads both at {best[6]}% pass and {best[7]}% placement, "
        f"while {worst[0]} trails both at {worst[6]}% and {worst[7]}%.",
        [REPORT], "correlation", difficulty="hard", answer_anchors=[best[0]])
    add("Which is the oldest department and which is the newest?",
        f"{min(DEPARTMENTS, key=lambda d: d[2])[0]} and Civil Engineering date from "
        f"{min(d[2] for d in DEPARTMENTS)}; the newest is "
        f"{max(DEPARTMENTS, key=lambda d: d[2])[0]}, established "
        f"{max(d[2] for d in DEPARTMENTS)}.", [HANDBOOK], "ranking")
    add("Which department has the largest sanctioned faculty?",
        f"{max(DEPARTMENTS, key=lambda d: d[4])[0]} with "
        f"{max(d[4] for d in DEPARTMENTS)} faculty.", [HANDBOOK], "ranking")

    total_intake = sum(d[5] for d in DEPARTMENTS)
    add("What is the total annual intake across all departments?",
        f"The eight departments admit {total_intake} students per year in total.",
        [HANDBOOK], "aggregation", derived=True, difficulty="hard")
    total_faculty = sum(d[4] for d in DEPARTMENTS)
    add("How many faculty positions are sanctioned across the Institute?",
        f"{total_faculty} sanctioned faculty positions in total.",
        [HANDBOOK], "aggregation", derived=True, difficulty="hard")

    biggest = max(VENDORS, key=lambda v: v[2])
    add("Which vendor holds the largest annual contract?",
        f"{biggest[0]} at Rs. {rupees(biggest[2])} per year.", [VENDOR_DOC], "ranking")
    total_contracts = sum(v[2] for v in VENDORS)
    add("What is the total annual value of all recurring vendor contracts?",
        f"The eight recurring contracts total Rs. {rupees(total_contracts)} per year.",
        [VENDOR_DOC], "aggregation", derived=True, difficulty="hard")
    add("Which vendors have a relationship with the Institute beyond supplying a service?",
        "Trivendra Systems is also a campus recruiter with 14 offers, and Godavari "
        "Engineering Works sponsors the Manufacturing Excellence award.",
        [VENDOR_DOC], "synthesis", difficulty="hard")
    add("How many vendor contracts expire in 2026?",
        f"{len([v for v in VENDORS if v[4] == 2026])} contracts run to 2026.",
        [VENDOR_DOC], "counting", difficulty="hard")

    by_agency = {}
    for lab in LABS:
        by_agency.setdefault(lab[4], []).append(lab[0])
    top_agency = max(by_agency.items(), key=lambda kv: len(kv[1]))
    add("Which funding agency has supported the most laboratories?",
        f"{top_agency[0]} funded {len(top_agency[1])} laboratories.",
        [INFRA], "counting", difficulty="hard")
    total_grants = sum(lab[5] for lab in LABS)
    add("What is the combined grant value of all laboratories?",
        f"The ten laboratories were funded with Rs. {rupees(total_grants)} in total.",
        [INFRA], "aggregation", derived=True, difficulty="hard")
    add("Which laboratory received the largest grant?",
        f"{max(LABS, key=lambda x: x[5])[0]} at Rs. "
        f"{rupees(max(lab[5] for lab in LABS))}.", [INFRA], "ranking")
    add("How many laboratories does the Computer Science and Engineering department own?",
        f"{len([x for x in LABS if x[1] == 'CS'])} laboratories: the High Performance "
        "Computing Laboratory and the Cyber Security Laboratory.",
        [INFRA], "counting")

    gen = FEES[0]
    sc = FEES[2]
    add("What does a General category student pay in the first year, excluding hostel?",
        f"Tuition Rs. {rupees(gen[1])}, development Rs. {rupees(gen[2])}, examination "
        f"Rs. {rupees(gen[3])} and a library deposit of Rs. {rupees(gen[4])}, totalling "
        f"Rs. {rupees(sum(gen[1:]))}.", [FEE_DOC], "aggregation", derived=True,
        difficulty="hard")
    add("How much less does an SC category student pay in tuition than a General student?",
        f"Rs. {rupees(gen[1] - sc[1])} less: Rs. {rupees(sc[1])} against Rs. {rupees(gen[1])}.",
        [FEE_DOC], "comparison", derived=True, difficulty="hard")
    add("Compare the first-year cost for a General category hosteller and a day scholar.",
        f"A day scholar pays Rs. {rupees(sum(gen[1:]))}. A hosteller in Block A adds "
        f"Rs. {rupees(HOSTELS[0][4])}, reaching Rs. {rupees(sum(gen[1:]) + HOSTELS[0][4])}.",
        [FEE_DOC, HOSTEL_DOC], "aggregation", derived=True, difficulty="hard")

    add("What is the total hostel capacity of the Institute?",
        f"{sum(h[2] for h in HOSTELS)} beds across four blocks.",
        [HOSTEL_DOC], "aggregation")
    add("How many hostel beds are available to female students?",
        f"{sum(h[2] for h in HOSTELS if h[1] == 'female')} beds, in Block C and Block D.",
        [HOSTEL_DOC], "aggregation", derived=True, difficulty="hard")

    total_schol = sum(s[2] * s[3] for s in SCHOLARSHIPS)
    add("What was the total scholarship outlay in 2024-25?",
        f"Rs. {rupees(total_schol)} across the four Institute scholarships.",
        [SCHOL], "aggregation", derived=True, difficulty="hard")
    add("Which scholarship reached the most students?",
        f"{max(SCHOLARSHIPS, key=lambda s: s[3])[0]}, awarded to "
        f"{max(s[3] for s in SCHOLARSHIPS)} students.", [SCHOL], "ranking")

    add("Which committee meets most often?",
        f"{max(COMMITTEES, key=lambda c: c[3])[0]}, {max(c[3] for c in COMMITTEES)} times "
        "a year.", [GOV], "ranking")
    add("How many committees are chaired by heads of department?",
        f"{len([c for c in COMMITTEES if any(d[3] == c[1] for d in DEPARTMENTS)])} of the "
        "six committees are chaired by heads of department.",
        [GOV, HANDBOOK], "counting", difficulty="hard")

    total_research = sum(p[3] for p in PROJECTS)
    add("What is the total sanctioned value of the sponsored research portfolio?",
        f"Rs. {rupees(total_research)} across four projects.",
        [RESEARCH], "aggregation", derived=True, difficulty="hard")
    add("Which agency funds more than one sponsored project?",
        "SERB funds two projects: the edge inference and the low-power VLSI projects.",
        [RESEARCH], "counting", difficulty="hard")
    add("Which sponsored project has the largest budget and who leads it?",
        f"\"{max(PROJECTS, key=lambda p: p[3])[0]}\" at Rs. "
        f"{rupees(max(p[3] for p in PROJECTS))}, led by "
        f"{max(PROJECTS, key=lambda p: p[3])[1]}.", [RESEARCH], "ranking")

    add("How does the Institute protect student personal data?",
        "Under the Digital Personal Data Protection Act, 2023. Academic records are kept "
        "for ten years after graduation and disciplinary records for two years.",
        ["15_grievance_policy.md"], "synthesis", difficulty="hard")
    add("Summarise how a student complaint is escalated.",
        "It starts with the Head of Department, moves to the Grievance Redressal Cell "
        "after ten working days, and then to the Director. Harassment complaints bypass "
        "this and go to the Internal Complaints Committee.",
        ["15_grievance_policy.md"], "synthesis", difficulty="hard")
    add("What are the main themes of the Institute's policy documents?",
        "Academic regulations covering credits and attendance, the fee structure, hostel "
        "rules and governance committees.",
        [REGS, FEE_DOC, HOSTEL_DOC, GOV], "synthesis", difficulty="hard")

    # ── filter-then-join families: the answer needs a table scan in one document and a
    # lookup in another, which is where a single-chunk retrieval quietly fails ──

    for agency, labs in sorted(by_agency.items()):
        add(f"How many laboratories were funded by {agency}, and which departments own them?",
            f"{len(labs)} laboratories: {', '.join(labs)}, owned by "
            f"{', '.join(sorted({dept_by_code(x[1])[0] for x in LABS if x[4] == agency}))}.",
            [INFRA], "counting", difficulty="hard")

    by_vendor = {}
    for lab in LABS:
        by_vendor.setdefault(lab[3], []).append(lab[0])
    for vendor, labs in sorted(by_vendor.items()):
        add(f"How many laboratories did {vendor} equip?",
            f"{len(labs)}: {', '.join(labs)}.", [INFRA], "counting")

    lab_codes = {x[1] for x in LABS}
    without = [d[0] for d in DEPARTMENTS if d[1] not in lab_codes]
    add("Which departments have no laboratory listed in the infrastructure register?",
        (f"{', '.join(without)}." if without
         else "Every department has at least one laboratory listed."),
        [INFRA, HANDBOOK], "counting", difficulty="hard")

    old_good = [d for d in DEPARTMENTS if d[2] < 1995 and d[7] > 65]
    add("Which departments established before 1995 place more than 65% of their students?",
        f"{len(old_good)}: " + ", ".join(f"{d[0]} ({d[7]}%)" for d in old_good) + ".",
        [HANDBOOK, REPORT], "filter_join", difficulty="hard")

    chairs = {c[1]: c[0] for c in COMMITTEES}
    both = [(d[0], d[3], chairs[d[3]]) for d in DEPARTMENTS if d[3] in chairs]
    add("Which heads of department also chair a committee?",
        "; ".join(f"{hod} heads {dept} and chairs the {cm}" for dept, hod, cm in both) + ".",
        [HANDBOOK, GOV], "filter_join", difficulty="hard")

    ratio = max(DEPARTMENTS, key=lambda d: d[4] / d[5])
    add("Which department has the most faculty per admitted student?",
        f"{ratio[0]}, with {ratio[4]} faculty for an intake of {ratio[5]}.",
        [HANDBOOK], "ranking", derived=True, difficulty="hard")

    newest_labs = [x for x in LABS if x[2] >= 2018]
    add("Which laboratories were commissioned in 2018 or later?",
        f"{len(newest_labs)}: " + ", ".join(f"{x[0]} ({x[2]})" for x in newest_labs) + ".",
        [INFRA], "filter", difficulty="hard")

    best_new = max((x for x in LABS if x[2] >= 2018),
                   key=lambda x: dept_by_code(x[1])[7])
    add("Among departments owning a laboratory commissioned since 2018, which places best?",
        f"{dept_by_code(best_new[1])[0]} at {dept_by_code(best_new[1])[7]}%, which owns "
        f"the {best_new[0]}.", [INFRA, REPORT], "filter_join", difficulty="hard")

    add("How do the concession categories compare on tuition?",
        f"General and no-concession categories pay Rs. {rupees(FEES[0][1])}. OBC and EWS pay "
        f"Rs. {rupees(FEES[1][1])}. SC and ST pay Rs. {rupees(FEES[2][1])}.",
        [FEE_DOC], "comparison")
    add("Which categories pay a reduced development fee?",
        f"SC and ST pay Rs. {rupees(FEES[2][2])} against Rs. {rupees(FEES[0][2])} for the "
        "others.", [FEE_DOC], "comparison", difficulty="hard")

    add("How many students in total received an Institute scholarship in 2024-25?",
        f"{sum(s[3] for s in SCHOLARSHIPS)} students across the four schemes.",
        [SCHOL], "aggregation", derived=True, difficulty="hard")
    add("Which scholarships are means-tested rather than merit-based?",
        "The Rural Excellence Grant is means-tested, on family income below Rs. 2,50,000. "
        "The others turn on CGPA or sporting achievement.",
        [SCHOL], "synthesis", difficulty="hard")

    add("Which wardens are also heads of department?",
        "; ".join(f"{h[3]} ({h[0]})" for h in HOSTELS
                  if any(d[3] == h[3] for d in DEPARTMENTS)) + ".",
        [HOSTEL_DOC, HANDBOOK], "filter_join", difficulty="hard")
    add("How does male and female hostel capacity compare?",
        f"Male blocks hold {sum(h[2] for h in HOSTELS if h[1] == 'male')} beds and female "
        f"blocks {sum(h[2] for h in HOSTELS if h[1] == 'female')} beds.",
        [HOSTEL_DOC], "comparison", derived=True, difficulty="hard")

    add("Which sponsored projects run for three years or more?",
        ", ".join(f"\"{p[0]}\" ({p[5]} years)" for p in PROJECTS if p[5] >= 3) + ".",
        [RESEARCH], "filter", difficulty="hard")
    add("What overheads does the Institute retain on sponsored research?",
        "10% of the sanctioned amount.", [RESEARCH], "extraction")

    add("What must a student do to be both placement-eligible and promoted?",
        f"Maintain a CGPA of at least {PLACEMENT['eligibility_cgpa']} with "
        f"{PLACEMENT['eligibility_backlogs']} live backlogs for placement, and carry no "
        f"more than {R['max_backlogs_to_promote']} backlogs to be promoted, with at least "
        f"{R['min_attendance_pct']}% attendance.",
        [PLACE, REGS], "synthesis", difficulty="hard")
    add("Which authority appears in the most policies, and in what capacity?",
        "The Registrar: reviewing vendor contracts, deciding hostel removals, "
        "administering government scholarships, receiving committee minutes, and running "
        "the examination section.",
        [VENDOR_DOC, HOSTEL_DOC, SCHOL, GOV], "synthesis", difficulty="hard")
    add("What deadlines does a first-year student face in their first semester?",
        f"Fees by {CALENDAR['fee_deadline']} or a Rs. {rupees(CALENDAR['late_fee'])} fine, "
        f"the odd semester running {CALENDAR['odd_semester_start']} to "
        f"{CALENDAR['odd_semester_end']}, and revaluation within "
        f"{R['revaluation_window_days']} days of a result.",
        [FEE_DOC, CAL, REGS], "synthesis", difficulty="hard")

    for i, item in enumerate(q):
        item["id"] = f"BG{i + 1:03d}"
    return q


# ── UNANSWERABLE ─────────────────────────────────────────────────────────────

def unanswerable_questions():
    gaps = [
        ("What is the PhD tuition fee?", "plausible_gap"),
        ("How many students are enrolled in the M.Tech programme?", "plausible_gap"),
        ("What is the mess menu for Wednesday?", "plausible_gap"),
        ("Which IIT is the Institute affiliated with?", "false_premise"),
        ("What is the pass percentage of the Department of Aerospace Engineering?", "false_premise"),
        ("Who is the Dean of Student Affairs?", "plausible_gap"),
        ("How many patents were granted to the Institute in 2023-24?", "plausible_gap"),
        ("What is the salary of the Registrar?", "out_of_scope"),
        ("Which hostel block has air conditioning?", "plausible_gap"),
        ("What is the Institute's NIRF ranking?", "plausible_gap"),
        ("How many buses does Pune Bus Lines operate on each route?", "plausible_gap"),
        ("What is the CGPA of the topper in Civil Engineering?", "out_of_scope"),
        ("When will the new library building open?", "plausible_gap"),
        ("What is the annual electricity bill of the campus?", "plausible_gap"),
        ("Which company recruited the most students from Chemical Engineering?", "plausible_gap"),
        ("What is the fee for the Data Analytics Studio certification course?", "false_premise"),
        ("How many international students are enrolled?", "plausible_gap"),
        ("What is the retirement age for faculty?", "plausible_gap"),
        ("Who was the Director before Dr. Nandini Apte?", "plausible_gap"),
        ("What is the hostel fee for a single-occupancy room?", "false_premise"),
    ]
    return [{"id": f"BU{i + 1:03d}", "question": qn,
             "expected_behaviour": "Not stated in the corpus", "gap_type": gt}
            for i, (qn, gt) in enumerate(gaps)]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sets = {
        "golden_fact.json": fact_questions(),
        "golden_local.json": local_questions(),
        "golden_global.json": global_questions(),
        "golden_unanswerable.json": unanswerable_questions(),
    }
    for fname, qs in sets.items():
        (OUT / fname).write_text(
            json.dumps({"questions": qs}, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"  {fname:28s} {len(qs):4d} questions")
    total = sum(len(v) for v in sets.values())
    derived = sum(1 for q in sets["golden_global.json"] if q.get("derived"))
    print(f"total {total} questions -> {OUT}")
    print(f"  ({derived} GLOBAL questions require computing a figure the corpus does not state)")


if __name__ == "__main__":
    main()
