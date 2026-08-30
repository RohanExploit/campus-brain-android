"""
Per-PDF diagnostic runner.
Runs the same block-parsing + self-validation logic used in test_full_pass.py
against every PDF supplied on the command line, and reports results.
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT

import pdfplumber
import re
import traceback

KNOWN_FOOTER_MARKERS = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]
ROLL_PATTERN = re.compile(r'^\d{10,15}$')

VALIDATED_DOCS = {
    "cse 5 reg.pdf",
    "Bachelor of Technology (Artificial Intelligence (AI) and Data Science)_3(DECEMBER_2025) - CR Report (1).pdf"
}

KNOWN_FLAGS = {
    "unverifiable_unscored_subject_present",
    "sgpa_mismatch",    # could still come from a truly unexpected case
}


def extract_rows(page):
    words = page.extract_words()
    words_sorted = sorted(words, key=lambda w: (w['top'], w['x0']))
    rows, cur_row, cur_y = [], [], None
    for w in words_sorted:
        if cur_y is None:
            cur_y = w['top']; cur_row.append(w)
        elif abs(w['top'] - cur_y) <= 3.0:
            cur_row.append(w)
        else:
            rows.append(sorted(cur_row, key=lambda w: w['x0']))
            cur_row = [w]; cur_y = w['top']
    if cur_row:
        rows.append(sorted(cur_row, key=lambda w: w['x0']))
    return rows


def parse_header(rows):
    subjects, subject_row_idx, total_marks_str = [], -1, ""
    for i, r in enumerate(rows):
        text = " ".join(w['text'] for w in r)
        if "Total Marks(" in text:
            subject_row_idx = i
            m = re.search(r'Total Marks\((\d+)\)', text)
            if m: total_marks_str = m.group(1)
            break
    if subject_row_idx == -1:
        return None, 0, 0
    expected_total_max = int(total_marks_str) if total_marks_str else 0
    for w in rows[subject_row_idx]:
        if "Total" in w['text']: break
        if re.match(r'^[A-Z0-9]+$', w['text']):
            subjects.append({"code": w['text'], "x0": w['x0'], "x1": w['x1'], "credit": 0})
    credit_row = rows[subject_row_idx + 1]
    if "CREDIT" in " ".join(w['text'] for w in credit_row):
        for w in credit_row:
            if w['text'] == "CREDIT": continue
            if re.match(r'^\d+$', w['text']):
                closest = min(subjects, key=lambda s: abs(s['x0'] - w['x0']))
                closest['credit'] = int(w['text'])
    printed_max_sum = 0
    for r in rows:
        text = " ".join(w['text'] for w in r)
        if text.startswith("TOTAL") and "100/40" in text:
            for tk in text.split():
                if "/" in tk and tk not in ("GRADE", "TOTAL"):
                    parts = tk.split("/")
                    if parts[0].isdigit(): printed_max_sum += int(parts[0])
            break
    return subjects, expected_total_max, printed_max_sum


def parse_single_block(block, subjects, expected_total_max, printed_max_sum):
    raw = "\n".join(" ".join(w['text'] for w in r) for r in block)
    r0_parts = [w['text'] for w in block[0]]
    roll_no = r0_parts[0]
    result_status = r0_parts[-1]
    name_parts = []
    for p in r0_parts[1:-1]:
        if re.match(r'^\d{4,5}$', p): break
        name_parts.append(p)
    name = " ".join(name_parts)

    is_supply = "Winter -" in raw or "Summer -" in raw
    grade_row_idx = -2 if is_supply else -1

    # SGPA
    r1_parts = [w['text'] for w in block[1]]
    sgpa = None
    try:
        lv = r1_parts[-1]
        if "." in lv and len(lv.split(".")) == 2: sgpa = float(lv)
    except Exception: pass

    # Total marks
    total_marks = 0
    if len(block) >= 5:
        r4_parts = [w['text'] for w in block[4]]
        if r4_parts and r4_parts[0].isdigit(): total_marks = int(r4_parts[0])

    # Grades
    grades_tokens = [w['text'] for w in block[grade_row_idx] if w['text'] != '|']
    student_subjects = []
    for i, sub in enumerate(subjects):
        gs = grades_tokens[i] if i < len(grades_tokens) else "0/FF/0"
        gs = re.sub(r'\(G-\d+\)', '', gs)
        pts, g = 0.0, "FF"
        if "/" in gs:
            gp = gs.split("/")
            if len(gp) == 3:
                g = gp[1]
                try: pts = float(gp[2]) if gp[2] else 0.0
                except Exception: pass
        elif gs == "AU": g = "AU"
        student_subjects.append({"code": sub['code'], "credit": sub['credit'], "grade": g, "grade_point": pts})

    calc_pts = sum(s['grade_point'] for s in student_subjects)
    reg_cr = sum(s['credit'] for s in student_subjects if s['grade'] != 'AU')
    calc_sgpa = round(calc_pts / reg_cr, 2) if reg_cr > 0 else 0.0

    sgpa_match = True
    if result_status == 'PASS':
        if sgpa is None or abs(calc_sgpa - sgpa) > 0.05: sgpa_match = False
    else:
        if sgpa is not None and abs(calc_sgpa - sgpa) > 0.05: sgpa_match = False

    # Totals row
    tr = block[grade_row_idx - 1]
    if len([w for w in tr if w['text'] == '|']) > 0 and len(tr) < 3:
        tr = block[grade_row_idx - 2]
    calc_total = sum(int(t.replace("(","").replace(")","")) for t in [w['text'] for w in tr if w['text'] != '|']
                     if t.replace("(","").replace(")","").isdigit())
    marks_match = (calc_total == total_marks)
    token_match = (len(grades_tokens) == len(subjects))

    gap = total_marks - calc_total
    derived_max = expected_total_max - printed_max_sum
    gap_exceeds = unverifiable = False
    if not marks_match:
        if gap > derived_max or gap < 0: gap_exceeds = True
        elif gap > 0 and derived_max > 0: unverifiable = True

    flags = []
    if not sgpa_match: flags.append("sgpa_mismatch")
    if gap_exceeds: flags.append("gap_exceeds_max_possible")
    elif unverifiable: flags.append("unverifiable_unscored_subject_present")
    elif not marks_match: flags.append("marks_mismatch_other")
    if not token_match: flags.append("token_count_mismatch")

    return {
        "roll_no": roll_no, "name": name, "result": result_status,
        "passed_all": len(flags) == 0, "flags": flags,
        "gap": gap, "derived_max": derived_max,
        "block_len": len(block), "subject_count": len(subjects),
        "raw": raw
    }


def run_on_pdf(path):
    total, passed, exceptions = 0, 0, []
    flag_counts = {}
    unexplained = []     # flags NOT in the known-good set
    layout_issues = []   # pages where header couldn't be parsed

    with pdfplumber.open(path) as pdf:
        page_count = len(pdf.pages)
        for pidx in range(page_count):
            page = pdf.pages[pidx]
            rows = extract_rows(page)
            subjects, exp_max, printed_sum = parse_header(rows)
            if not subjects:
                layout_issues.append(f"Page {pidx+1}: no parseable header found")
                continue

            blocks, cur_block = [], []
            for r in rows:
                if not r: continue
                text = " ".join(w['text'] for w in r)
                fw = r[0]['text']
                if any(text.startswith(m) for m in KNOWN_FOOTER_MARKERS):
                    if cur_block: blocks.append(cur_block); cur_block = []
                    continue
                if ROLL_PATTERN.match(fw) and ("PASS" in text or "FAIL" in text):
                    if cur_block: blocks.append(cur_block)
                    cur_block = [r]
                elif cur_block:
                    cur_block.append(r)
            if cur_block: blocks.append(cur_block)

            for b in blocks:
                total += 1
                try:
                    res = parse_single_block(b, subjects, exp_max, printed_sum)
                    if res['passed_all']:
                        passed += 1
                    else:
                        for f in res['flags']:
                            flag_counts[f] = flag_counts.get(f, 0) + 1
                        novel = [f for f in res['flags'] if f not in KNOWN_FLAGS]
                        if novel:
                            unexplained.append(res)
                except Exception as e:
                    exceptions.append({
                        "block_text": "\n".join(" ".join(w['text'] for w in r) for r in b),
                        "error": str(e),
                        "traceback": traceback.format_exc()
                    })

    return {
        "pages": page_count,
        "total": total,
        "passed": passed,
        "flag_counts": flag_counts,
        "unexplained": unexplained,
        "layout_issues": layout_issues,
        "exceptions": exceptions
    }


def main():
    pdfs = [
        f"{PROJECT_ROOT}/Results Dataset/127f859d-372d-4367-862e-1a9147714a74.pdf",
        f"{PROJECT_ROOT}/Results Dataset/6757_results_groupA.pdf",
        f"{PROJECT_ROOT}/Results Dataset/6757_results_groupB.pdf",
        f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_1(APRIL_2024) - CR Report (2).pdf",
        f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_2(May_2025) - CR Report.pdf",
    ]
    already_validated = [
        "cse 5 reg.pdf",
        "Bachelor of Technology (Artificial Intelligence (AI) and Data Science)_3(DECEMBER_2025) - CR Report (1).pdf"
    ]
    print(f"Already validated: {already_validated}\n")
    print("=" * 70)
    for path in pdfs:
        name = path.split("/")[-1]
        print(f"\n### PDF: {name}")
        res = run_on_pdf(path)
        print(f"  Pages: {res['pages']}")
        print(f"  Total blocks: {res['total']}")
        print(f"  Passed cleanly: {res['passed']}")
        print(f"  Needs-review flags: {res['flag_counts']}")
        print(f"  Parsing exceptions: {len(res['exceptions'])}")
        print(f"  Layout/header issues: {len(res['layout_issues'])}")

        if res['layout_issues']:
            for li in res['layout_issues']:
                print(f"    LAYOUT: {li}")

        if res['exceptions']:
            for ex in res['exceptions'][:2]:
                print(f"    EXCEPTION: {ex['error']}")
                print(f"    BLOCK:\n{ex['block_text'][:500]}")

        if res['unexplained']:
            print(f"  UNEXPLAINED ANOMALIES ({len(res['unexplained'])}):")
            for u in res['unexplained']:
                print(f"    Roll: {u['roll_no']}, Flags: {u['flags']}, Gap: {u['gap']}, Subjects: {u['subject_count']}, BlockLen: {u['block_len']}")
                print(f"    RAW BLOCK:\n{u['raw']}\n")
        else:
            print("  No unexplained anomalies.")
        print("=" * 70)


if __name__ == "__main__":
    main()
