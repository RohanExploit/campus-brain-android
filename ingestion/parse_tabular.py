import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pdfplumber
import re
import json
import logging
import duckdb
from pathlib import Path
from utils.logging_config import setup_logging
from ingestion.record_schema import validate_parsed_record

setup_logging()
logger = logging.getLogger(__name__)

def extract_rows(page):
    words = page.extract_words()
    words_sorted = sorted(words, key=lambda w: (w['top'], w['x0']))
    rows = []
    cur_row = []
    cur_y = None
    for w in words_sorted:
        if cur_y is None:
            cur_y = w['top']
            cur_row.append(w)
        elif abs(w['top'] - cur_y) <= 3.0:
            cur_row.append(w)
        else:
            rows.append(sorted(cur_row, key=lambda w: w['x0']))
            cur_row = [w]
            cur_y = w['top']
    if cur_row:
        rows.append(sorted(cur_row, key=lambda w: w['x0']))
    return rows

def parse_header(rows):
    subjects = []
    subject_row_idx = -1
    total_marks_str = ""
    for i, r in enumerate(rows):
        text = " ".join([w['text'] for w in r])
        if "Total Marks(" in text:
            subject_row_idx = i
            m = re.search(r'Total Marks\((\d+)\)', text)
            if m:
                total_marks_str = m.group(1)
            break

    if subject_row_idx == -1:
        return None, 0, 0

    expected_total_max = int(total_marks_str) if total_marks_str else 0

    subject_row = rows[subject_row_idx]
    for w in subject_row:
        if "Total" in w['text']:
            break
        if re.match(r'^[A-Z0-9]+$', w['text']):
            subjects.append({"code": w['text'], "x0": w['x0'], "x1": w['x1'], "credit": 0})

    # Fix 2: CSE_2 long subject codes wrap onto a suffix row before the CREDIT row.
    # If the row immediately after the subject-code row does NOT start with "CREDIT",
    # it is a code-suffix row (e.g. '1 2C 6A 8 07A...') — skip it.
    credit_row_idx = subject_row_idx + 1
    if credit_row_idx < len(rows):
        next_row_text = " ".join([w['text'] for w in rows[credit_row_idx]])
        if "CREDIT" not in next_row_text:
            credit_row_idx = subject_row_idx + 2  # jump past suffix row

    credit_row = rows[credit_row_idx] if credit_row_idx < len(rows) else []
    credit_text = " ".join([w['text'] for w in credit_row])
    if "CREDIT" in credit_text:
        for w in credit_row:
            if w['text'] == "CREDIT": continue
            if re.match(r'^\d+$', w['text']):
                closest = min(subjects, key=lambda s: abs(s['x0'] - w['x0']))
                closest['credit'] = int(w['text'])

    printed_max_sum = 0
    for r in rows:
        text = " ".join([w['text'] for w in r])
        if text.startswith("TOTAL") and "100/40" in text:
            tokens = text.split()
            for tk in tokens:
                if "/" in tk and tk != "GRADE" and tk != "TOTAL":
                    parts = tk.split("/")
                    if parts[0].isdigit():
                        printed_max_sum += int(parts[0])
            break

    return subjects, expected_total_max, printed_max_sum

