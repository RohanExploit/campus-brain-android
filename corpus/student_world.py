"""The student-corpus world model — one source of truth for the PDFs rendered from it.

This follows the discipline set out in tests/eval/bench/world.py: facts are split across
documents on purpose, nothing here is random (no seeds, no generated filler — a fixed,
hand-checked world so a regression is always a system change and never a corpus change),
and several distractors are deliberate rather than accidental. See the comment above each
distractor below for what it tests.

The institute, every person, every roll number, phone number and email in this module are
INVENTED. This is the whole point of building a synthetic corpus: a PII-free document set
can be committed, made public, and bundled into an app, which real student records never
can. "Konkan Ratna Institute of Engineering and Technology" was checked against a live web
search before being chosen and does not match any real institution; do not rename it to
match one. It is deliberately NOT "Sahyadri Institute of Technology" (tests/eval/bench's
fictional institute — that is benchmark data and must stay uncontaminated) and NOT any real
college. Its affiliating university, Dr. Babasaheb Ambedkar Technological University
(DBATU), Lonere, IS real — the only state technological university in Maharashtra — exactly
as tests/eval/bench/world.py affiliates its fictional SIT to the real Shivaji University:
a real affiliating body lends the corpus's cross-references (fee circulars, exam
regulations) their ordinary texture without claiming any specific college is real.

Every phone number in this module (institute switchboard included) uses the block reserved
for fiction, +91 90000 xxxxx, enforced by tests/test_student_world.py. Holiday dates for
2026 and 2027 were checked against a live gazetted holiday calendar (officeholidays.com,
2026-08-29) rather than reconstructed from memory, because a lunar festival date is a
specific checkable fact and getting one wrong would quietly break the "hand-checked" bar
the rest of this module holds itself to.
"""

# ── the institution ──────────────────────────────────────────────────────────

INSTITUTE = {
    "name": "Konkan Ratna Institute of Engineering and Technology",
    "short": "KRIET",
    "established": 1999,
    "established_date": "21 September 1999",
    "city": "Ratnagiri",
    "district": "Ratnagiri",
    "state": "Maharashtra",
    "affiliation": "Dr. Babasaheb Ambedkar Technological University (DBATU), Lonere",
    "principal_key": "principal",
    "registrar_key": "registrar",
    "campus_acres": 18,
    # AICTE Extension of Approval reference. Format mirrors a real EOA reference
    # (Region/Application-ID/Year/EOA); the digits are invented.
    "aicte_approval_ref": "F.No. Western/1-3927456108/2026/EOA",
    "website": "www.kriet.ac.in",
    "main_phone": "+91 90000 10000",
    "address": "Kokan Vidyanagar, Nachane Road, Ratnagiri - 415639, Maharashtra",
}


# ── departments ──────────────────────────────────────────────────────────────
# name, code, established, hod person-key, annual intake, sanctioned faculty count.
#
# Only COMP and EXTC get a full subject catalogue, timetable and exam schedule (see
# SUBJECTS/TIMETABLE below) — the other three exist so PLACEMENTS and DEADLINES have real
# branches to reference without every branch needing a rendered timetable.
DEPARTMENTS = [
    ("Computer Engineering", "COMP", 2002, "hod_comp", 120, 22),
    ("Electronics and Telecommunication Engineering", "EXTC", 1999, "hod_extc", 60, 14),
    ("Mechanical Engineering", "MECH", 1999, "hod_mech", 60, 16),
    ("Civil Engineering", "CIVIL", 1999, "hod_civil", 60, 12),
    ("Information Technology", "IT", 2008, "hod_it", 60, 13),
]


def dept_by_code(code):
    return next(d for d in DEPARTMENTS if d[1] == code)


# ── people / SPOCs ───────────────────────────────────────────────────────────
# Keyed by a stable short id (not by name), because every other section below refers to a
# person by this key, never by a name string that could drift. render_base.NoticeDoc's
# .signature(person_key) resolves against exactly this dict.
#
# Deliberate distractor: two HODs share the surname Kadam — Dr. Manasi Kadam (Computer
# Engineering) and Dr. Prashant Kadam (Electronics and Telecommunication) — mirroring the
# bench world's two Ranes. A retriever that lands on "Dr. Kadam, HOD" without checking which
# department is now visibly wrong instead of accidentally right.
PEOPLE = {
    "principal": {
        "name": "Dr. Suhas Kelkar", "designation": "Principal", "department": None,
        "role": "Principal", "email": "principal@kriet.ac.in",
        "phone": "+91 90000 10001", "office": "Admin Building, Room 101",
        "hours": "Monday-Friday 11:00-13:00 (prior appointment via the Registrar's office)",
    },
    "registrar": {
        "name": "Shri Vijay Naik", "designation": "Registrar", "department": None,
        "role": "Registrar", "email": "registrar@kriet.ac.in",
        "phone": "+91 90000 10002", "office": "Admin Building, Room 104",
        "hours": "Monday-Saturday 10:00-17:00",
    },
    "hod_comp": {
        "name": "Dr. Manasi Kadam", "designation": "Professor and Head",
        "department": "COMP", "role": "HOD, Computer Engineering",
        "email": "m.kadam@kriet.ac.in", "phone": "+91 90000 10003",
        "office": "Computer Engineering Block, Room CO-101",
        "hours": "Monday-Friday 14:00-15:00",
    },
    "hod_extc": {
        "name": "Dr. Prashant Kadam", "designation": "Professor and Head",
        "department": "EXTC", "role": "HOD, Electronics and Telecommunication Engineering",
        "email": "p.kadam@kriet.ac.in", "phone": "+91 90000 10004",
        "office": "EXTC Block, Room EC-101",
        "hours": "Monday-Friday 14:00-15:00",
    },
    "hod_mech": {
        "name": "Dr. Suresh Bhagat", "designation": "Professor and Head",
        "department": "MECH", "role": "HOD, Mechanical Engineering",
        "email": "s.bhagat@kriet.ac.in", "phone": "+91 90000 10005",
        "office": "Mechanical Block, Room ME-101",
        "hours": "Tuesday and Thursday 15:00-16:00",
    },
    "hod_civil": {
        "name": "Dr. Neelam Toraskar", "designation": "Professor and Head",
        "department": "CIVIL", "role": "HOD, Civil Engineering",
        "email": "n.toraskar@kriet.ac.in", "phone": "+91 90000 10006",
        "office": "Civil Block, Room CE-101",
        "hours": "Monday and Wednesday 15:00-16:00",
    },
    "hod_it": {
        "name": "Dr. Rajendra Samant", "designation": "Associate Professor and Head",
        "department": "IT", "role": "HOD, Information Technology",
        "email": "r.samant@kriet.ac.in", "phone": "+91 90000 10007",
        "office": "IT Block, Room IT-101",
        "hours": "Wednesday and Friday 14:00-15:00",
    },
    "scholarship_spoc": {
        "name": "Mrs. Deepali Ghorpade", "designation": "Assistant Registrar (Scholarships)",
        "department": None, "role": "Scholarship SPOC",
        "email": "scholarships@kriet.ac.in", "phone": "+91 90000 10008",
        "office": "Admin Building, Room 106 (Accounts Section)",
        "hours": "Monday-Saturday 10:30-16:30",
    },
    "tpo": {
        "name": "Prof. Ajit Sawant", "designation": "Training and Placement Officer",
        "department": None, "role": "Training and Placement Officer",
        "email": "tpo@kriet.ac.in", "phone": "+91 90000 10009",
        "office": "Placement Cell, Admin Building Ground Floor",
        "hours": "Monday-Saturday 09:30-17:00",
    },
    "grievance_chair": {
        "name": "Dr. Nitin Salvi", "designation": "Associate Professor, EXTC",
        "department": "EXTC", "role": "Grievance Redressal Committee Chair",
        "email": "n.salvi@kriet.ac.in", "phone": "+91 90000 10010",
        "office": "EXTC Block, Room EC-104",
        "hours": "Tuesday 15:00-16:00 (grievance hours)",
    },
    "antiragging_spoc": {
        "name": "Prof. Snehal Bhagwat", "designation": "Assistant Professor, Computer Engineering",
        "department": "COMP", "role": "Anti-Ragging SPOC",
        "email": "s.bhagwat@kriet.ac.in", "phone": "+91 90000 10011",
        "office": "Computer Engineering Block, Room CO-105",
        "hours": "Monday-Friday 10:00-11:00",
    },
    "librarian": {
        "name": "Mrs. Ujwala Rege", "designation": "Librarian", "department": None,
        "role": "Librarian", "email": "librarian@kriet.ac.in",
        "phone": "+91 90000 10012", "office": "Central Library, Ground Floor",
        "hours": "Monday-Saturday, during library hours",
    },
    "sports_officer": {
        "name": "Mr. Ganesh Thorve", "designation": "Physical Director", "department": None,
        "role": "Sports Officer", "email": "sports@kriet.ac.in",
        "phone": "+91 90000 10013", "office": "Sports Complex Office",
        "hours": "Monday-Saturday 08:00-14:00",
    },
    "incubation_manager": {
        "name": "Dr. Abhijit Naik", "designation": "Incubation Manager", "department": None,
        "role": "Incubation Manager", "email": "incubation@kriet.ac.in",
        "phone": "+91 90000 10014",
        "office": "Ratnaditya Innovation and Incubation Centre, Block D",
        "hours": "Monday-Friday 11:00-17:00",
    },
    "exam_controller": {
        "name": "Prof. Madhavi Sathe", "designation": "Controller of Examinations",
        "department": None, "role": "Exam Controller",
        "email": "exam.controller@kriet.ac.in", "phone": "+91 90000 10015",
        "office": "Examination Section, Admin Building, Room 110",
        "hours": "Monday-Saturday 10:00-17:00 (09:00-18:00 during examination weeks)",
    },
    "attendance_coordinator": {
        "name": "Mrs. Pallavi Chitnis", "designation": "Assistant Professor",
        "department": None, "role": "Attendance Coordinator",
        "email": "attendance@kriet.ac.in", "phone": "+91 90000 10016",
        "office": "Academic Section, Admin Building, Room 108",
        "hours": "Monday-Friday 11:00-13:00",
    },
    "fac_panse": {
        "name": "Prof. Rutuja Panse", "designation": "Assistant Professor, Computer Engineering",
        "department": "COMP", "role": None, "email": "r.panse@kriet.ac.in",
        "phone": "+91 90000 10017", "office": "Computer Engineering Block, Room CO-108",
        "hours": "Monday-Friday 13:00-14:00",
    },
    "fac_oak": {
        "name": "Dr. Vikrant Oak", "designation": "Associate Professor, Computer Engineering",
        "department": "COMP", "role": None, "email": "v.oak@kriet.ac.in",
        "phone": "+91 90000 10018", "office": "Computer Engineering Block, Room CO-109",
        "hours": "Monday-Friday 13:00-14:00",
    },
    "fac_ketkar": {
        "name": "Prof. Omkar Ketkar", "designation": "Assistant Professor, Computer Engineering",
        "department": "COMP", "role": None, "email": "o.ketkar@kriet.ac.in",
        "phone": "+91 90000 10019", "office": "Computer Engineering Block, Room CO-110",
        "hours": "Monday-Friday 13:00-14:00",
    },
    "fac_bapat": {
        "name": "Prof. Sarika Bapat", "designation": "Assistant Professor, EXTC",
        "department": "EXTC", "role": None, "email": "s.bapat@kriet.ac.in",
        "phone": "+91 90000 10020", "office": "EXTC Block, Room EC-106",
        "hours": "Monday-Friday 13:00-14:00",
    },
    "fac_puranik": {
        "name": "Prof. Yogesh Puranik", "designation": "Assistant Professor, EXTC",
        "department": "EXTC", "role": None, "email": "y.puranik@kriet.ac.in",
        "phone": "+91 90000 10021", "office": "EXTC Block, Room EC-107",
        "hours": "Monday-Friday 13:00-14:00",
    },
    "fac_devrukhkar": {
        "name": "Prof. Ashwini Devrukhkar", "designation": "Assistant Professor, EXTC",
        "department": "EXTC", "role": None, "email": "a.devrukhkar@kriet.ac.in",
        "phone": "+91 90000 10022", "office": "EXTC Block, Room EC-108",
        "hours": "Monday-Friday 13:00-14:00",
    },
    # The six SPOCs below back the notices/handbook/events/attendance/deadline-tracker
    # documents in corpus/render_notices.py — general administrative functions (fees, ID
    # cards, hostel, transport, student activities, NSS) that the first rendering pass had
    # no notice category needing yet.
    "accounts_spoc": {
        "name": "Mr. Santosh Rane", "designation": "Section Officer, Accounts",
        "department": None, "role": "Fee and Accounts SPOC",
        "email": "accounts@kriet.ac.in", "phone": "+91 90000 10023",
        "office": "Admin Building, Room 105 (Accounts Section)",
        "hours": "Monday-Saturday 10:30-16:30",
    },
    "student_section_spoc": {
        "name": "Mrs. Vaishali Naik", "designation": "Section Officer, Student Section",
        "department": None,
        "role": "Student Section SPOC (ID cards, certificates, semester registration)",
        "email": "studentsection@kriet.ac.in", "phone": "+91 90000 10024",
        "office": "Admin Building, Room 107 (Student Section)",
        "hours": "Monday-Saturday 10:00-16:00",
    },
    "hostel_warden": {
        "name": "Dr. Prakash Bhide", "designation": "Chief Hostel Warden",
        "department": None, "role": "Hostel Warden",
        "email": "hostel@kriet.ac.in", "phone": "+91 90000 10025",
        "office": "Hostel Administration Office, Block E",
        "hours": "Monday-Saturday 17:00-19:00 (hostel office hours)",
    },
    "transport_incharge": {
        "name": "Mr. Dilip Kadave", "designation": "Transport In-Charge",
        "department": None, "role": "Bus Transport SPOC",
        "email": "transport@kriet.ac.in", "phone": "+91 90000 10026",
        "office": "Transport Office, near Main Gate",
        "hours": "Monday-Saturday 08:00-16:00",
    },
    "student_activities_officer": {
        "name": "Prof. Kavita Wagh", "designation": "Dean, Student Activities",
        "department": None, "role": "Student Activities and Events SPOC",
        "email": "activities@kriet.ac.in", "phone": "+91 90000 10027",
        "office": "Student Activities Cell, Admin Building Ground Floor",
        "hours": "Monday-Friday 15:00-16:30",
    },
    "nss_officer": {
        "name": "Dr. Sanjay Parab", "designation": "NSS Programme Officer",
        "department": None, "role": "NSS Coordinator",
        "email": "nss@kriet.ac.in", "phone": "+91 90000 10028",
        "office": "NSS Cell, Admin Building Ground Floor",
        "hours": "Monday-Friday 14:00-15:00",
    },
}


