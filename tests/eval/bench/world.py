"""The bench world model — one source of truth for both the corpus and the golds.

Every number and name the benchmark uses lives here exactly once. The documents are
RENDERED from this model and the questions are DERIVED from it, so a gold cannot disagree
with the corpus: there is no scraping step in between to get it wrong. That is the whole
point — the existing stresskit golds were regex-scraped out of prose, which is how a
correct answer ended up scored FAIL for writing "Computer Science and Engineering" where
the gold said "CSE".

Design constraints that make the resulting benchmark actually discriminating:

  * Facts are SPLIT across documents on purpose. A department's placement rate lives in
    the annual report; the name of the person who heads it lives in the faculty handbook;
    the lab it operates lives in the infrastructure register; the vendor that equipped
    that lab lives in the vendor schedule. A question that crosses two of those is a real
    multi-hop question, not a lookup with extra words.
  * Nothing is random. No seeds, no generated filler — a fixed, hand-checked world, so a
    regression is always a system change and never a corpus change.
  * Distractors are deliberate: several departments have similar pass rates, two vendors
    serve the same lab, and two people share a surname. Retrieval that merely lands in the
    right document still has to pick the right row.
"""

INSTITUTE = {
    "name": "Sahyadri Institute of Technology",
    "short": "SIT",
    "established": 1983,
    "city": "Karad",
    "district": "Satara",
    "affiliation": "Shivaji University, Kolhapur",
    "director": "Dr. Nandini Apte",
    "registrar": "Shri Prakash Deshmukh",
    "campus_acres": 62,
}

# name, code, established, hod, faculty, intake, pass_pct, placement_pct, avg_lpa, highest_lpa
DEPARTMENTS = [
    ("Computer Science and Engineering", "CS", 1998, "Dr. Meera Joshi", 34, 120, 94.1, 92.4, 8.6, 44.0),
    ("Information Technology", "IT", 2001, "Dr. Sameer Kulkarni", 26, 90, 93.7, 88.1, 7.9, 31.0),
    ("Electronics and Telecommunication", "ET", 1987, "Dr. Anjali Pawar", 29, 90, 88.2, 71.6, 5.4, 18.5),
    ("Mechanical Engineering", "ME", 1983, "Dr. Vasant Rane", 41, 120, 85.6, 68.3, 4.8, 14.2),
    ("Civil Engineering", "CE", 1983, "Dr. Ramesh Bhosale", 33, 90, 79.4, 60.7, 4.1, 11.0),
    ("Electrical Engineering", "EE", 1991, "Dr. Sunita Gaikwad", 27, 60, 86.9, 66.2, 4.6, 12.8),
    ("Artificial Intelligence and Data Science", "AD", 2021, "Dr. Farhan Shaikh", 18, 60, 91.8, 84.5, 9.2, 38.0),
    ("Chemical Engineering", "CH", 1994, "Dr. Vaishali Rane", 22, 60, 82.3, 57.9, 4.3, 9.6),
]

# name, department code, year, equipment vendor, funding agency, grant (rupees), custodian
# Custodians are technical staff, deliberately NOT heads of department: if an HOD were also
# a custodian, the infrastructure register would name the answer to "who heads the
# department that runs lab X" for an unrelated reason, and the hop could be skipped by luck.
LABS = [
    ("High Performance Computing Laboratory", "CS", 2022, "Trivendra Systems", "AICTE MODROB", 19400000, "Dr. Iqbal Shaikh"),
    ("Cyber Security Laboratory", "CS", 2019, "Nashik Netcom", "TEQIP III", 6200000, "Shri Dattatray Mane"),
    ("Data Analytics Studio", "AD", 2021, "Trivendra Systems", "AICTE MODROB", 8800000, "Smt. Rekha Patil"),
    ("Advanced Manufacturing Laboratory", "ME", 2017, "Godavari Engineering Works", "DST FIST", 24500000, "Shri Ganesh Tarate"),
    ("Thermal Engineering Laboratory", "ME", 2005, "Godavari Engineering Works", "Institute funds", 3100000, "Shri Ganesh Tarate"),
    ("VLSI Design Laboratory", "ET", 2016, "Nashik Netcom", "TEQIP III", 7400000, "Smt. Rekha Patil"),
    ("Structural Testing Laboratory", "CE", 2012, "Konkan Instruments", "DST FIST", 11200000, "Shri Dattatray Mane"),
    ("Power Systems Laboratory", "EE", 2009, "Konkan Instruments", "Institute funds", 5300000, "Shri Ganesh Tarate"),
    ("Process Control Laboratory", "CH", 2014, "Konkan Instruments", "Institute funds", 4100000, "Smt. Rekha Patil"),
    ("Network Systems Laboratory", "IT", 2018, "Nashik Netcom", "TEQIP III", 6900000, "Shri Dattatray Mane"),
]