def parse_single_block(block, subjects, expected_total_max, printed_max_sum):
    # A block is a student header row (roll + PASS/FAIL) plus its R1..R4 detail
    # rows. Nearly every access below assumes block[1]/block[-2] exist. A block
    # with a single row (header immediately followed by the next student or a
    # footer) would otherwise raise an anonymous IndexError that the caller
    # swallows — silently losing the student. Fail loudly with the roll number.
    if len(block) < 2:
        roll = block[0][0]['text'] if block and block[0] else "?"
        raise ValueError(f"Malformed block: roll {roll!r} has {len(block)} row(s), need >=2")
    raw_block_text = "\n".join([" ".join([w['text'] for w in r]) for r in block])
    r0_text = " ".join([w['text'] for w in block[0]])
    parts = r0_text.split()
    roll_no = parts[0]
    result_status = parts[-1]

    name_parts = []
    for p in parts[1:-1]:
        if re.match(r'^\d{4,5}$', p):
            break
        name_parts.append(p)
    name = " ".join(name_parts)

    is_supply = False
    last_row_text = " ".join([w['text'] for w in block[-1]])
    if "Winter -" in last_row_text or "Summer -" in last_row_text:
        is_supply = True

    grade_row_idx = -2 if is_supply else -1

    # R1 SGPA
    r1_text = " ".join([w['text'] for w in block[1]])
    r1_parts = r1_text.split()
    # Fix 1: CSE_1 R1 has extra trailing columns (Percentage, ExtTotal) after SGPA.
    # Don't read r1_parts[-1] positionally — scan for the first float in [0.0, 10.0].
    # FAIL students have no SGPA token; scan correctly returns None for them.
    sgpa = None
    for token in r1_parts:
        if re.match(r'^\d+\.\d+$', token):
            val = float(token)
            if 0.0 <= val <= 10.0:
                sgpa = val
                break

    total_marks = 0
    if len(block) >= 5:
        r4_text = " ".join([w['text'] for w in block[4]])
        r4_parts = r4_text.split()
        if r4_parts and r4_parts[0].isdigit():
            total_marks = int(r4_parts[0])

    grades_row = block[grade_row_idx]
    raw_grades_tokens = [w['text'] for w in grades_row if w['text'] != '|']
    grades_tokens = []
    for t in raw_grades_tokens:
        if (t.startswith("G-") or t.startswith("(G-")) and grades_tokens:
            grades_tokens[-1] += t
        else:
            grades_tokens.append(t)

    student_subjects = []
    for i, sub in enumerate(subjects):
        grade_str = grades_tokens[i] if i < len(grades_tokens) else "0/FF/0"

        # Strip grace marks suffix (handling both (G-N) and malformed G-N) )
        grade_str = re.sub(r'\(?G-\d+\)?', '', grade_str)

        pts = 0.0
        g = "FF"
        if "/" in grade_str:
            gparts = grade_str.split("/")
            if len(gparts) == 3:
                g = gparts[1]
                try:
                    pts = float(gparts[2]) if gparts[2] else 0.0
                except Exception:
                    pass
        elif grade_str == "AU":
            g = "AU"

        student_subjects.append({
            "code": sub['code'],
            "credit": sub['credit'],
            "grade": g,
            "grade_point": pts,
            "raw": grades_tokens[i] if i < len(grades_tokens) else ""
        })

    calc_points = sum([s['grade_point'] for s in student_subjects])
    registered_credits = sum([s['credit'] for s in student_subjects if s['grade'] != 'AU'])
    calc_sgpa = round(calc_points / registered_credits, 2) if registered_credits > 0 else 0.0

    sgpa_match = True
    if result_status == 'PASS':
        if sgpa is None or abs(calc_sgpa - sgpa) > 0.05:
            sgpa_match = False
    else:
        if sgpa is not None and abs(calc_sgpa - sgpa) > 0.05:
            sgpa_match = False

    totals_row = block[grade_row_idx - 1]
    if len([w for w in totals_row if w['text'] == '|']) > 0 and len(totals_row) < 3:
        totals_row = block[grade_row_idx - 2]

    totals_tokens = [w['text'] for w in totals_row if w['text'] != '|']
    calc_total_marks = 0
    for t in totals_tokens:
        t_clean = t.replace("(", "").replace(")", "").strip()
        if t_clean.isdigit():
            calc_total_marks += int(t_clean)

    marks_match = (calc_total_marks == total_marks)
    token_count_match = (len(grades_tokens) == len(subjects))

    gap = total_marks - calc_total_marks
    derived_max = expected_total_max - printed_max_sum

    gap_exceeds = False
    unverifiable = False

    if not marks_match:
        if gap > derived_max or gap < 0:
            gap_exceeds = True
        elif gap > 0 and derived_max > 0:
            unverifiable = True

    flags = []
    if not sgpa_match: flags.append("sgpa_mismatch")
    if gap_exceeds: flags.append("gap_exceeds_max_possible")
    elif unverifiable: flags.append("unverifiable_unscored_subject_present")
    elif not marks_match: flags.append("marks_mismatch_other")
    if not token_count_match: flags.append("token_count_mismatch")

    return {
        "roll_no": roll_no,
        "name": name,
        "result": result_status,
        "sgpa": sgpa,
        "estimated_sgpa_partial_credits": calc_sgpa,
        "total_marks": total_marks,
        "is_supply": is_supply,
        "seat_cancelled": False,
        "subjects": student_subjects,
        "passed_all": len(flags) == 0,
        "flags": flags,
        "gap": gap,
        "derived_max": derived_max,
        "raw_block": raw_block_text
    }