def person(key):
    return PEOPLE[key]


# ── subjects ──────────────────────────────────────────────────────────────────
# code, title, department code, semester, credits, type (theory/lab/elective).
#
# Code scheme: {dept}{semester}{seq}, e.g. CO301 = Computer Engineering, semester 3.
#
# Deliberate distractor: CO404 (semester 4, "Design and Analysis of Algorithms") and CO405
# (semester 5, "Web Technology Laboratory") are numerically adjacent but sit in different
# semesters. CO405 is out of its "should be CO5xx" sequence on purpose — the lab was added
# to the semester 5 curriculum in a later syllabus revision, after CO501-CO506 were already
# allotted, and picked up the next free code in the CO4xx block rather than a gap-filled
# CO5xx one. Any code that assumes "the digit after the department letters IS the semester"
# is wrong for exactly this one subject.
#
# Second distractor: CO402 ("Computer Networks", COMP semester 4) and EC603 ("Computer
# Networks", EXTC semester 6) share a title across two different departments and semesters.
SUBJECTS = [
    # Computer Engineering, semester 3
    ("CO301", "Data Structures and Algorithms", "COMP", 3, 3, "theory"),
    ("CO302", "Digital Logic and Computer Organization", "COMP", 3, 3, "theory"),
    ("CO303", "Discrete Mathematics", "COMP", 3, 3, "theory"),
    ("CO304", "Object Oriented Programming with Java", "COMP", 3, 3, "theory"),
    ("CO305", "Data Structures Laboratory", "COMP", 3, 1, "lab"),
    ("CO306", "Java Programming Laboratory", "COMP", 3, 1, "lab"),
    # Computer Engineering, semester 4
    ("CO401", "Database Management Systems", "COMP", 4, 3, "theory"),
    ("CO402", "Computer Networks", "COMP", 4, 3, "theory"),
    ("CO403", "Operating Systems", "COMP", 4, 3, "theory"),
    ("CO404", "Design and Analysis of Algorithms", "COMP", 4, 3, "theory"),
    ("CO406", "Database Management Systems Laboratory", "COMP", 4, 1, "lab"),
    ("CO407", "Operating Systems Laboratory", "COMP", 4, 1, "lab"),
    # Computer Engineering, semester 5 — CO405 deliberately out of sequence, see note above
    ("CO405", "Web Technology Laboratory", "COMP", 5, 1, "lab"),
    ("CO501", "Software Engineering", "COMP", 5, 3, "theory"),
    ("CO502", "Theory of Computation", "COMP", 5, 3, "theory"),
    ("CO503", "Web Technology", "COMP", 5, 3, "theory"),
    ("CO504", "Elective I: Distributed Systems", "COMP", 5, 3, "elective"),
    ("CO506", "Mini Project I", "COMP", 5, 2, "lab"),
    # Computer Engineering, semester 6
    ("CO601", "Artificial Intelligence", "COMP", 6, 3, "theory"),
    ("CO602", "Cryptography and Network Security", "COMP", 6, 3, "theory"),
    ("CO603", "Cloud Computing", "COMP", 6, 3, "theory"),
    ("CO604", "Elective II: Machine Learning", "COMP", 6, 3, "elective"),
    ("CO605", "Artificial Intelligence Laboratory", "COMP", 6, 1, "lab"),
    ("CO606", "Mini Project II", "COMP", 6, 2, "lab"),
    # Electronics and Telecommunication, semester 3
    ("EC301", "Network Analysis and Synthesis", "EXTC", 3, 3, "theory"),
    ("EC302", "Electronic Devices and Circuits", "EXTC", 3, 3, "theory"),
    ("EC303", "Digital Electronics", "EXTC", 3, 3, "theory"),
    ("EC304", "Electromagnetic Field Theory", "EXTC", 3, 3, "theory"),
    ("EC305", "Electronic Devices Laboratory", "EXTC", 3, 1, "lab"),
    ("EC306", "Digital Electronics Laboratory", "EXTC", 3, 1, "lab"),
    # Electronics and Telecommunication, semester 4
    ("EC401", "Analog Communication", "EXTC", 4, 3, "theory"),
    ("EC402", "Microcontroller and Applications", "EXTC", 4, 3, "theory"),
    ("EC403", "Signals and Systems", "EXTC", 4, 3, "theory"),
    ("EC404", "Linear Integrated Circuits", "EXTC", 4, 3, "theory"),
    ("EC405", "Microcontroller Laboratory", "EXTC", 4, 1, "lab"),
    ("EC406", "Analog Communication Laboratory", "EXTC", 4, 1, "lab"),
    # Electronics and Telecommunication, semester 5
    ("EC501", "Digital Communication", "EXTC", 5, 3, "theory"),
    ("EC502", "Digital Signal Processing", "EXTC", 5, 3, "theory"),
    ("EC503", "Antenna and Wave Propagation", "EXTC", 5, 3, "theory"),
    ("EC504", "Elective I: Embedded Systems", "EXTC", 5, 3, "elective"),
    ("EC505", "Digital Signal Processing Laboratory", "EXTC", 5, 1, "lab"),
    ("EC506", "Mini Project I", "EXTC", 5, 2, "lab"),
    # Electronics and Telecommunication, semester 6
    ("EC601", "VLSI Design", "EXTC", 6, 3, "theory"),
    ("EC602", "Mobile Communication", "EXTC", 6, 3, "theory"),
    ("EC603", "Computer Networks", "EXTC", 6, 3, "theory"),
    ("EC604", "Elective II: Optical Communication", "EXTC", 6, 3, "elective"),
    ("EC605", "VLSI Design Laboratory", "EXTC", 6, 1, "lab"),
    ("EC606", "Mini Project II", "EXTC", 6, 2, "lab"),
]


def subject_by_code(code):
    return next(s for s in SUBJECTS if s[0] == code)


# ── academic calendar 2026-27 ────────────────────────────────────────────────
# All windows are Monday-Saturday ranges, chosen to fall clear of every date in HOLIDAYS
# below (checked by hand against the list, and by test_student_world.py for the exam dates
# specifically).
ACADEMIC_CALENDAR = {
    "academic_year": "2026-27",
    "odd_term_start": "2026-07-13",
    "odd_term_teaching_weeks": 14,
    "odd_term_ia2_window": ("2026-10-26", "2026-11-03"),
    "odd_term_practical_window": ("2026-11-30", "2026-12-05"),
    "odd_term_theory_exam_window": ("2026-12-07", "2026-12-19"),
    "odd_term_result_date": "2026-12-24",
    "winter_vacation": ("2026-12-20", "2027-01-03"),
    "even_term_start": "2027-01-04",
    "even_term_teaching_weeks": 14,
    "even_term_ia2_window": ("2027-03-15", "2027-03-20"),
    "even_term_practical_window": ("2027-04-24", "2027-04-29"),
    "even_term_theory_exam_window": ("2027-05-03", "2027-05-15"),
    "even_term_result_date": "2027-06-05",
    "summer_vacation": ("2027-05-16", "2027-07-12"),
}


