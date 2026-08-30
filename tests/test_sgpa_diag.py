"""
Diagnostic: Compare SGPA mismatches across CSE_1, CSE_2, cse_5_reg
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pdfplumber
import re

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
            subjects.append({"code": w['text'], "credit": 0})
    credit_row = rows[subject_row_idx + 1]
    if "CREDIT" in " ".join(w['text'] for w in credit_row):
        for w in credit_row:
            if w['text'] == "CREDIT": continue
            if re.match(r'^\d+$', w['text']):
                # simpler: assign in order
                unassigned = [s for s in subjects if s['credit'] == 0]
                if unassigned: unassigned[0]['credit'] = int(w['text'])
    # Redo credit assignment properly by position order
    credit_nums = [w['text'] for w in credit_row if re.match(r'^\d+$', w['text'])]
    for i, s in enumerate(subjects):
        s['credit'] = int(credit_nums[i]) if i < len(credit_nums) else 0
    return subjects, expected_total_max, 0

FOOTER_MARKERS = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]
ROLL_PATTERN = re.compile(r'^\d{10,15}$')

def dump_block_detail(block, subjects, label=""):
    """Extract and return per-block diagnostic data"""
    r0_parts = [w['text'] for w in block[0]]
    roll_no = r0_parts[0]
    result_status = r0_parts[-1]
    name_parts = []
    for p in r0_parts[1:-1]:
        if re.match(r'^\d{4,5}$', p): break
        name_parts.append(p)
    name = " ".join(name_parts)

    is_supply = "Winter -" in "\n".join(" ".join(w['text'] for w in r) for r in block)
    grade_row_idx = -2 if is_supply else -1

    # Print all rows with index
    rows_dump = []
    for i, r in enumerate(block):
        rows_dump.append(f"  [{i:+d}/{i-len(block)}] {' '.join(w['text'] for w in r)}")

    # R1 — find SGPA (current logic: last token if it's a float)
    r1_tokens = [w['text'] for w in block[1]]
    sgpa_parsed = None
    try:
        lv = r1_tokens[-1]
        if "." in lv and len(lv.split(".")) == 2: sgpa_parsed = float(lv)
    except Exception: pass

    # Grades row
    grades_row_tokens = [w['text'] for w in block[grade_row_idx] if w['text'] != '|']

    student_subjects = []
    for i, sub in enumerate(subjects):
        gs = grades_row_tokens[i] if i < len(grades_row_tokens) else "?"
        gs_clean = re.sub(r'\(G-\d+\)', '', gs)
        pts, g, cr = 0.0, "FF", sub['credit']
        if "/" in gs_clean:
            gp = gs_clean.split("/")
            if len(gp) == 3:
                g = gp[1]
                try: pts = float(gp[2]) if gp[2] else 0.0
                except Exception: pass
        elif gs_clean == "AU": g = "AU"
        student_subjects.append({"code": sub['code'], "credit": cr, "grade": g, "pts": pts})

    total_pts = sum(s['pts'] for s in student_subjects)
    reg_cr_excl_au = sum(s['credit'] for s in student_subjects if s['grade'] != 'AU')
    reg_cr_incl_au = sum(s['credit'] for s in student_subjects)

    calc_sgpa_excl = round(total_pts / reg_cr_excl_au, 2) if reg_cr_excl_au else 0
    calc_sgpa_incl = round(total_pts / reg_cr_incl_au, 2) if reg_cr_incl_au else 0

    return {
        "roll": roll_no,
        "name": name,
        "result": result_status,
        "printed_sgpa": sgpa_parsed,
        "r1_raw": " ".join(r1_tokens),
        "total_pts": total_pts,
        "reg_cr_excl_au": reg_cr_excl_au,
        "reg_cr_incl_au": reg_cr_incl_au,
        "calc_sgpa_excl_au": calc_sgpa_excl,
        "calc_sgpa_incl_au": calc_sgpa_incl,
        "subjects": student_subjects,
        "block_rows": rows_dump,
        "block_len": len(block),
        "grade_row_tokens": grades_row_tokens,
    }

def get_blocks_from_pdf(path, max_pages=None):
    blocks_out = []
    header_sample = None
    with pdfplumber.open(path) as pdf:
        pages = pdf.pages[:max_pages] if max_pages else pdf.pages
        for pidx, page in enumerate(pages):
            rows = extract_rows(page)
            subjects, _, _ = parse_header(rows)
            if not subjects: continue
            if header_sample is None:
                # Capture raw header rows for comparison
                header_rows = []
                for r in rows[:15]:
                    header_rows.append(" ".join(w['text'] for w in r))
                header_sample = header_rows

            blocks, cur_block = [], []
            for r in rows:
                if not r: continue
                text = " ".join(w['text'] for w in r)
                fw = r[0]['text']
                if any(text.startswith(m) for m in FOOTER_MARKERS):
                    if cur_block: blocks.append((cur_block, subjects))
                    cur_block = []
                    continue
                if ROLL_PATTERN.match(fw) and ("PASS" in text or "FAIL" in text):
                    if cur_block: blocks.append((cur_block, subjects))
                    cur_block = [r]
                elif cur_block:
                    cur_block.append(r)
            if cur_block: blocks.append((cur_block, subjects))

            for b, subs in blocks:
                d = dump_block_detail(b, subs)
                d['page'] = pidx + 1
                blocks_out.append(d)
    return blocks_out, header_sample


def main():
    pdfs = {
        "cse5_reg": f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf",
        "cse1_2024": f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_1(APRIL_2024) - CR Report (2).pdf",
        "cse2_2025": f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_2(May_2025) - CR Report.pdf",
    }

    results = {}
    headers = {}
    for key, path in pdfs.items():
        # Only first 3 pages per doc to keep output manageable
        blocks, hdr = get_blocks_from_pdf(path, max_pages=3)
        results[key] = blocks
        headers[key] = hdr

    # 1. Header layout comparison
    print("\n" + "="*70)
    print("HEADER COMPARISON (first parseable page, first 12 rows)")
    print("="*70)
    for key in pdfs:
        print(f"\n--- {key} ---")
        if headers[key]:
            for i, row in enumerate(headers[key][:12]):
                print(f"  Row {i:02d}: {row}")

    # 2. Five sgpa_mismatch samples per doc
    print("\n" + "="*70)
    print("SGPA MISMATCH SAMPLES — 5 per doc")
    print("="*70)
    for key in ["cse1_2024", "cse2_2025", "cse5_reg"]:
        mismatches = [b for b in results[key]
                      if b['printed_sgpa'] is not None
                      and b['result'] == 'PASS'
                      and abs(b['calc_sgpa_excl_au'] - b['printed_sgpa']) > 0.05]
        clean = [b for b in results[key]
                 if b['printed_sgpa'] is not None
                 and b['result'] == 'PASS'
                 and abs(b['calc_sgpa_excl_au'] - b['printed_sgpa']) <= 0.05]

        print(f"\n### {key}: {len(mismatches)} mismatches / {len([b for b in results[key] if b['result']=='PASS'])} PASS students (first 3 pages)")
        print(f"{'Roll':<18} {'Name':<30} {'Printed':>8} {'CalcExAU':>9} {'CalcInAU':>9} {'TotPts':>7} {'CrExAU':>7} {'CrInAU':>7} {'R1 raw'}")
        print("-"*120)
        for b in (mismatches[:5] if mismatches else []):
            print(f"{b['roll']:<18} {b['name'][:28]:<30} {str(b['printed_sgpa']):>8} {b['calc_sgpa_excl_au']:>9.2f} {b['calc_sgpa_incl_au']:>9.2f} {b['total_pts']:>7.1f} {b['reg_cr_excl_au']:>7} {b['reg_cr_incl_au']:>7}  {b['r1_raw']}")

        # Show a clean sample for contrast
        if clean:
            b = clean[0]
            print(f"  [CLEAN] {b['roll']:<15} {b['name'][:28]:<30} printed={b['printed_sgpa']} calc_excl={b['calc_sgpa_excl_au']} calc_incl={b['calc_sgpa_incl_au']} r1={b['r1_raw']}")

        # Show block structure for first mismatch
        if mismatches:
            b = mismatches[0]
            print(f"\n  Block rows for {b['roll']} (block_len={b['block_len']}):")
            for row in b['block_rows']:
                print(f"    {row}")
            print(f"  Grade tokens: {b['grade_row_tokens']}")
            print(f"  Subjects ({len(b['subjects'])}): {[(s['code'], s['credit'], s['grade'], s['pts']) for s in b['subjects']]}")

if __name__ == "__main__":
    main()