def extract_cancelled_seats(pdf_path):
    cancelled_seats = set()
    with pdfplumber.open(pdf_path) as pdf:
        text = "\n".join([p.extract_text() for p in pdf.pages])
        for line in text.split("\n"):
            if "Cancel Seat No's" in line:
                # "Cancel Seat No's : 2221381242031, 23067571242000" etc
                parts = line.split(":")
                if len(parts) > 1:
                    numbers = parts[1].split()
                    for num in numbers:
                        num = num.strip().replace(",", "")
                        if num.isdigit() and len(num) >= 10:
                            cancelled_seats.add(num)
    return cancelled_seats

def parse_tabular_data(pdf_paths):
    clean_records = []
    needs_review = []
    all_cancelled_seats = set()

    roll_pattern = re.compile(r'^\d{10,15}$')
    footer_markers = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]

    for path in pdf_paths:
        cancelled = extract_cancelled_seats(path)
        all_cancelled_seats.update(cancelled)

        with pdfplumber.open(path) as pdf:
            # Fix 3: Schema detection — groupA/B PDFs have no 'Total Marks(' header.
            # Check full document text once before iterating pages.
            full_text = ""
            try:
                full_text = " ".join(p.extract_text() or "" for p in pdf.pages[:3])
            except Exception:
                pass
            if "Total Marks(" not in full_text:
                logger.warning(f"[schema_unsupported] {path} — no 'Total Marks(' header found. "
                      f"Likely flat-summary-table format (v1.1 scope). Skipping.")
                continue

            for page_idx in range(len(pdf.pages)):
                page = pdf.pages[page_idx]
                rows = extract_rows(page)
                subjects, exp_max, printed_sum = parse_header(rows)
                if not subjects:
                    continue

                blocks = []
                cur_block = []
                for r in rows:
                    if not r: continue
                    text = " ".join([w['text'] for w in r])
                    first_word = r[0]['text']

                    if any(text.startswith(m) for m in footer_markers):
                        if cur_block:
                            blocks.append(cur_block)
                            cur_block = []
                        continue

                    # Fix 3b: COPYCASE(RESERVE) and WITHHELD are valid result statuses
                    # that must trigger a block boundary, same as PASS/FAIL.
                    result_keywords = ["PASS", "FAIL", "COPYCASE", "WITHHELD"]
                    if roll_pattern.match(first_word) and any(kw in text for kw in result_keywords):
                        if cur_block: blocks.append(cur_block)
                        cur_block = [r]
                    elif cur_block:
                        cur_block.append(r)
                if cur_block: blocks.append(cur_block)

                for b in blocks:
                    try:
                        res = parse_single_block(b, subjects, exp_max, printed_sum)
                        violations = validate_parsed_record(res)
                        if violations:
                            logger.warning(
                                f"[schema_violation] roll {res.get('roll_no')!r} quarantined: {violations}"
                            )
                            res["flags"].extend([f"schema:{v}" for v in violations])
                            res["passed_all"] = False
                            needs_review.append(res)
                        elif res['passed_all']:
                            clean_records.append(res)
                        else:
                            needs_review.append(res)
                    except Exception as e:
                        # Identify the dropped student so a parse failure is
                        # traceable in logs instead of a silent data loss.
                        roll = b[0][0]['text'] if b and b[0] else "?"
                        logger.error(f"Exception parsing block (roll {roll}): {e}")

    # Apply cancelled seat flag to all records
    for r in clean_records + needs_review:
        if r['roll_no'] in all_cancelled_seats:
            r['seat_cancelled'] = True

    return clean_records, needs_review, list(all_cancelled_seats)