# ── holidays ──────────────────────────────────────────────────────────────────
# date (ISO), occasion, type (national / state / institutional / restricted).
#
# National and Maharashtra-state dates for 2026 and 2027 were checked against a live
# gazetted holiday calendar (officeholidays.com/countries/india/2026 and .../2027,
# checked 2026-08-29) rather than recalled from memory, because lunar-calendar festival
# dates shift every year and a wrong one would be exactly the kind of silent corpus error
# this world model exists to avoid. "KRIET Foundation Day" and "KRIET Annual Social
# Gathering" are the institute's own invented institutional holidays.
HOLIDAYS = [
    ("2026-08-15", "Independence Day", "national"),
    ("2026-09-14", "Ganesh Chaturthi", "state"),
    ("2026-09-21", "KRIET Foundation Day", "institutional"),
    ("2026-10-02", "Mahatma Gandhi Jayanti", "national"),
    ("2026-10-21", "Dussehra (Vijayadashami)", "state"),
    ("2026-11-08", "Diwali (Lakshmi Pujan)", "state"),
    ("2026-11-10", "Diwali Padwa (Bali Pratipada)", "state"),
    ("2026-11-11", "Bhaubeej (Bhai Dooj)", "state"),
    ("2026-11-24", "Guru Nanak Jayanti", "state"),
    ("2026-12-25", "Christmas", "restricted"),
    ("2027-01-26", "Republic Day", "national"),
    ("2027-02-19", "Chhatrapati Shivaji Maharaj Jayanti", "state"),
    ("2027-02-27", "KRIET Annual Social Gathering", "institutional"),
    ("2027-03-06", "Maha Shivratri", "restricted"),
    ("2027-03-09", "Eid-ul-Fitr", "restricted"),
    ("2027-03-22", "Holi", "state"),
    ("2027-04-07", "Gudi Padwa", "state"),
    ("2027-04-14", "Dr. Babasaheb Ambedkar Jayanti", "national"),
    ("2027-04-15", "Ram Navami", "state"),
    ("2027-05-01", "Maharashtra Din (Maharashtra Day)", "state"),
]


# ── notice log ────────────────────────────────────────────────────────────────
# Every notice this corpus refers to, numbered exactly once, in exactly one place, so a
# notice number printed on a rendered PDF (corpus/render_academic.py reads this dict) can
# never drift from the one a DEADLINES entry below points at. Format:
# {short code}/{section}/{academic year}/{sequence}.
NOTICE_LOG = {
    "academic_calendar": "KRIET/ACAD/2026-27/001",
    "timetable_comp_sem3": "KRIET/TT/2026-27/003",
    "timetable_comp_sem5": "KRIET/TT/2026-27/004",
    "timetable_extc_sem3": "KRIET/TT/2026-27/005",
    "timetable_extc_sem5": "KRIET/TT/2026-27/006",
    "exam_schedule_odd": "KRIET/EXAM/2026-27/014",
    "exam_ia2_notice": "KRIET/EXAM/2026-27/011",
    "exam_result_odd": "KRIET/EXAM/2026-27/018",
    "scholarship_postmatric": "KRIET/SCHOLAR/2026-27/002",
    "scholarship_shahu_merit": "KRIET/SCHOLAR/2026-27/003",
    "scholarship_shahu_ebc": "KRIET/SCHOLAR/2026-27/004",
    "scholarship_alumni_grant": "KRIET/SCHOLAR/2026-27/005",
    "scholarship_eligibility_matrix": "KRIET/SCHOLAR/2026-27/006",
    "scholarship_sports": "KRIET/SPORTS/2026-27/002",
    "sports_cultural_benefits": "KRIET/SPORTS/2026-27/003",
    "placement_ratnagiri_softworks": "KRIET/TPO/2026-27/006",
    "placement_sindhu_cloud": "KRIET/TPO/2026-27/011",
    "placement_policy": "KRIET/TPO/2026-27/012",
    "placement_konkangiri_analytics": "KRIET/TPO/2026-27/013",
    "placement_malvan_robotics": "KRIET/TPO/2026-27/014",
    "placement_vishalgad_power": "KRIET/TPO/2026-27/015",
    "placement_devbaug_structall": "KRIET/TPO/2026-27/016",
    "training_aws_bootcamp": "KRIET/TRAIN/2026-27/002",
    "training_programmes": "KRIET/TRAIN/2026-27/003",
    "incubation_cohort5": "KRIET/INCUB/2026-27/002",
    "library_fine_waiver": "KRIET/LIB/2026-27/001",
    "grievance_affidavit": "KRIET/GRIEV/2026-27/001",
    "spoc_directory": "KRIET/ADMIN/2026-27/001",
    # Below: notice board, handbook, events/holidays, attendance and deadline-tracker
    # documents rendered by corpus/render_notices.py.
    "fee_payment_odd": "KRIET/ACCT/2026-27/001",
    "id_card_reissue": "KRIET/GEN/2026-27/001",
    "bonafide_certificate": "KRIET/GEN/2026-27/002",
    "semester_registration_even": "KRIET/GEN/2026-27/003",
    "dress_code_reminder": "KRIET/GEN/2026-27/004",
    "hostel_allotment": "KRIET/HOSTEL/2026-27/001",
    "bus_pass_renewal": "KRIET/TRANS/2026-27/001",
    "convocation_registration": "KRIET/ACAD/2026-27/002",
    "student_handbook": "KRIET/HB/2026-27/001",
    "holiday_list": "KRIET/CAL/2026-27/001",
    "event_tech_fest": "KRIET/EVENT/2026-27/001",
    "event_cultural_fest": "KRIET/EVENT/2026-27/002",
    "event_sports_meet": "KRIET/EVENT/2026-27/003",
    "event_industry_visit": "KRIET/EVENT/2026-27/004",
    "event_guest_lecture": "KRIET/EVENT/2026-27/005",
    "event_alumni_meet": "KRIET/EVENT/2026-27/006",
    "event_nss_camp": "KRIET/EVENT/2026-27/007",
    "attendance_policy": "KRIET/ATT/2026-27/001",
    "attendance_defaulter_procedure": "KRIET/ATT/2026-27/002",
    "attendance_condonation": "KRIET/ATT/2026-27/003",
    "attendance_specimen_statement": "KRIET/ATT/2026-27/004",
    "deadline_tracker": "KRIET/DEADLINE/2026-27/001",
}


# ── weekly period timings, shared by every timetable ────────────────────────
# Six periods Monday-Friday; Saturday is a half day (P1-P4 only). A lab or a mini-project
# block occupies several consecutive periods at once (e.g. "P4-P6"); its time span is the
# start of the first period to the end of the last.
PERIOD_TIMES = {
    "P1": "09:15-10:15", "P2": "10:15-11:15", "P3": "11:15-12:15",
    # 12:15-12:45 recess
    "P4": "12:45-13:45", "P5": "13:45-14:45", "P6": "14:45-15:45",
}


# ── timetable ─────────────────────────────────────────────────────────────────
# dept code, semester, division, day, period(s), time slot, subject code, faculty
# person-key, room. Only semesters 3 and 5 are published this term: AY 2026-27's odd term
# (July-November 2026) is what is actually running, so only the odd semesters (3 and 5) of
# the four-year programme have a live timetable — semesters 4 and 6 exist in SUBJECTS (a
# student can still ask "what is semester 4 Computer Engineering") but do not get one until
# the even term starts in January 2027. This is a real property of any single-snapshot
# college calendar, not a gap in the corpus.
TIMETABLE = [
    # Computer Engineering, semester 3, Division A
    ("COMP", 3, "A", "Monday", "P1", "09:15-10:15", "CO301", "hod_comp", "CO-301"),
    ("COMP", 3, "A", "Monday", "P2", "10:15-11:15", "CO302", "antiragging_spoc", "CO-301"),
    ("COMP", 3, "A", "Monday", "P3", "11:15-12:15", "CO303", "fac_panse", "CO-301"),
    ("COMP", 3, "A", "Tuesday", "P1", "09:15-10:15", "CO304", "fac_oak", "CO-301"),
    ("COMP", 3, "A", "Tuesday", "P2", "10:15-11:15", "CO301", "hod_comp", "CO-301"),
    ("COMP", 3, "A", "Tuesday", "P3", "11:15-12:15", "CO302", "antiragging_spoc", "CO-301"),
    ("COMP", 3, "A", "Wednesday", "P4-P6", "12:45-15:45", "CO305", "fac_panse", "CO-Lab-1"),
    ("COMP", 3, "A", "Thursday", "P1", "09:15-10:15", "CO303", "fac_panse", "CO-301"),
    ("COMP", 3, "A", "Thursday", "P2", "10:15-11:15", "CO304", "fac_oak", "CO-301"),
    ("COMP", 3, "A", "Thursday", "P3", "11:15-12:15", "CO301", "hod_comp", "CO-301"),
    ("COMP", 3, "A", "Friday", "P1", "09:15-10:15", "CO302", "antiragging_spoc", "CO-301"),
    ("COMP", 3, "A", "Friday", "P2", "10:15-11:15", "CO303", "fac_panse", "CO-301"),
    ("COMP", 3, "A", "Friday", "P3", "11:15-12:15", "CO304", "fac_oak", "CO-301"),
    ("COMP", 3, "A", "Saturday", "P1-P3", "09:15-12:15", "CO306", "fac_oak", "CO-Lab-2"),

    # Computer Engineering, semester 5, Division A
    ("COMP", 5, "A", "Monday", "P4", "12:45-13:45", "CO501", "hod_comp", "CO-301"),
    ("COMP", 5, "A", "Monday", "P5", "13:45-14:45", "CO502", "fac_panse", "CO-301"),
    ("COMP", 5, "A", "Tuesday", "P4", "12:45-13:45", "CO503", "fac_oak", "CO-301"),
    ("COMP", 5, "A", "Tuesday", "P5", "13:45-14:45", "CO504", "fac_ketkar", "CO-301"),
    ("COMP", 5, "A", "Wednesday", "P1", "09:15-10:15", "CO501", "hod_comp", "CO-301"),
    ("COMP", 5, "A", "Wednesday", "P2", "10:15-11:15", "CO502", "fac_panse", "CO-301"),
    ("COMP", 5, "A", "Wednesday", "P4-P6", "12:45-15:45", "CO405", "fac_oak", "CO-Lab-2"),
    ("COMP", 5, "A", "Thursday", "P4", "12:45-13:45", "CO503", "fac_oak", "CO-301"),
    ("COMP", 5, "A", "Thursday", "P5", "13:45-14:45", "CO504", "fac_ketkar", "CO-301"),
    ("COMP", 5, "A", "Friday", "P1", "09:15-10:15", "CO501", "hod_comp", "CO-301"),
    ("COMP", 5, "A", "Friday", "P2", "10:15-11:15", "CO502", "fac_panse", "CO-301"),
    ("COMP", 5, "A", "Friday", "P4", "12:45-13:45", "CO503", "fac_oak", "CO-301"),
    ("COMP", 5, "A", "Friday", "P5", "13:45-14:45", "CO504", "fac_ketkar", "CO-301"),
    ("COMP", 5, "A", "Saturday", "P1-P2", "09:15-11:15", "CO506", "antiragging_spoc", "CO-Lab-1"),

    # Electronics and Telecommunication, semester 3, Division A
    ("EXTC", 3, "A", "Monday", "P1", "09:15-10:15", "EC301", "hod_extc", "EC-201"),
    ("EXTC", 3, "A", "Monday", "P2", "10:15-11:15", "EC302", "fac_bapat", "EC-201"),
    ("EXTC", 3, "A", "Monday", "P3", "11:15-12:15", "EC303", "grievance_chair", "EC-201"),
    ("EXTC", 3, "A", "Tuesday", "P1", "09:15-10:15", "EC304", "fac_puranik", "EC-201"),
    ("EXTC", 3, "A", "Tuesday", "P2", "10:15-11:15", "EC301", "hod_extc", "EC-201"),
    ("EXTC", 3, "A", "Tuesday", "P3", "11:15-12:15", "EC302", "fac_bapat", "EC-201"),
    ("EXTC", 3, "A", "Wednesday", "P1", "09:15-10:15", "EC303", "grievance_chair", "EC-201"),
    ("EXTC", 3, "A", "Wednesday", "P2", "10:15-11:15", "EC304", "fac_puranik", "EC-201"),
    ("EXTC", 3, "A", "Thursday", "P4-P6", "12:45-15:45", "EC305", "fac_bapat", "EC-Lab-1"),
    ("EXTC", 3, "A", "Friday", "P1", "09:15-10:15", "EC301", "hod_extc", "EC-201"),
    ("EXTC", 3, "A", "Friday", "P2", "10:15-11:15", "EC304", "fac_puranik", "EC-201"),
    ("EXTC", 3, "A", "Friday", "P3", "11:15-12:15", "EC302", "fac_bapat", "EC-201"),
    ("EXTC", 3, "A", "Friday", "P4", "12:45-13:45", "EC303", "grievance_chair", "EC-201"),
    ("EXTC", 3, "A", "Saturday", "P1-P3", "09:15-12:15", "EC306", "grievance_chair", "EC-Lab-2"),

    # Electronics and Telecommunication, semester 5, Division A
    ("EXTC", 5, "A", "Monday", "P4", "12:45-13:45", "EC501", "hod_extc", "EC-201"),
    ("EXTC", 5, "A", "Monday", "P5", "13:45-14:45", "EC502", "grievance_chair", "EC-201"),
    ("EXTC", 5, "A", "Tuesday", "P4", "12:45-13:45", "EC503", "fac_bapat", "EC-201"),
    ("EXTC", 5, "A", "Tuesday", "P5", "13:45-14:45", "EC504", "fac_devrukhkar", "EC-201"),
    ("EXTC", 5, "A", "Wednesday", "P1", "09:15-10:15", "EC501", "hod_extc", "EC-201"),
    ("EXTC", 5, "A", "Wednesday", "P2", "10:15-11:15", "EC502", "grievance_chair", "EC-201"),
    ("EXTC", 5, "A", "Thursday", "P1", "09:15-10:15", "EC503", "fac_bapat", "EC-201"),
    ("EXTC", 5, "A", "Thursday", "P2", "10:15-11:15", "EC504", "fac_devrukhkar", "EC-201"),
    ("EXTC", 5, "A", "Thursday", "P4-P6", "12:45-15:45", "EC505", "grievance_chair", "EC-Lab-1"),
    ("EXTC", 5, "A", "Friday", "P1", "09:15-10:15", "EC501", "hod_extc", "EC-201"),
    ("EXTC", 5, "A", "Friday", "P2", "10:15-11:15", "EC502", "grievance_chair", "EC-201"),
    ("EXTC", 5, "A", "Friday", "P4", "12:45-13:45", "EC503", "fac_bapat", "EC-201"),
    ("EXTC", 5, "A", "Friday", "P5", "13:45-14:45", "EC504", "fac_devrukhkar", "EC-201"),
    ("EXTC", 5, "A", "Saturday", "P1-P2", "09:15-11:15", "EC506", "fac_puranik", "EC-Lab-2"),
]


