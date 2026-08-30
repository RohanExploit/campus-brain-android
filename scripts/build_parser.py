import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pdfplumber
import re
import json

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
    # Find the subject codes row (contains "Total Marks")
    subject_row_idx = -1
    for i, r in enumerate(rows):
        text = " ".join([w['text'] for w in r])
        if "Total Marks" in text:
            subject_row_idx = i
            break

    if subject_row_idx == -1:
        return None

    subject_row = rows[subject_row_idx]
    # Extract subjects (any word before "Total")
    for w in subject_row:
        if "Total" in w['text']:
            break
        # Subject codes are usually alphanumeric
        if re.match(r'^[A-Z0-9]+$', w['text']):
            subjects.append({"code": w['text'], "x0": w['x0'], "x1": w['x1'], "credit": 0})

    # Now find CREDIT row
    credit_row = rows[subject_row_idx + 1]
    credit_text = " ".join([w['text'] for w in credit_row])
    if "CREDIT" in credit_text:
        for w in credit_row:
            if w['text'] == "CREDIT": continue
            if re.match(r'^\d+$', w['text']):
                # Find closest subject by x0
                closest = min(subjects, key=lambda s: abs(s['x0'] - w['x0']))
                closest['credit'] = int(w['text'])

    return subjects

def parse_student_blocks(rows, subjects):
    blocks = []
    cur_block = []

    roll_pattern = re.compile(r'^\d{10,15}$')

    for r in rows:
        text = " ".join([w['text'] for w in r])
        if not r: continue
        first_word = r[0]['text']

        if roll_pattern.match(first_word) and "PASS" in text or "FAIL" in text:
            # Start of a new block
            if cur_block:
                blocks.append(cur_block)
            cur_block = [r]
        elif cur_block:
            cur_block.append(r)

    if cur_block:
        blocks.append(cur_block)

    results = []
    for block in blocks:
        try:
            res = parse_single_block(block, subjects)
            if res: results.append(res)
        except Exception as e:
            print(f"Error parsing block: {e}")

    return results

def parse_single_block(block, subjects):
    # R0: Student details
    r0_text = " ".join([w['text'] for w in block[0]])
    parts = r0_text.split()
    roll_no = parts[0]
    result_status = parts[-1]
    # Name is everything between roll no and the institute code (which is a number)
    name_parts = []
    for p in parts[1:-1]:
        if re.match(r'^\d{4,5}$', p): # Institute code
            break
        name_parts.append(p)
    name = " ".join(name_parts)

    # Identify document type
    is_supply = False
    last_row_text = " ".join([w['text'] for w in block[-1]])
    if "Winter -" in last_row_text or "Summer -" in last_row_text:
        is_supply = True


    # We rely on the final grades row, which is the 2nd to last if supply, or last if regular
    grade_row_idx = -2 if is_supply else -1

    # SGPA and Total Marks
    # R1: ESE + SGPA
    r1_text = " ".join([w['text'] for w in block[1]])
    r1_parts = r1_text.split()
    sgpa = None
    try:
        # SGPA is usually at the end, formatted as a float (e.g. 6.07)
        # If it's a FAIL student, it might not be printed at all.
        last_val = r1_parts[-1]
        if "." in last_val and len(last_val.split(".")) == 2:
            sgpa = float(last_val)
    except Exception:
        pass

    # Total Marks is in R4 (0-indexed)
    total_marks = 0
    if len(block) >= 5:
        r4_text = " ".join([w['text'] for w in block[4]])
        r4_parts = r4_text.split()
        if r4_parts and r4_parts[0].isdigit():
            total_marks = int(r4_parts[0])

    # Grades are in grade_row_idx
    grades_row = block[grade_row_idx]
    # Filter out '|'
    grades_tokens = [w['text'] for w in grades_row if w['text'] != '|']

    student_subjects = []
    for i, sub in enumerate(subjects):
        grade_str = grades_tokens[i] if i < len(grades_tokens) else "0/FF/0"
        # Format: 5.5/DE/22 or AU or 0//0
        pts = 0.0
        g = "FF"
        if "/" in grade_str:
            parts = grade_str.split("/")
            if len(parts) == 3:
                g = parts[1]
                try:
                    pts = float(parts[2]) if parts[2] else 0.0
                except Exception:
                    pass
        elif grade_str == "AU":
            g = "AU"

        student_subjects.append({
            "code": sub['code'],
            "credit": sub['credit'],
            "grade": g,
            "grade_points": pts,
            "raw": grade_str
        })

    # Validation
    calc_points = sum([s['grade_points'] for s in student_subjects])
    # Actually SGPA = total points / total credits (of registered subjects)
    # total registered credits is sum of all credits except AU. Wait, FF counts in denominator for SGPA!
    registered_credits = sum([s['credit'] for s in student_subjects if s['grade'] != 'AU'])
    calc_sgpa = round(calc_points / registered_credits, 2) if registered_credits > 0 else 0.0

    sgpa_match = (sgpa is not None) and (abs(calc_sgpa - sgpa) <= 0.05)

    # We don't have per-subject total marks parsed yet, let's parse R7 (Subject TOTALS)
    # It is block[7]
    totals_row = block[7]
    totals_tokens = [w['text'] for w in totals_row if w['text'] != '|']
    calc_total_marks = 0
    for t in totals_tokens:
        t_clean = t.replace("(", "").replace(")", "").strip()
        if t_clean.isdigit():
            calc_total_marks += int(t_clean)

    marks_match = (calc_total_marks == total_marks)
    token_count_match = (len(grades_tokens) == len(subjects))

    passed_validation = sgpa_match and marks_match and token_count_match

    return {
        "roll_no": roll_no,
        "name": name,
        "result": result_status,
        "sgpa": sgpa,
        "estimated_sgpa_partial_credits": calc_sgpa,
        "total_marks": total_marks,
        "is_supply": is_supply,
        "subjects": student_subjects,
        "validation": {
            "sgpa_match": sgpa_match,
            "calc_sgpa": calc_sgpa,
            "marks_match": marks_match,
            "calc_total_marks": calc_total_marks,
            "token_count_match": token_count_match,
            "grades_tokens": len(grades_tokens),
            "subject_count": len(subjects),
            "passed_all": passed_validation
        }
    }

def main():
    files = [
        (f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf", 0), # page 1
        (f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf", 1), # page 2
        (f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Artificial Intelligence (AI) and Data Science)_3(DECEMBER_2025) - CR Report (1).pdf", 0)
    ]

    targets = ["2267571242025", "2267571242133", "23067571263005"]

    results = []

    for path, page_idx in files:
        with pdfplumber.open(path) as pdf:
            page = pdf.pages[page_idx]
            rows = extract_rows(page)
            subjects = parse_header(rows)
            if subjects:
                blocks = parse_student_blocks(rows, subjects)
                for b in blocks:
                    if b['roll_no'] in targets:
                        results.append(b)
                        targets.remove(b['roll_no'])

    print(json.dumps(results, indent=2))

if __name__ == "__main__":
    main()