# name, service, annual value (rupees), contract start, contract end, secondary role (or None)
VENDORS = [
    ("Trivendra Systems", "HPC and analytics hardware supply", 2600000, 2022, 2027,
     "campus recruiter, 14 offers in 2024-25"),
    ("Konkan Facility Services", "hostel housekeeping and maintenance", 4100000, 2021, 2026, None),
    ("Godavari Engineering Works", "workshop machinery AMC", 1800000, 2020, 2025,
     "sponsors the annual Manufacturing Excellence award"),
    ("Nashik Netcom", "campus network and security appliances AMC", 2350000, 2023, 2028, None),
    ("Konkan Instruments", "laboratory instrumentation supply", 1950000, 2019, 2026, None),
    ("Sahyadri Caterers", "mess and canteen operations", 5400000, 2022, 2025, None),
    ("Pune Bus Lines", "student transport on six routes", 3200000, 2023, 2026, None),
    ("Suvarna Security", "campus security services", 2870000, 2021, 2026, None),
]

# block, gender, capacity, warden, per-year fee (rupees), rooms per floor
HOSTELS = [
    ("Block A", "male", 320, "Dr. Vasant Rane", 45000, 18),
    ("Block B", "male", 300, "Dr. Prakash Salunkhe", 45000, 18),
    ("Block C", "female", 240, "Dr. Anjali Pawar", 48000, 16),
    ("Block D", "female", 180, "Dr. Sunita Gaikwad", 48000, 16),
]

# category, tuition, development fee, exam fee, library deposit
FEES = [
    ("General", 142000, 12500, 4800, 3200),
    ("OBC", 71000, 12500, 4800, 3200),
    ("SC", 35500, 6250, 4800, 3200),
    ("ST", 35500, 6250, 4800, 3200),
    ("EWS", 71000, 12500, 4800, 3200),
]

# name, eligibility, amount per year, recipients in 2024-25
SCHOLARSHIPS = [
    ("Gujar Merit Scholarship", "CGPA of 9.00 or above in the previous year", 40000, 22),
    ("Rural Excellence Grant", "family income below Rs. 2,50,000 per annum", 25000, 68),
    ("Women in Engineering Award", "female students with CGPA 8.50 or above", 30000, 41),
    ("Sports Achievement Grant", "representation at state level or above", 18000, 15),
]

# committee, chair, member count, meets (times per year)
COMMITTEES = [
    ("Internal Complaints Committee", "Dr. Anjali Pawar", 7, 4),
    ("Anti-Ragging Committee", "Dr. Vasant Rane", 11, 6),
    ("Academic Council", "Dr. Nandini Apte", 15, 2),
    ("Grievance Redressal Cell", "Dr. Sameer Kulkarni", 6, 12),
    ("Library Advisory Committee", "Dr. Ramesh Bhosale", 5, 2),
    ("Research Advisory Board", "Dr. Iqbal Shaikh", 9, 3),
]

# title, principal investigator, agency, amount, start year, duration years
PROJECTS = [
    ("Edge Inference for Precision Agriculture", "Dr. Farhan Shaikh", "SERB", 3400000, 2023, 3),
    ("Seismic Retrofitting of Heritage Structures", "Dr. Ramesh Bhosale", "DST", 5100000, 2022, 4),
    ("Additive Manufacturing of Turbine Blades", "Dr. Vasant Rane", "ARDB", 7250000, 2024, 3),
    ("Low-Power VLSI for Wearable Sensors", "Dr. Anjali Pawar", "SERB", 2900000, 2023, 2),
]

ACADEMIC_RULES = {
    "btech_credits": 168,
    "min_attendance_pct": 75,
    "min_attendance_final_year_pct": 80,
    "min_cgpa_award": 5.50,
    "max_credits_per_semester": 28,
    "min_credits_per_semester": 18,
    "final_project_credits": 12,
    "lab_course_credits": 1,
    "failing_grade": "FF",
    "max_backlogs_to_promote": 4,
    "debarment_attendance_pct": 65,
    "max_duration_years": 8,
    "revaluation_window_days": 15,
    "revaluation_fee": 750,
}