# ── exam schedule ─────────────────────────────────────────────────────────────
# subject code, exam type, date (ISO), session (FN/AN), duration, venue, seating block.
#
# Only Internal Assessment II and the practical/end-semester exams that follow it are
# modeled — IA-I is continuous in-class assessment that the department runs informally and
# never gets its own institute-wide notice, unlike IA-II which is formally scheduled. Every
# theory subject taught this term (semester 3 and 5, COMP and EXTC — the ones in TIMETABLE)
# gets an Internal Assessment II slot and an End-Semester Theory slot; every lab subject
# gets a Practical Examination slot instead of a written paper.
EXAM_SCHEDULE = [
    # Internal Assessment II — within ACADEMIC_CALENDAR["odd_term_ia2_window"]
    ("CO301", "Internal Assessment II", "2026-10-26", "FN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("CO302", "Internal Assessment II", "2026-10-26", "AN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("CO303", "Internal Assessment II", "2026-10-27", "FN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("CO304", "Internal Assessment II", "2026-10-27", "AN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("CO501", "Internal Assessment II", "2026-10-28", "FN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("CO502", "Internal Assessment II", "2026-10-28", "AN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("CO503", "Internal Assessment II", "2026-10-29", "FN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("CO504", "Internal Assessment II", "2026-10-29", "AN", "1 hour", "CO-301", "Roll-number order, alternate seating"),
    ("EC301", "Internal Assessment II", "2026-10-30", "FN", "1 hour", "EC-201", "Roll-number order, alternate seating"),
    ("EC302", "Internal Assessment II", "2026-10-30", "AN", "1 hour", "EC-201", "Roll-number order, alternate seating"),
    ("EC303", "Internal Assessment II", "2026-10-31", "FN", "1 hour", "EC-201", "Roll-number order, alternate seating"),
    ("EC304", "Internal Assessment II", "2026-10-31", "AN", "1 hour", "EC-201", "Roll-number order, alternate seating"),
    ("EC501", "Internal Assessment II", "2026-11-02", "FN", "1 hour", "EC-201", "Roll-number order, alternate seating"),
    ("EC502", "Internal Assessment II", "2026-11-02", "AN", "1 hour", "EC-201", "Roll-number order, alternate seating"),
    ("EC503", "Internal Assessment II", "2026-11-03", "FN", "1 hour", "EC-201", "Roll-number order, alternate seating"),
    ("EC504", "Internal Assessment II", "2026-11-03", "AN", "1 hour", "EC-201", "Roll-number order, alternate seating"),

    # Practical Examination — within ACADEMIC_CALENDAR["odd_term_practical_window"]
    ("CO305", "Practical Examination", "2026-11-30", "FN", "2 hours", "CO-Lab-1", "Batch-wise, 3 batches of 20"),
    ("CO306", "Practical Examination", "2026-11-30", "AN", "2 hours", "CO-Lab-2", "Batch-wise, 3 batches of 20"),
    ("CO405", "Practical Examination", "2026-12-01", "FN", "2 hours", "CO-Lab-2", "Batch-wise, 3 batches of 20"),
    ("CO506", "Practical Examination", "2026-12-01", "AN", "2 hours", "CO-Lab-1", "Batch-wise, 3 batches of 20"),
    ("EC305", "Practical Examination", "2026-12-02", "FN", "2 hours", "EC-Lab-1", "Batch-wise, 2 batches of 15"),
    ("EC306", "Practical Examination", "2026-12-02", "AN", "2 hours", "EC-Lab-2", "Batch-wise, 2 batches of 15"),
    ("EC505", "Practical Examination", "2026-12-03", "FN", "2 hours", "EC-Lab-1", "Batch-wise, 2 batches of 15"),
    ("EC506", "Practical Examination", "2026-12-03", "AN", "2 hours", "EC-Lab-2", "Batch-wise, 2 batches of 15"),

    # End-Semester Theory — within ACADEMIC_CALENDAR["odd_term_theory_exam_window"]
    ("CO301", "End-Semester Theory", "2026-12-07", "FN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("CO302", "End-Semester Theory", "2026-12-07", "AN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("CO303", "End-Semester Theory", "2026-12-08", "FN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("CO304", "End-Semester Theory", "2026-12-08", "AN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("CO501", "End-Semester Theory", "2026-12-09", "FN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("CO502", "End-Semester Theory", "2026-12-09", "AN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("CO503", "End-Semester Theory", "2026-12-10", "FN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("CO504", "End-Semester Theory", "2026-12-10", "AN", "3 hours", "Block D, Ground Floor", "Rows 1-20, Computer Engineering Division A"),
    ("EC301", "End-Semester Theory", "2026-12-14", "FN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
    ("EC302", "End-Semester Theory", "2026-12-14", "AN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
    ("EC303", "End-Semester Theory", "2026-12-15", "FN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
    ("EC304", "End-Semester Theory", "2026-12-15", "AN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
    ("EC501", "End-Semester Theory", "2026-12-16", "FN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
    ("EC502", "End-Semester Theory", "2026-12-16", "AN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
    ("EC503", "End-Semester Theory", "2026-12-17", "FN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
    ("EC504", "End-Semester Theory", "2026-12-17", "AN", "3 hours", "Block D, Ground Floor", "Rows 21-30, EXTC Division A"),
]

EXAM_SESSION_TIMES = {
    "Internal Assessment II": {"FN": "10:00-11:00", "AN": "14:00-15:00"},
    "Practical Examination": {"FN": "09:30-11:30", "AN": "13:00-15:00"},
    "End-Semester Theory": {"FN": "10:30-13:30", "AN": "14:30-17:30"},
}


# ── scholarship and benefit schemes ──────────────────────────────────────────
# This is the decision-support core: a student must be able to determine eligibility from
# this table alone, without needing to phone the Accounts Section.
#
# Deliberate distractor: "Rajarshi Shahu Maharaj Merit Scholarship" and "Rajarshi Shahu
# Maharaj Freeship for EBC" share three of five words in their name but carry different
# income ceilings (Rs. 8,00,000 vs Rs. 1,00,000) — a retriever that matches on the shared
# words and stops there will pick the wrong ceiling.
#
# Second deliberate distractor: the Sports and Cultural Excellence Scholarship requires only
# 70% attendance, against the 75% general minimum in ATTENDANCE_POLICY below — an answer
# that quotes the general policy for a sports-quota student is wrong.
SCHEMES = [
    {
        "name": "Post-Matric Scholarship for SC/ST Students",
        "level": "central",
        "category_eligible": "SC, ST",
        "income_ceiling": 250000,
        "min_attendance_pct": 75,
        "min_cgpa": None,
        "documents_required": [
            "Caste certificate", "Income certificate (current financial year)",
            "Aadhaar card", "Previous year statement of marks",
            "Bank passbook copy (Aadhaar-linked account)",
        ],
        "application_window": "1 September 2026 to 31 October 2026",
        "spoc": "scholarship_spoc",
        "disbursement_timeline": (
            "Within 90 days of application window closure, credited by DBT to the "
            "student's Aadhaar-linked bank account"
        ),
        "portal": "National Scholarship Portal - scholarships.gov.in",
        "notice_key": "scholarship_postmatric",
        "application_steps": [
            "Register or log in on the National Scholarship Portal (scholarships.gov.in) "
            "using your Aadhaar number",
            "Select \"Post-Matric Scholarship for SC/ST Students\" and fill the institute, "
            "course and bank-account details",
            "Upload scanned copies of the caste certificate, income certificate, Aadhaar "
            "card, previous year statement of marks and the Aadhaar-linked bank passbook",
            "Submit the online application before the window closes on 31 October 2026",
            "Print the system-generated acknowledgement and hand a signed copy to the "
            "Scholarship SPOC (Accounts Section) for institute-level verification",
        ],
        "after_submission": (
            "The institute verifies the uploaded documents against originals and forwards "
            "the application to the Directorate of Technical Education within 15 days of "
            "the window closing; the student can track status on the portal using the "
            "application ID, and disbursement follows within 90 days of closure by DBT"
        ),
    },
    {
        "name": "Rajarshi Shahu Maharaj Merit Scholarship",
        "level": "state",
        "category_eligible": "SC, ST, OBC, VJNT, SBC",
        "income_ceiling": 800000,
        "min_attendance_pct": 75,
        "min_cgpa": 6.50,
        "documents_required": [
            "Caste certificate", "Income certificate (current financial year)",
            "Domicile certificate", "Previous semester marksheet",
        ],
        "application_window": "1 September 2026 to 15 October 2026",
        "spoc": "scholarship_spoc",
        "disbursement_timeline": "Within 120 days of application window closure, via Maha DBT",
        "portal": "Maha DBT Portal - mahadbt.maharashtra.gov.in",
        "notice_key": "scholarship_shahu_merit",
        "application_steps": [
            "Log in to the Maha DBT Portal (mahadbt.maharashtra.gov.in) with your student "
            "credentials",
            "Select \"Rajarshi Shahu Maharaj Merit Scholarship\" under the DBATU / KRIET "
            "institute list",
            "Enter the previous semester's CGPA (a scanned marksheet must support at least "
            "6.50) and category details",
            "Upload the caste certificate, income certificate, domicile certificate and "
            "previous semester marksheet",
            "Submit before 15 October 2026 and note the application reference number",
        ],
        "after_submission": (
            "The Scholarship SPOC cross-checks the uploaded CGPA and income certificate "
            "against institute records within 10 working days; an application failing "
            "verification is returned on the portal for resubmission, and an accepted "
            "application is disbursed via Maha DBT within 120 days of closure"
        ),
    },
    {
        "name": "Rajarshi Shahu Maharaj Freeship for EBC",
        "level": "state",
        "category_eligible": "Economically Backward Class (open category, non-creamy layer)",
        "income_ceiling": 100000,
        "min_attendance_pct": 75,
        "min_cgpa": None,
        "documents_required": [
            "Income certificate (current financial year, below Rs. 1,00,000)",
            "Non-creamy-layer declaration", "Domicile certificate",
            "Previous semester marksheet",
        ],
        "application_window": "1 September 2026 to 15 October 2026",
        "spoc": "scholarship_spoc",
        "disbursement_timeline": "Within 120 days of application window closure, via Maha DBT",
        "portal": "Maha DBT Portal - mahadbt.maharashtra.gov.in",
        "notice_key": "scholarship_shahu_ebc",
        "application_steps": [
            "Log in to the Maha DBT Portal (mahadbt.maharashtra.gov.in) with your student "
            "credentials",
            "Select \"Rajarshi Shahu Maharaj Freeship for EBC\" under the DBATU / KRIET "
            "institute list",
            "Upload the income certificate showing income below Rs. 1,00,000, the "
            "non-creamy-layer declaration, domicile certificate and previous semester "
            "marksheet",
            "Submit before 15 October 2026 and note the application reference number",
        ],
        "after_submission": (
            "The Scholarship SPOC verifies the income certificate and non-creamy-layer "
            "declaration against institute records within 10 working days; a discrepancy is "
            "returned on the portal for resubmission, and an accepted application is "
            "disbursed via Maha DBT within 120 days of closure"
        ),
    },
    {
        "name": "KRIET Alumni Merit Grant",
        "level": "institutional",
        "category_eligible": "All categories, merit-based",
        "income_ceiling": None,
        "min_attendance_pct": 80,
        "min_cgpa": 8.50,
        "documents_required": [
            "Previous two semester marksheets", "No-backlog declaration",
        ],
        "application_window": "1 September 2026 to 20 September 2026",
        "spoc": "scholarship_spoc",
        "disbursement_timeline": (
            "Credited to the tuition-fee ledger before the following semester's fee due date"
        ),
        "portal": "Internal - apply in person at the Accounts Section",
        "notice_key": "scholarship_alumni_grant",
        "application_steps": [
            "Collect the KRIET Alumni Merit Grant application form from the Accounts "
            "Section (Admin Building, Room 106) or download it from the institute website",
            "Attach the previous two semester marksheets (CGPA must be at least 8.50) and a "
            "signed no-backlog declaration",
            "Submit the form in person to the Scholarship SPOC between 1 and 20 September "
            "2026 and collect a dated acknowledgement slip",
        ],
        "after_submission": (
            "The Scholarship SPOC verifies the CGPA and no-backlog declaration against "
            "examination records within 10 working days; the grant is credited directly to "
            "the tuition-fee ledger before the following semester's fee due date, so no "
            "separate disbursement step is needed"
        ),
    },
    {
        "name": "Sports and Cultural Excellence Scholarship",
        "level": "sports and cultural",
        "category_eligible": "Students with state or national level representation in sports or cultural events",
        "income_ceiling": None,
        "min_attendance_pct": 70,
        "min_cgpa": None,
        "documents_required": [
            "Selection or participation certificate from the state or national association",
            "No-backlog declaration",
        ],
        "application_window": "1 September 2026 to 30 September 2026",
        "spoc": "sports_officer",
        "disbursement_timeline": "Within 60 days of application window closure",
        "portal": "Internal - apply at the Sports Complex Office",
        "notice_key": "scholarship_sports",
        "application_steps": [
            "Collect the Sports and Cultural Excellence Scholarship form from the Sports "
            "Complex Office",
            "Attach the selection or participation certificate from the state or national "
            "association and a signed no-backlog declaration",
            "Submit the form in person to the Sports Officer between 1 and 30 September "
            "2026 and collect a dated acknowledgement slip",
        ],
        "after_submission": (
            "The Sports Officer verifies the participation certificate with the concerned "
            "association within 10 working days and forwards the verified list to the "
            "Accounts Section; disbursement follows within 60 days of the application "
            "window closing"
        ),
    },
]


# ── attendance policy ─────────────────────────────────────────────────────────
ATTENDANCE_POLICY = {
    "min_attendance_pct": 75,
    "condonation_band_low_pct": 65,   # 65-75%: condonable with documentation
    "condonation_band_high_pct": 75,
    "condonation_max_grant_pct": 10,  # condonation cannot raise counted attendance by more than this
    "debarment_threshold_pct": 65,    # below this: debarred outright, no condonation possible
    "medical_leave_max_days": 15,
    "medical_leave_doc": (
        "Certificate from a Registered Medical Practitioner, submitted within 7 days of "
        "resuming classes"
    ),
    "committee": "Attendance Review Committee",
    "committee_chair_key": "attendance_coordinator",
    "appeal_route": (
        "Written appeal to the Principal within 7 working days of the defaulter list "
        "being published on the notice board"
    ),
}


# ── sports and cultural benefits ─────────────────────────────────────────────
# A separate, broader set of benefits from the Sports and Cultural Excellence Scholarship
# in SCHEMES above: this covers the admission quota, the attendance relaxation for
# representing the institute, prize money and the annual kit allowance. The attendance
# relaxation figure below is the same 70% used by the SCHEMES sports scholarship entry — one
# underlying institute rule, referenced from two documents — and is deliberately different
# from ATTENDANCE_POLICY["min_attendance_pct"] (75%): a student who represents KRIET is held
# to a different, lower bar, and an answer that quotes the general policy for such a student
# is wrong.
SPORTS_CULTURAL_BENEFITS = {
    "notice_key": "sports_cultural_benefits",
    "quota_seats_per_department": 2,
    "quota_description": (
        "2 supernumerary seats per department each admission year, reserved for candidates "
        "holding a state or national level sports/cultural representation certificate, over "
        "and above the sanctioned intake in DEPARTMENTS"
    ),
    "attendance_relaxation_pct": 70,
    "on_duty_provision": (
        "Days spent representing KRIET at a recognised state or national sports or cultural "
        "event are marked \"On Duty\" and counted as present for attendance purposes, on "
        "production of the participation proof below, provided the student's counted "
        "attendance does not fall below 70%"
    ),
    # level, position, prize money (Rs.)
    "prize_money": [
        ("State", "1st", 5000),
        ("State", "2nd", 3000),
        ("National", "1st", 15000),
        ("National", "2nd", 8000),
    ],
    "kit_allowance_per_year": 3000,
    "eligibility_proof_required": [
        "Selection letter or participation certificate from the state or national sports or "
        "cultural association",
        "Event result sheet or proof of position for a prize money claim",
        "Countersignature of the Sports Officer within 3 days of returning to campus",
    ],
    "spoc_key": "sports_officer",
}


# ── placements ────────────────────────────────────────────────────────────────
# company, role, package (LPA), minimum CGPA, maximum live backlogs allowed, branches
# eligible (department codes), registration deadline (ISO), drive date (ISO), SPOC,
# selection rounds (in order).
PLACEMENTS = [
    ("Ratnagiri Softworks", "Software Engineer Trainee", 6.2, 7.0, 0,
     ["COMP", "IT"], "2026-09-08", "2026-09-16", "tpo",
     ["Online Aptitude Test", "Technical Interview", "HR Interview"]),
    ("Konkangiri Analytics", "Data Analyst", 5.8, 7.2, 0,
     ["COMP", "IT"], "2026-09-22", "2026-09-30", "tpo",
     ["Online Aptitude Test", "Case Study Round", "Technical Interview", "HR Interview"]),
    ("Malvan Robotics Pvt Ltd", "Graduate Engineer Trainee", 4.5, 6.5, 1,
     ["MECH", "EXTC"], "2026-10-06", "2026-10-14", "tpo",
     ["Written Test", "Group Discussion", "Technical Interview", "HR Interview"]),
    ("Vishalgad Power Systems", "Graduate Engineer Trainee (Electronics)", 4.2, 6.2, 2,
     ["EXTC"], "2026-10-20", "2026-10-28", "tpo",
     ["Written Test", "Technical Interview", "HR Interview"]),
    ("Devbaug Structall Engineers", "Site Engineer", 4.0, 6.0, 2,
     ["CIVIL"], "2026-11-03", "2026-11-12", "tpo",
     ["Written Test", "Technical Interview", "HR Interview"]),
    ("Sindhu Cloud Systems", "Cloud Support Engineer", 7.5, 7.5, 0,
     ["COMP", "IT", "EXTC"], "2026-11-17", "2026-11-25", "tpo",
     ["Online Aptitude Test", "Technical Interview", "Managerial Interview", "HR Interview"]),
]


# ── placement policy ─────────────────────────────────────────────────────────
PLACEMENT_POLICY = {
    "registration_rule": (
        "Every student in the final and pre-final year must register once with the "
        "Placement Cell at the start of the placement season (online form circulated by the "
        "TPO) before appearing for any company drive; a student who has not registered is "
        "not permitted to sit for any drive that season"
    ),
    "one_offer_rule": (
        "A student who accepts an offer, verbally at the drive or in writing, is placed and "
        "is withdrawn from all subsequent drives for that academic year; sitting for a "
        "further drive after accepting an offer requires a written no-objection from the "
        "TPO, granted only for a package at least 50% higher than the accepted offer"
    ),
    "backlog_policy": (
        "The maximum live backlog count for each drive is set by the recruiting company "
        "(see the per-drive cut-off in PLACEMENTS); a student with more live backlogs than "
        "a drive's stated limit is not permitted to register for that specific drive "
        "regardless of CGPA"
    ),
    "dress_code": (
        "Formal business attire is mandatory for every drive: full-sleeve shirt and formal "
        "trousers for men, formal Indian or Western business wear for women; jeans, "
        "T-shirts and sportswear are not permitted"
    ),
    "absenteeism_penalty": (
        "A registered student who does not report for a drive without informing the "
        "Placement Cell at least 24 hours in advance is debarred from the next two drives"
    ),
    "spoc_key": "tpo",
}


# ── training programmes ───────────────────────────────────────────────────────
# programme, provider, duration, fee (Rs.), eligibility, certification, coordinator,
# schedule, how to enrol.
TRAINING = [
    ("AWS Cloud Practitioner Certification Bootcamp", "Konkan Skill Academy",
     "6 weeks (weekend batches)", 4500,
     "Semester 5 or above; Computer Engineering, Information Technology or EXTC",
     "AWS Cloud Practitioner voucher exam (external, additional Rs. 3,300)", "tpo",
     "Saturday and Sunday, 10:00-13:00, starting 26 September 2026",
     "Submit the enrolment form circulated by the TPO office by 20 September 2026; seats "
     "are confirmed on a first-come basis and the fee is payable at the Accounts Section "
     "on confirmation"),
    ("Full-Stack Web Development (MERN)", "Vidyasagar Career Foundation",
     "8 weeks", 6000, "Semester 4 or above; any branch",
     "Course completion certificate; optional proctored MERN assessment", "tpo",
     "Weekday evenings, Monday-Thursday 17:00-19:00, starting 5 October 2026",
     "Register at the TPO office with the fee receipt; each batch is capped between 20 and "
     "40 students and closes once full"),
    ("Embedded Systems and IoT Workshop", "Ratnagiri Institute of Advanced Computing",
     "2 weeks (intensive)", 2500, "EXTC, semester 5 or 6",
     "Workshop completion certificate", "hod_extc",
     "Daily 09:30-16:30, starting 12 January 2027 (winter vacation slot)",
     "Register with the EXTC HOD's office; departmental priority is given to semester 6 "
     "students"),
]


# ── incubation centre ─────────────────────────────────────────────────────────
INCUBATION = {
    "name": "Ratnaditya Innovation and Incubation Centre",
    "established": 2022,
    "manager_key": "incubation_manager",
    "offers": [
        "Free co-working desk space for 6 months",
        "Seed funding up to Rs. 2,00,000 (see funding tiers)",
        "Mentorship from an industry panel",
        "Access to the prototyping lab",
        "Patent filing support (50% fee reimbursement)",
    ],
    # tier name, amount (Rs.), stage required
    "funding_tiers": [
        ("Ideation Grant", 15000, "proof-of-concept stage"),
        ("Prototype Grant", 75000, "working prototype stage"),
        ("Seed Grant", 200000, "market-ready product with at least 3 letters of intent"),
    ],
    "application_procedure": (
        "Submit a 2-page concept note to the Incubation Manager, followed by a pitch to "
        "the Institute Innovation Council within 15 days of submission"
    ),
    "mentors": [
        "Dr. Abhijit Naik (Incubation Manager)",
        "Dr. Manasi Kadam (HOD, Computer Engineering)",
        # Cross-reference: this mentor is the founder of a company that also runs a
        # placement drive on campus (see PLACEMENTS) — the same person shows up for two
        # unrelated reasons, exactly as real small colleges' rosters do.
        "Mr. Kunal Devrukhkar, Founder, Konkangiri Analytics (external mentor)",
    ],
    # startup, description, tier reached
    "cohort_2026": [
        ("AgroSense Technologies", "IoT soil-moisture sensors for Konkan cashew farms",
         "Prototype Grant recipient"),
        ("MedTrack Health", "Offline-first patient record app for rural clinics",
         "Seed Grant recipient"),
        ("EcoPack Konkan", "Biodegradable packaging from areca-leaf waste",
         "Ideation Grant recipient"),
    ],
    "application_deadline_cohort5": "2026-09-30",
    "application_deadline_cohort6": "2027-02-15",
    "ip_policy": (
        "Intellectual property generated using Incubation Centre resources (co-working "
        "space, prototyping lab, seed funding) is jointly owned by the student founder(s) "
        "and KRIET in a 70:30 (founder:institute) split; the institute waives its share in "
        "full if the founder(s) exit the incubation programme before availing any seed "
        "funding tier"
    ),
    "seed_grant_terms": (
        "The Seed Grant (Rs. 2,00,000) is released in two tranches: 60% on sanction and 40% "
        "on a milestone review conducted 6 months after sanction; the institute takes no "
        "equity in exchange for any funding tier; recipients must submit a utilisation "
        "certificate to the Incubation Manager within 12 months of the first tranche and "
        "acknowledge the Ratnaditya Innovation and Incubation Centre in any resulting "
        "patent filing, publication or investor deck"
    ),
}


# ── library ───────────────────────────────────────────────────────────────────
LIBRARY = {
    "name": "Central Library, KRIET",
    "librarian_key": "librarian",
    "titles": 32500,
    "journals_print": 42,
    "e_resources": [
        "DELNET", "NPTEL Local Chapter Repository",
        "IEEE Xplore (departmental subscription: Computer Engineering and EXTC only)",
    ],
    "weekday_hours": "08:30 to 20:00",
    "saturday_hours": "09:00 to 17:00",
    "sunday_hours": "Closed",
    # category -> (books allowed, loan days)
    "loan_limits": {
        "UG student": (3, 14),
        "PG student": (5, 14),
        "faculty": (8, 90),
    },
    "fine_per_day": 2,
    "max_fine_per_book": 200,
    "reservation_rule": (
        "A book already on loan may be reserved online; the current holder is notified "
        "and must return it within 3 days of the reservation request"
    ),
    "reference_section_loanable": False,
    "overdue_notice": (
        "SMS/email reminder sent 2 days before the due date, and a second reminder on "
        "the due date itself"
    ),
    "renewal_rule": (
        "A book may be renewed once for the same loan period, in person or online, provided "
        "it is not on hold for another reader"
    ),
    "remote_access": (
        "DELNET and the NPTEL Local Chapter Repository are reachable off-campus via "
        "institute single sign-on (roll number and library password) at "
        "remote.kriet.ac.in; IEEE Xplore is IP-restricted to the campus network under the "
        "departmental subscription and has no remote-access option"
    ),
    "lost_book_policy": (
        "The reader pays the current market price of the title plus a Rs. 200 processing "
        "fee, or replaces it with a fresh copy of the same edition approved by the "
        "Librarian, within 30 days of the loss being reported"
    ),
}


# ── grievance and anti-ragging ────────────────────────────────────────────────
GRIEVANCE = {
    "committee_name": "Student Grievance Redressal Committee",
    "chair_key": "grievance_chair",
    "member_keys": ["grievance_chair", "attendance_coordinator", "antiragging_spoc", "hod_comp", "hod_extc"],
    "categories": [
        "Academic (marks, attendance, timetable)",
        "Administrative (fees, certificates, records)",
        "Harassment or discrimination",
        "Ragging",
        "Facilities",
    ],
    # level, description, timeline
    "escalation": [
        ("Level 1", "Raised with the concerned Head of Department", "5 working days"),
        ("Level 2", "Student Grievance Redressal Committee", "10 working days from Level 1"),
        ("Level 3", "Principal — final institute-level appeal", "7 working days from Level 2"),
        ("Level 4", "DBATU Ombudsperson / University Grievance Cell (external)",
         "as per University regulations"),
    ],
    "portal": "https://grievance.kriet.ac.in",
    "anti_ragging": {
        "spoc_key": "antiragging_spoc",
        "committee": "Anti-Ragging Committee and Anti-Ragging Squad (per UGC Regulations, 2009)",
        # Checked live against antiragging.in (2026-08-29): "24x7 Toll Free Number
        # 1800-180-5522, helpline@antiragging.in".
        "ugc_helpline": "1800-180-5522 (UGC Anti-Ragging Helpline, toll-free, 24x7)",
        "ugc_helpline_email": "helpline@antiragging.in",
        "affidavit_requirement": (
            "Every student and every parent/guardian must submit an anti-ragging "
            "affidavit on the UGC portal (www.antiragging.in) at the start of each "
            "academic year"
        ),
    },
}


# ── SPOC directory ────────────────────────────────────────────────────────────
# task (a plain-English "who do I contact for X" line) -> person-key. This is the
# consolidated cross-reference the grievance/contacts notice renders as a directory table —
# the answer to "who do I contact for X" a student should never have to derive by phoning
# around. Every PEOPLE entry that carries a role (i.e. every SPOC, as opposed to the
# subject-teaching-only faculty whose "role" is None) appears here exactly once; this is
# checked by tests/test_student_corpus_services.py rather than by hand, since a role added
# to PEOPLE without a matching directory row is exactly the kind of silent gap this table
# exists to prevent.
SPOC_DIRECTORY = [
    ("General administration and correspondence", "principal"),
    ("Registration, certificates, transcripts and official records", "registrar"),
    ("Computer Engineering department matters", "hod_comp"),
    ("Electronics and Telecommunication Engineering department matters", "hod_extc"),
    ("Mechanical Engineering department matters", "hod_mech"),
    ("Civil Engineering department matters", "hod_civil"),
    ("Information Technology department matters", "hod_it"),
    ("Scholarship and fee-waiver applications", "scholarship_spoc"),
    ("Placement registration and company drives", "tpo"),
    ("Grievance escalation (Level 2 committee)", "grievance_chair"),
    ("Anti-ragging complaints and affidavit queries", "antiragging_spoc"),
    ("Library services, fines and digital access", "librarian"),
    ("Sports quota, kit allowance and prize money", "sports_officer"),
    ("Incubation centre applications and seed funding", "incubation_manager"),
    ("Examination schedule, hall tickets and results", "exam_controller"),
    ("Attendance shortage and condonation appeals", "attendance_coordinator"),
    # The six entries below back the fee/ID-card/hostel/transport/activities/NSS SPOCs
    # added alongside corpus/render_notices.py; kept here so this directory stays the one
    # complete "who do I contact for X" answer regardless of which renderer a role serves.
    ("Fee payment and accounts queries", "accounts_spoc"),
    ("ID cards, certificates and semester registration", "student_section_spoc"),
    ("Hostel allotment and hostel rules", "hostel_warden"),
    ("Bus pass issue and renewal", "transport_incharge"),
    ("Student activities, fests and campus events", "student_activities_officer"),
    ("NSS enrolment and camps", "nss_officer"),
]


# ── consolidated deadlines ────────────────────────────────────────────────────
# item, date (ISO), owning SPOC person-key, the notice number that announced it (resolved
# from NOTICE_LOG above, so it can never drift from what a rendered notice actually prints).
DEADLINES = [
    ("Post-Matric Scholarship (SC/ST) application window closes", "2026-10-31",
     "scholarship_spoc", NOTICE_LOG["scholarship_postmatric"]),
    ("Rajarshi Shahu Maharaj Merit Scholarship application window closes", "2026-10-15",
     "scholarship_spoc", NOTICE_LOG["scholarship_shahu_merit"]),
    ("Rajarshi Shahu Maharaj Freeship for EBC application window closes", "2026-10-15",
     "scholarship_spoc", NOTICE_LOG["scholarship_shahu_ebc"]),
    ("KRIET Alumni Merit Grant application window closes", "2026-09-20",
     "scholarship_spoc", NOTICE_LOG["scholarship_alumni_grant"]),
    ("Sports and Cultural Excellence Scholarship application window closes", "2026-09-30",
     "sports_officer", NOTICE_LOG["scholarship_sports"]),
    ("Ratnagiri Softworks placement registration closes", "2026-09-08",
     "tpo", NOTICE_LOG["placement_ratnagiri_softworks"]),
    ("Konkangiri Analytics placement registration closes", "2026-09-22",
     "tpo", NOTICE_LOG["placement_konkangiri_analytics"]),
    ("Malvan Robotics Pvt Ltd placement registration closes", "2026-10-06",
     "tpo", NOTICE_LOG["placement_malvan_robotics"]),
    ("Vishalgad Power Systems placement registration closes", "2026-10-20",
     "tpo", NOTICE_LOG["placement_vishalgad_power"]),
    ("Devbaug Structall Engineers placement registration closes", "2026-11-03",
     "tpo", NOTICE_LOG["placement_devbaug_structall"]),
    ("Sindhu Cloud Systems placement registration closes", "2026-11-17",
     "tpo", NOTICE_LOG["placement_sindhu_cloud"]),
    ("AWS Cloud Practitioner Bootcamp enrollment closes", "2026-09-20",
     "tpo", NOTICE_LOG["training_aws_bootcamp"]),
    ("Incubation Cohort 5 concept note submission closes", "2026-09-30",
     "incubation_manager", NOTICE_LOG["incubation_cohort5"]),
    ("Internal Assessment II examinations begin", "2026-10-26",
     "exam_controller", NOTICE_LOG["exam_ia2_notice"]),
    ("End-Semester theory examinations begin", "2026-12-07",
     "exam_controller", NOTICE_LOG["exam_schedule_odd"]),
    ("Odd-semester result declaration", "2026-12-24",
     "exam_controller", NOTICE_LOG["exam_result_odd"]),
    ("Library annual fine-waiver window closes", "2026-08-31",
     "librarian", NOTICE_LOG["library_fine_waiver"]),
    ("Anti-ragging affidavit submission deadline (all students)", "2026-08-05",
     "antiragging_spoc", NOTICE_LOG["grievance_affidavit"]),
    # Below: deadlines behind the notices/events/attendance documents in
    # corpus/render_notices.py. Each date is also the date printed on the individual notice
    # named by its notice_key, so the two can never drift apart.
    ("Hostel room allotment application closes", "2026-07-05",
     "hostel_warden", NOTICE_LOG["hostel_allotment"]),
    ("Bus pass renewal window closes", "2026-07-08",
     "transport_incharge", NOTICE_LOG["bus_pass_renewal"]),
    ("Odd-term tuition and hostel fee payment due date", "2026-07-20",
     "accounts_spoc", NOTICE_LOG["fee_payment_odd"]),
    ("Kokanotsav Technical Festival registration closes", "2026-09-18",
     "student_activities_officer", NOTICE_LOG["event_tech_fest"]),
    ("Annual Sports Meet registration closes", "2026-11-14",
     "sports_officer", NOTICE_LOG["event_sports_meet"]),
    ("Alumni Meet 2026 registration closes", "2026-12-15",
     "student_activities_officer", NOTICE_LOG["event_alumni_meet"]),
    ("NSS Special Winter Camp selection list submission closes", "2026-12-21",
     "nss_officer", NOTICE_LOG["event_nss_camp"]),
    ("Even-term semester registration closes", "2026-12-31",
     "student_section_spoc", NOTICE_LOG["semester_registration_even"]),
    ("Ranjan Cultural Festival registration closes", "2027-02-05",
     "student_activities_officer", NOTICE_LOG["event_cultural_fest"]),
    ("Convocation registration closes", "2027-02-20",
     "registrar", NOTICE_LOG["convocation_registration"]),
]


# ── fees ──────────────────────────────────────────────────────────────────────
# Self-financing engineering college fee structure: tuition varies by department (COMP and
# IT carry a placement-premium over the other three, mirroring real DTE-approved fee
# structures where high-demand branches cost more), hostel is flat and the bus fee is
# zoned by distance. late_fee accrues per day past the due date, capped so a very late
# payer is not billed an unbounded amount.
FEES = {
    "tuition_per_semester": {  # department code -> Rs. per semester
        "COMP": 68000, "EXTC": 62000, "MECH": 58000, "CIVIL": 58000, "IT": 62000,
    },
    "hostel_fee_per_semester": 32000,
    "bus_fee_per_semester": {  # route zone (see BUS_TRANSPORT routes) -> Rs. per semester
        "Ratnagiri city (up to 10 km)": 6000,
        "Outstation (10-25 km)": 9000,
        "Outstation (above 25 km)": 12000,
    },
    "late_fee_per_day": 50,
    "late_fee_cap": 2000,
    "odd_term_due_date": "2026-07-20",
    "even_term_due_date": "2027-01-20",
    "payment_modes": [
        "Online via the fee portal (net banking / UPI / debit card)",
        "Demand draft in favour of \"Konkan Ratna Institute of Engineering and Technology\", "
        "payable at Ratnagiri",
    ],
    "portal": "fees.kriet.ac.in",
    "spoc_key": "accounts_spoc",
}


# ── student administrative services ──────────────────────────────────────────
# ID card reissue and the bonafide certificate are the two most-requested one-off Student
# Section services; semester registration is the once-a-term one every student must complete.
ID_CARD = {
    "reissue_fee": 150,
    "turnaround_days": 7,
    "documents_required": [
        "Written application to the Student Section",
        "Police complaint copy or a signed self-declaration of loss",
        "Two recent passport-size photographs",
        "Fee receipt (Rs. 150, paid at the Accounts Section)",
    ],
    "spoc_key": "student_section_spoc",
}

BONAFIDE_CERTIFICATE = {
    "fee": 0,
    "turnaround_days": 3,
    "documents_required": [
        "Written application to the Student Section stating the purpose",
        "Latest fee receipt",
    ],
    "valid_purposes": [
        "Bank loan or scholarship application", "Passport application",
        "Bus/rail concession pass application", "Visa application",
    ],
    "spoc_key": "student_section_spoc",
}

SEMESTER_REGISTRATION = {
    "even_term_window": ("2026-12-20", "2026-12-31"),
    "documents_required": [
        "Previous semester marksheet",
        "No-dues clearance from the Library and Accounts Section",
        "Fee payment receipt for the upcoming term",
    ],
    "spoc_key": "student_section_spoc",
}


# ── hostel ────────────────────────────────────────────────────────────────────
HOSTEL = {
    "warden_key": "hostel_warden",
    "total_rooms": 180,
    "occupancy": "2 or 3 students per room depending on block",
    "blocks": ["Block E (Boys)", "Block F (Girls)"],
    "application_window": ("2026-06-15", "2026-07-05"),
    "documents_required": [
        "Hostel application form", "Income certificate (for fee concession, if applicable)",
        "Medical fitness certificate", "Parent/guardian consent letter",
    ],
    # Distance-from-home applicants (over 40 km) are seated before merit-only applicants,
    # since a hostel seat is scarcer than a day-scholar's commute problem.
    "allotment_basis": "Distance from home (over 40 km) first, then merit, until rooms are full",
    "mess_charges_per_month": 3800,
}


# ── bus transport ─────────────────────────────────────────────────────────────
BUS_TRANSPORT = {
    "incharge_key": "transport_incharge",
    # route name, area covered, fee zone (must match a FEES["bus_fee_per_semester"] key)
    "routes": [
        ("Route 1", "Ratnagiri city - Nachane - College", "Ratnagiri city (up to 10 km)"),
        ("Route 2", "Chiplun - Sangameshwar - College", "Outstation (10-25 km)"),
        ("Route 3", "Rajapur - Lanja - College", "Outstation (above 25 km)"),
    ],
    "renewal_window": ("2026-06-18", "2026-07-08"),
    "documents_required": ["Previous term's bus pass (for renewal)", "Fee receipt"],
}


# ── dress code ────────────────────────────────────────────────────────────────
DRESS_CODE = {
    "weekday_code": (
        "Formal attire: collared shirt tucked in, formal trousers, closed-toe footwear. "
        "T-shirts, shorts and slippers are not permitted in academic areas."
    ),
    "wednesday_saturday_code": (
        "Institute uniform (department-colour polo T-shirt) with the ID card worn visibly."
    ),
    "lab_code": (
        "Cotton clothing and closed shoes are mandatory in laboratories handling electrical "
        "or mechanical equipment; synthetic fabric is not permitted."
    ),
    "id_card_rule": (
        "The ID card must be worn visibly at all times on campus; a student without it may "
        "be denied entry to the library, laboratories and examination halls."
    ),
    "spoc_key": "student_section_spoc",
}


# ── convocation ───────────────────────────────────────────────────────────────
CONVOCATION = {
    "ceremony_date": "2027-03-25",
    "registration_window": ("2027-02-01", "2027-02-20"),
    "registration_fee": 1500,
    "gown_hire_fee": 500,
    "eligibility": (
        "Final-year students who have cleared all backlogs and been declared eligible by "
        "the odd-term result date"
    ),
    "documents_required": [
        "Provisional degree certificate application form",
        "No-dues clearance from the Library, Accounts Section and Hostel",
        "Convocation registration fee receipt",
    ],
    "spoc_key": "registrar",
}


# ── student handbook: code of conduct, examination malpractice, hostel/lab/IT rules, leave ──
# Dress code lives in DRESS_CODE above and anti-ragging in GRIEVANCE["anti_ragging"] — both
# reused here rather than restated, so the handbook can never drift from the notices that
# also quote them.
CODE_OF_CONDUCT = [
    "Students must carry their ID card at all times and produce it on demand by any member "
    "of the faculty or administrative staff.",
    "Ragging in any form, on campus or off campus, is a punishable offence under UGC "
    "Regulations 2009 and the Maharashtra Prohibition of Ragging Act, 1999.",
    "Mobile phones may not be used in classrooms or laboratories during instructional hours "
    "unless explicitly permitted by the faculty in charge.",
    "Smoking, consumption of alcohol or any prohibited substance is strictly banned on "
    "campus and in the hostel premises.",
    "Students must maintain decorum in the library, laboratories and administrative "
    "offices; loud conversation and audible mobile phone ringers are not permitted.",
    "Damage to institute property (furniture, equipment, laboratory apparatus) is "
    "chargeable to the student(s) responsible, in addition to disciplinary action.",
    "Harassment or discrimination on the basis of caste, religion, gender or region is "
    "handled under the Student Grievance Redressal Committee's procedure (see GRIEVANCE).",
]

# offense, penalty — DBATU-pattern examination malpractice schedule.
EXAM_MALPRACTICE = [
    ("Possession of unauthorized material (chits, programmed calculator, mobile phone) in "
     "the examination hall",
     "Cancellation of that paper; fine of Rs. 5,000"),
    ("Copying from another candidate, or allowing another candidate to copy",
     "Cancellation of that paper for both candidates; fine of Rs. 5,000 each"),
    ("Impersonation (appearing in an examination on behalf of another candidate)",
     "Cancellation of admission for the current academic year; matter referred to the "
     "University"),
    ("Using or attempting to use an electronic communication device during the examination",
     "Cancellation of that paper; debarred from the next examination"),
    ("Misbehaviour with an invigilator or examination staff",
     "Cancellation of that paper; disciplinary action by the Principal"),
    ("Tampering with the answer book (removing pages, forging the roll number)",
     "Cancellation of all papers of that examination; debarred for one academic year"),
]

HOSTEL_RULES = [
    "The hostel gate closes at 21:30 on weekdays and 22:30 on weekends; late entry requires "
    "prior written permission from the Hostel Warden.",
    "Visitors are received only in the common room, between 16:00 and 19:00, and must sign "
    "the visitor register at the gate.",
    "Cooking in hostel rooms is prohibited; the mess is the only sanctioned dining facility.",
    "Possession of electrical appliances other than a mobile phone charger, laptop charger "
    "and study lamp requires prior written permission from the Hostel Warden.",
    "Ragging in any form within the hostel is a punishable offence and is reported to the "
    "Anti-Ragging Committee immediately.",
    "Overnight leave from the hostel requires a written application countersigned by the "
    "parent/guardian, submitted at least 24 hours in advance.",
]

LAB_SAFETY_RULES = [
    "Closed-toe footwear, and a laboratory coat where specified, are mandatory in every "
    "laboratory session.",
    "Food and beverages are not permitted inside any laboratory.",
    "Every laboratory incident, however minor, must be reported to the faculty in charge "
    "immediately; the first-aid box is at the laboratory entrance.",
    "Equipment must not be operated without the faculty member or laboratory assistant "
    "present.",
    "Long hair must be tied back and loose clothing avoided in laboratories with rotating "
    "machinery (Mechanical workshop, Electronics hardware labs).",
    "Fire extinguishers are mounted at each laboratory entrance; students are briefed on "
    "their use during the first laboratory session of every semester.",
]

IT_ACCEPTABLE_USE = [
    "Institute Wi-Fi and computer laboratory systems may be used only for academic and "
    "research purposes.",
    "Sharing login credentials (Wi-Fi, library portal, learning management system) with "
    "another person is prohibited.",
    "Downloading or distributing copyrighted material without authorization, and accessing "
    "content prohibited under Indian law, results in immediate suspension of network access "
    "and disciplinary action.",
    "Installing unauthorized software on laboratory systems is prohibited; software "
    "installation requests are routed through the department's laboratory in-charge.",
    "Network usage is logged; misuse, including attempts to bypass the institute firewall "
    "or access another user's account, is reported to the Principal.",
]

LEAVE_PROCEDURE = {
    "types": ["Medical leave", "Personal/casual leave", "On-duty leave (institute-sponsored event)"],
    "application_route": (
        "Leave application submitted to the class mentor/class in-charge, countersigned by "
        "the Head of Department for leave exceeding 3 consecutive days"
    ),
    "advance_notice_days": 2,  # for planned (non-medical) leave
    "on_duty_approval": (
        "On-duty leave for an institute-sponsored event (sports meet, technical festival, "
        "NSS camp — see EVENTS) is approved by the Head of Department on the recommendation "
        "of the event coordinator; the days covered are counted as present for attendance "
        "purposes"
    ),
}


# ── annual events ─────────────────────────────────────────────────────────────
# One entry per institute event notified individually (see render_notices.py). Where
# registration_deadline is not None, the same date and notice_key are mirrored into
# DEADLINES below so the event's registration closing date also appears in the consolidated
# deadline tracker — an event with open attendance (industry visit, guest lecture) has no
# registration step and so no deadline to track.
EVENTS = [
    {
        "name": "Kokanotsav Technical Festival",
        "type": "Technical festival",
        "date_start": "2026-09-25", "date_end": "2026-09-26",
        "venue": "Main Auditorium and COMP/EXTC Laboratories",
        "coordinator_key": "student_activities_officer",
        "registration_deadline": "2026-09-18",
        "registration_fee": 100,
        "notice_key": "event_tech_fest",
        "description": (
            "Annual technical festival featuring a coding contest, robotics challenge, "
            "project exhibition and technical paper presentation, open to engineering "
            "colleges across the Konkan region."
        ),
    },
    {
        "name": "Ranjan Cultural Festival",
        "type": "Cultural festival",
        "date_start": "2027-02-12", "date_end": "2027-02-14",
        "venue": "Open Air Theatre",
        "coordinator_key": "student_activities_officer",
        "registration_deadline": "2027-02-05",
        "registration_fee": 50,
        "notice_key": "event_cultural_fest",
        "description": (
            "Annual cultural festival with music, dance, drama and fine-arts competitions, "
            "concluding with a celebrity night."
        ),
    },
    {
        "name": "Annual Sports Meet",
        "type": "Sports meet",
        "date_start": "2026-11-21", "date_end": "2026-11-22",
        "venue": "Sports Complex Ground",
        "coordinator_key": "sports_officer",
        "registration_deadline": "2026-11-14",
        "registration_fee": 0,
        "notice_key": "event_sports_meet",
        "description": (
            "Inter-departmental athletics, cricket, kabaddi and kho-kho competitions, "
            "concluding with the prize distribution ceremony."
        ),
    },
    {
        # Deliberate cross-reference: Malvan Robotics Pvt Ltd is also a PLACEMENTS
        # recruiter — the same company shows up for two unrelated reasons, as small-region
        # industry does in practice.
        "name": "Industry Visit - Malvan Robotics Pvt Ltd",
        "type": "Industry visit",
        "date_start": "2026-09-05", "date_end": "2026-09-05",
        "venue": "Malvan Robotics Pvt Ltd, Chiplun",
        "coordinator_key": "tpo",
        "registration_deadline": None,
        "registration_fee": None,
        "notice_key": "event_industry_visit",
        "description": (
            "One-day industry visit for Mechanical and Electronics and Telecommunication "
            "Engineering students to observe an industrial automation and robotics assembly "
            "line."
        ),
    },
    {
        "name": "Guest Lecture Series - Emerging Technologies",
        "type": "Guest lecture series",
        "date_start": "2026-10-10", "date_end": "2026-10-10",
        "venue": "Seminar Hall, Block D",
        "coordinator_key": "student_activities_officer",
        "registration_deadline": None,
        "registration_fee": None,
        "notice_key": "event_guest_lecture",
        "description": (
            "A single day of three guest lectures on Artificial Intelligence, Renewable "
            "Energy and Entrepreneurship, open to all semesters."
        ),
    },
    {
        "name": "Alumni Meet 2026",
        "type": "Alumni meet",
        "date_start": "2026-12-27", "date_end": "2026-12-27",
        "venue": "Main Auditorium",
        "coordinator_key": "student_activities_officer",
        "registration_deadline": "2026-12-15",
        "registration_fee": 0,
        "notice_key": "event_alumni_meet",
        "description": (
            "Annual gathering of graduated alumni with current final-year students, for "
            "networking and a panel discussion on career pathways."
        ),
    },
    {
        "name": "NSS Special Winter Camp",
        "type": "NSS camp",
        "date_start": "2026-12-28", "date_end": "2027-01-03",
        "venue": "Adopted Village - Pawas",
        "coordinator_key": "nss_officer",
        "registration_deadline": "2026-12-21",
        "registration_fee": 0,
        "notice_key": "event_nss_camp",
        "description": (
            "Seven-day residential NSS special camp: community service, health awareness "
            "drives and a cleanliness campaign in the adopted village of Pawas."
        ),
    },
]


# ── attendance tiers, condonation procedure and a specimen monthly statement ────
# Three non-overlapping bands over ATTENDANCE_POLICY's own thresholds (never restated as
# fresh magic numbers, so the two structures cannot drift apart): [0, debarment_threshold)
# debarred outright, [debarment_threshold, min_attendance) condonable, [min_attendance, 101)
# regular. Each tier is (label, min_pct inclusive, max_pct exclusive, consequence).
ATTENDANCE_TIERS = [
    (
        "Below debarment threshold", 0, ATTENDANCE_POLICY["debarment_threshold_pct"],
        "Debarred outright from the End-Semester examination for the term; no condonation "
        "is possible at this tier.",
    ),
    (
        "Condonable band",
        ATTENDANCE_POLICY["condonation_band_low_pct"], ATTENDANCE_POLICY["condonation_band_high_pct"],
        "Placed on the defaulter list and notified in writing to the parent/guardian; may "
        "apply for condonation (see CONDONATION_PROCEDURE) to be permitted to sit the "
        "End-Semester examination.",
    ),
    (
        "Regular", ATTENDANCE_POLICY["min_attendance_pct"], 101,
        "No action; eligible to appear for the End-Semester examination without condonation.",
    ),
]


CONDONATION_PROCEDURE = {
    "eligible_band": (
        ATTENDANCE_POLICY["condonation_band_low_pct"], ATTENDANCE_POLICY["condonation_band_high_pct"],
    ),
    "max_grant_pct": ATTENDANCE_POLICY["condonation_max_grant_pct"],
    "form_fields": [
        "Student name, roll number, department, semester and division",
        "Subject-wise attendance percentage as on the defaulter list publication date",
        "Reason for shortfall (medical / personal / institute-sponsored duty)",
        "List of supporting documents attached",
        "Parent/guardian signature",
        "Class mentor's recommendation",
    ],
    "supporting_documents": [
        "Medical certificate from a Registered Medical Practitioner (for medical leave)",
        "On-duty certificate signed by the event coordinator (for institute-sponsored duty, "
        "see LEAVE_PROCEDURE)",
        "Parent/guardian's written explanation (for personal reasons)",
    ],
    "submission_deadline_days_after_list": 7,  # mirrors ATTENDANCE_POLICY["appeal_route"]
    "committee": ATTENDANCE_POLICY["committee"],
    "chair_key": ATTENDANCE_POLICY["committee_chair_key"],
    "decision_timeline_days": 10,
}


# Specimen only — illustrates how a student reads their own monthly attendance statement, not
# a real student's record (see the PII note at the top of this module). Subject codes are
# Computer Engineering semester 3's (see SUBJECTS/TIMETABLE), so the specimen lines up with a
# real, renderable timetable. Deliberately shows one subject (CO303) below the 75% minimum
# while the overall percentage is comfortably above it — the way an actual statement can hide
# a single at-risk subject behind a healthy overall number.
ATTENDANCE_SPECIMEN = {
    "note": "Specimen statement for illustration only; does not represent an actual student's record.",
    "student_label": "Specimen Student, Roll No. COMP-2026-SPECIMEN-01",
    "dept_code": "COMP", "semester": 3, "division": "A",
    "month": "September 2026",
    # subject code, lectures held, lectures attended
    "rows": [
        ("CO301", 12, 10),
        ("CO302", 12, 11),
        ("CO303", 12, 8),
        ("CO304", 12, 12),
        ("CO305", 4, 4),
        ("CO306", 4, 3),
    ],
}


def rupees(n: int) -> str:
    """Indian digit grouping — 142000 -> 1,42,000."""
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
