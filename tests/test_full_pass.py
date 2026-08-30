import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pdfplumber
import re
import traceback

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

    credit_row = rows[subject_row_idx + 1]
    credit_text = " ".join([w['text'] for w in credit_row])
    if "CREDIT" in credit_text:
        for w in credit_row:
            if w['text'] == "CREDIT": continue
            if re.match(r'^\d+$', w['text']):
                closest = min(subjects, key=lambda s: abs(s['x0'] - w['x0']))
                closest['credit'] = int(w['text'])

    # Find sum of printed max marks in TOTAL row
    printed_max_sum = 0
    for r in rows:
        text = " ".join([w['text'] for w in r])
        if text.startswith("TOTAL") and "100/40" in text:
            # count occurrences of XXX/YY
            tokens = text.split()
            for tk in tokens:
                if "/" in tk and tk != "GRADE" and tk != "TOTAL":
                    parts = tk.split("/")
                    if parts[0].isdigit():
                        printed_max_sum += int(parts[0])
            break

    return subjects, expected_total_max, printed_max_sum

def parse_single_block(block, subjects, expected_total_max, printed_max_sum):
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
    sgpa = None
    try:
        last_val = r1_parts[-1]
        if "." in last_val and len(last_val.split(".")) == 2:
            sgpa = float(last_val)
    except Exception:
        pass

    total_marks = 0
    if len(block) >= 5:
        r4_text = " ".join([w['text'] for w in block[4]])
        r4_parts = r4_text.split()
        if r4_parts and r4_parts[0].isdigit():
            total_marks = int(r4_parts[0])

    grades_row = block[grade_row_idx]
    grades_tokens = [w['text'] for w in grades_row if w['text'] != '|']

    student_subjects = []
    for i, sub in enumerate(subjects):
        grade_str = grades_tokens[i] if i < len(grades_tokens) else "0/FF/0"

        # Strip grace marks suffix e.g. (G-3) before processing
        grade_str = re.sub(r'\(G-\d+\)', '', grade_str)

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
            "grade_points": pts
        })

    calc_points = sum([s['grade_points'] for s in student_subjects])
    registered_credits = sum([s['credit'] for s in student_subjects if s['grade'] != 'AU'])
    calc_sgpa = round(calc_points / registered_credits, 2) if registered_credits > 0 else 0.0

    # SGPA validation
    sgpa_match = True
    if result_status == 'PASS':
        if sgpa is None or abs(calc_sgpa - sgpa) > 0.05:
            sgpa_match = False
    else:
        # FAIL students expect no SGPA printed (None)
        if sgpa is not None:
            # We don't fail validation if it IS printed, we just say it's valid if they match or it's absent
            if abs(calc_sgpa - sgpa) > 0.05:
                sgpa_match = False

    # Total marks validation
    # Subject TOTALs are 2 rows above grades row in 9/10-row blocks?
    # Actually it's block[7] in AI/DS supply (10 row) and block[7] in regular (9 row) ?
    # Let's count backwards. Grades is -1 or -2. Totals is grades_row - 1. (Wait, regular is row 8 (0-indexed). So -1.
    # Ah! Row 8 is block[8] (grades), block[7] is totals.
    # In supply, block[9] is semester, block[8] is grades, block[7] is totals.
    # Actually block[grade_row_idx - 1] is the `|` separator.
    # block[grade_row_idx - 2] is the TOTALs!
    # Let's check regular: block[8] is grades. block[7] is totals. So grade_row_idx - 1.
    # Wait, earlier regular: R6 is MID, R7 is `|`, R8 is TOTAL per subject, R9 is Grades!
    # Oh! My previous code said `block[7]` (which is row 8 if 0-indexed).
    # Let's use `grade_row_idx - 1` as it was `totals_row = block[7]` for both. Wait!
    # HAJARE: 9 rows (block has 9 elements). grades is 8. totals is 7. grade_row_idx is -1.
    # AI/DS: 10 rows. grades is 8. totals is 7. grade_row_idx is -2.
    totals_row = block[grade_row_idx - 1]
    if len([w for w in totals_row if w['text'] == '|']) > 0 and len(totals_row) < 3:
        # If it's just the `|` separator, then totals might be grade_row_idx - 2
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

    # Compile flags
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
        "passed_all": len(flags) == 0,
        "flags": flags,
        "gap": gap,
        "derived_max": derived_max,
        "raw_block": raw_block_text
    }

def main():
    pdfs = [
        f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf",
        f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Artificial Intelligence (AI) and Data Science)_3(DECEMBER_2025) - CR Report (1).pdf"
    ]

    stats = {
        "total_parsed": 0,
        "passed_all": 0,
        "exceptions": 0,
        "flags": {
            "sgpa_mismatch": 0,
            "gap_exceeds_max_possible": 0,
            "unverifiable_unscored_subject_present": 0,
            "marks_mismatch_other": 0,
            "token_count_mismatch": 0
        },
        "anomalous_blocks": [],
        "pages_parsed": 0,
        "pdf_stats": {}
    }

    roll_pattern = re.compile(r'^\d{10,15}$')

    for path in pdfs:
        with pdfplumber.open(path) as pdf:
            stats["pdf_stats"][path] = len(pdf.pages)
            stats["pages_parsed"] += len(pdf.pages)
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

                    footer_markers = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]
                    if any(text.startswith(m) for m in footer_markers):
                        if cur_block:
                            blocks.append(cur_block)
                            cur_block = []
                        continue

                    if roll_pattern.match(first_word) and ("PASS" in text or "FAIL" in text):
                        if cur_block: blocks.append(cur_block)
                        cur_block = [r]
                    elif cur_block:
                        cur_block.append(r)
                if cur_block: blocks.append(cur_block)

                for b in blocks:
                    stats["total_parsed"] += 1
                    try:
                        res = parse_single_block(b, subjects, exp_max, printed_sum)
                        if res['passed_all']:
                            stats["passed_all"] += 1
                        else:
                            for f in res['flags']:
                                stats["flags"][f] += 1

                            # If the failure is NOT just 'unverifiable_unscored_subject_present' (which we expect for AI/DS)
                            # or 'gap_exceeds_max_possible' (maybe?), we capture it as an anomaly to report.
                            if len(res['flags']) > 1 or ('unverifiable_unscored_subject_present' not in res['flags']):
                                stats["anomalous_blocks"].append(res)
                    except Exception as e:
                        stats["exceptions"] += 1
                        print(f"Exception parsing block: {e}")
                        traceback.print_exc()

    print("\n=== FINAL STATS ===")
    print("Page Counts:")
    for path, count in stats["pdf_stats"].items():
        print(f"  - {path}: {count} pages")
    print(f"Total Pages Parsed: {stats['pages_parsed']}")
    print(f"Total Student Blocks Parsed: {stats['total_parsed']}")
    print(f"Passed Cleanly: {stats['passed_all']}")
    print(f"Exceptions: {stats['exceptions']}")
    print("Flags Breakdown:")
    for k, v in stats['flags'].items():
        if v > 0:
            print(f"  - {k}: {v}")

    print(f"\nTotal Anomalous Needs-Review Cases: {len(stats['anomalous_blocks'])}")
    # Print all anomalies
    for i, a in enumerate(stats['anomalous_blocks']):
        print(f"\n--- ANOMALY {i+1} ---")
        print(f"Roll No: {a['roll_no']}, Flags: {a['flags']}, Gap: {a['gap']}, Derived Max: {a['derived_max']}")
        print(a['raw_block'])

if __name__ == "__main__":
    main()