def store_in_duckdb(clean_records, needs_review, all_cancelled_seats, db_path):
    conn = duckdb.connect(db_path)

    conn.execute("BEGIN TRANSACTION")
    try:
        # Capture existing row counts (if tables already exist) before dropping,
        # so we can refuse to commit a rebuild that would silently shrink the
        # students table (e.g. a re-run with a stale/incomplete PDF list).
        old_student_count = 0
        try:
            old_student_count = conn.execute(
                "SELECT COUNT(*) FROM students"
            ).fetchone()[0]
        except Exception:
            old_student_count = 0

        conn.execute("DROP TABLE IF EXISTS student_subjects CASCADE")
        conn.execute("DROP TABLE IF EXISTS students CASCADE")
        conn.execute("DROP TABLE IF EXISTS needs_review CASCADE")
        conn.execute("DROP TABLE IF EXISTS cancelled_seats CASCADE")

        # Cancelled Seats table
        conn.execute("""
            CREATE TABLE cancelled_seats (
                roll_no VARCHAR PRIMARY KEY
            )
        """)

        # Students table
        conn.execute("""
            CREATE TABLE students (
                roll_no VARCHAR PRIMARY KEY,
                name VARCHAR,
                sgpa DOUBLE,
                estimated_sgpa DOUBLE,
                total_marks INTEGER,
                result VARCHAR,
                is_supply BOOLEAN,
                seat_cancelled BOOLEAN
            )
        """)

        # Subjects table
        conn.execute("""
            CREATE TABLE student_subjects (
                roll_no VARCHAR,
                subject_code VARCHAR,
                credit INTEGER,
                grade VARCHAR,
                grade_point DOUBLE,
                raw_grade_string VARCHAR,
                PRIMARY KEY (roll_no, subject_code),
                FOREIGN KEY (roll_no) REFERENCES students(roll_no)
            )
        """)

        # Needs Review table
        conn.execute("""
            CREATE TABLE needs_review (
                roll_no VARCHAR PRIMARY KEY,
                name VARCHAR,
                flags VARCHAR,
                gap INTEGER,
                derived_max INTEGER,
                raw_block VARCHAR
            )
        """)

        for c in all_cancelled_seats:
            conn.execute("INSERT INTO cancelled_seats (roll_no) VALUES (?)", (c,))

        for r in clean_records:
            conn.execute("""
                INSERT INTO students (roll_no, name, sgpa, estimated_sgpa, total_marks, result, is_supply, seat_cancelled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (r['roll_no'], r['name'], r['sgpa'], r['estimated_sgpa_partial_credits'],
                  r['total_marks'], r['result'], r['is_supply'], r['seat_cancelled']))

            for sub in r['subjects']:
                conn.execute("""
                    INSERT INTO student_subjects (roll_no, subject_code, credit, grade, grade_point, raw_grade_string)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (r['roll_no'], sub['code'], sub['credit'], sub['grade'], sub['grade_point'], sub['raw']))

        # needs_review.roll_no is a PRIMARY KEY, but a single ingest can legitimately
        # surface the same roll twice (e.g. a regular + a supplementary record, or a
        # mis-parsed duplicate). Collapse duplicates by roll_no — merging their flags —
        # so a repeat can't raise a constraint violation that rolls back the ENTIRE
        # ingest and leaves the tenant with zero updated rows.
        deduped_review: dict = {}
        for r in needs_review:
            existing = deduped_review.get(r['roll_no'])
            if existing is None:
                deduped_review[r['roll_no']] = dict(r)
            else:
                merged_flags = list(existing.get('flags') or [])
                for f in (r.get('flags') or []):
                    if f not in merged_flags:
                        merged_flags.append(f)
                existing['flags'] = merged_flags

        for r in deduped_review.values():
            conn.execute("""
                INSERT INTO needs_review (roll_no, name, flags, gap, derived_max, raw_block)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (r['roll_no'], r['name'], json.dumps(r['flags']), r['gap'], r['derived_max'], r['raw_block']))

        new_student_count = conn.execute("SELECT COUNT(*) FROM students").fetchone()[0]
        if new_student_count < old_student_count:
            raise RuntimeError(
                f"Refusing to commit: rebuild would shrink students table from "
                f"{old_student_count} to {new_student_count} rows. Check pdf_paths list."
            )

        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise
    finally:
        conn.close()

if __name__ == '__main__':
    pdfs = [
        f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf",
        f"{PROJECT_ROOT}/Results Dataset/AIDS Result.pdf",
        f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_5(DECEMBER_2025) - CR Report.pdf"
    ]
    db_path = f"{PROJECT_ROOT}/data/tenants/tenant_1/tabular.duckdb"

    Path(db_path).parent.mkdir(parents=True, exist_ok=True)

    clean, review, cancelled = parse_tabular_data(pdfs)

    print(f"Parsed {len(clean)} clean records.")
    print(f"Parsed {len(review)} needs-review records.")
    print(f"Found {len(cancelled)} cancelled seats globally.")

    store_in_duckdb(clean, review, cancelled, db_path)
    print(f"Stored records in {db_path}")