LIBRARY = {
    "titles": 48200,
    "journals": 310,
    "e_resources": 12,
    "seats": 420,
    "weekday_hours": "08:00 to 22:00",
    "weekend_hours": "09:00 to 18:00",
    "loan_days": 14,
    "loan_limit": 4,
    "fine_per_day": 5,
    "reference_section_loanable": False,
}

PLACEMENT = {
    "eligibility_cgpa": 6.00,
    "eligibility_backlogs": 0,
    "companies_2024_25": 87,
    "total_offers": 612,
    "highest_lpa": 44.0,
    "median_lpa": 5.9,
    "internship_weeks": 8,
    "one_offer_policy": True,
}

CALENDAR = {
    "odd_semester_start": "15 July",
    "odd_semester_end": "22 November",
    "even_semester_start": "5 January",
    "even_semester_end": "18 May",
    "convocation": "12 February",
    "foundation_day": "3 September",
    "fee_deadline": "31 August",
    "late_fee": 500,
}


def dept_by_code(code):
    return next(d for d in DEPARTMENTS if d[1] == code)


def rupees(n: int) -> str:
    """Indian digit grouping — 142000 -> 1,42,000. The corpus writes numbers this way;
    the v2 gold derivation normalises commas on both sides so either surface scores."""
    s = str(n)
    if len(s) <= 3:
        return s
    head, tail = s[:-3], s[-3:]
    parts = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return ",".join(parts + [tail])


# Faculty rosters per department. These exist to give retrieval something to be WRONG
# about: every department profile has the same shape and similar vocabulary, differing
# only in names, numbers and specialisations. A retriever that lands in "a department
# profile" rather than "the right department profile" is then visibly wrong, which a
# 27-chunk corpus could never expose — at that size top-k returns a third of everything.
FACULTY = {
    "CS": ["Dr. Meera Joshi", "Dr. Amruta Kale", "Dr. Nilesh Wagh", "Prof. Sayali Kadam"],
    "IT": ["Dr. Sameer Kulkarni", "Dr. Pooja Nimbalkar", "Prof. Akash Jagtap"],
    "ET": ["Dr. Anjali Pawar", "Dr. Sandeep Chavan", "Prof. Manisha Kore"],
    "ME": ["Dr. Vasant Rane", "Dr. Prakash Salunkhe", "Dr. Kiran Deshpande", "Prof. Omkar Bhoite"],
    "CE": ["Dr. Ramesh Bhosale", "Dr. Swapnil Mohite", "Prof. Trupti Sawant"],
    "EE": ["Dr. Sunita Gaikwad", "Dr. Harshad Patange", "Prof. Nikhil Zende"],
    "AD": ["Dr. Farhan Shaikh", "Dr. Shweta Bagade", "Prof. Rohit Mulik"],
    "CH": ["Dr. Vaishali Rane", "Dr. Ajinkya Kamble", "Prof. Neha Thorat"],
}

# Elective baskets per department — same structure everywhere, different content.
ELECTIVES = {
    "CS": ["Distributed Systems", "Compiler Design", "Information Retrieval"],
    "IT": ["Cloud Architecture", "Mobile Computing", "Service Oriented Design"],
    "ET": ["Antenna Theory", "Embedded Firmware", "Signal Estimation"],
    "ME": ["Tribology", "Computational Fluid Dynamics", "Robotics and Automation"],
    "CE": ["Earthquake Engineering", "Transportation Planning", "Hydrology"],
    "EE": ["Power Electronics", "Smart Grids", "Electrical Machine Design"],
    "AD": ["Deep Learning", "Time Series Analysis", "Responsible AI"],
    "CH": ["Reaction Engineering", "Process Safety", "Membrane Separation"],
}

# Per-department accreditation and result detail, deliberately similar across departments.
DEPT_DETAIL = {
    "CS": (2019, 2025, 6, 41, "NBA Tier-I"), "IT": (2018, 2024, 4, 29, "NBA Tier-I"),
    "ET": (2017, 2026, 5, 22, "NBA Tier-II"), "ME": (2016, 2025, 7, 31, "NBA Tier-I"),
    "CE": (2018, 2024, 3, 17, "NBA Tier-II"), "EE": (2019, 2026, 4, 19, "NBA Tier-II"),
    "AD": (2022, 2027, 2, 12, "provisional"), "CH": (2017, 2025, 3, 14, "NBA Tier-II"),
}
