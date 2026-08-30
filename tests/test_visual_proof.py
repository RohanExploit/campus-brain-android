"""
Visual proof investigation:
1. CSE_1 — last 5 students, annotated R1 token dump
2. CSE_2 — 3 students with wrapped codes, y-coordinate clustering dump
3. groupA/B — confirm table structure and scope decision
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pdfplumber
import re

def extract_rows_with_y(page):
    """Return rows with full word metadata including y-coordinates."""
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

FOOTER_MARKERS = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]
ROLL_PATTERN = re.compile(r'^\d{10,15}$')

def collect_all_blocks(path):
    """Collect every student block across all pages, return list of (block, page_idx)."""
    all_blocks = []
    with pdfplumber.open(path) as pdf:
        for pidx, page in enumerate(pdf.pages):
            rows = extract_rows_with_y(page)
            # Check for header
            has_header = any("Total Marks(" in " ".join(w['text'] for w in r) for r in rows)
            if not has_header:
                continue
            blocks, cur_block = [], []
            for r in rows:
                if not r: continue
                text = " ".join(w['text'] for w in r)
                fw = r[0]['text']
                if any(text.startswith(m) for m in FOOTER_MARKERS):
                    if cur_block: blocks.append(cur_block); cur_block = []
                    continue
                if ROLL_PATTERN.match(fw) and any(s in text for s in ["PASS","FAIL","COPYCASE","WITHHELD"]):
                    if cur_block: blocks.append(cur_block)
                    cur_block = [r]
                elif cur_block:
                    cur_block.append(r)
            if cur_block: blocks.append(cur_block)
            for b in blocks:
                all_blocks.append((b, pidx))
    return all_blocks

def annotate_r1(block):
    """Print annotated R1 with float-pattern detection."""
    r0 = [w['text'] for w in block[0]]
    r1 = block[1] if len(block) > 1 else []
    roll = r0[0]
    result = r0[-1]
    tokens = [(w['text'], round(w['x0'],1), round(w['top'],1)) for w in r1]

    annotations = []
    for i, (txt, x, y) in enumerate(tokens):
        ann = txt
        if re.match(r'^\d+\.\d+$', txt):
            val = float(txt)
            if 0.0 <= val <= 10.0:
                ann = f"{txt}[=SGPA]"
            elif 10.0 < val <= 100.0:
                ann = f"{txt}[=PCT]"
        elif re.match(r'^\d+$', txt) and int(txt) > 100:
            ann = f"{txt}[=EXTTOTAL]"
        elif txt == 'AB':
            ann = f"{txt}[=PASS 8.5]"
        annotations.append((ann, x, y))
    return roll, result, tokens, annotations

def get_page_header_raw(path, page_idx):
    """Return all rows from a page with y-coords, focusing on header region."""
    with pdfplumber.open(path) as pdf:
        page = pdf.pages[page_idx]
        rows = extract_rows_with_y(page)
        return rows

def main():
    CSE1 = f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_1(APRIL_2024) - CR Report (2).pdf"
    CSE2 = f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_2(May_2025) - CR Report.pdf"
    GRPA = f"{PROJECT_ROOT}/Results Dataset/6757_results_groupA.pdf"

    # =========================================================
    # DELIVERABLE 1: CSE_1 — last 5 student blocks, annotated
    # =========================================================
    print("\n" + "="*70)
    print("DELIVERABLE 1: CSE_1 — Last 5 students, annotated R1 token dump")
    print("="*70)

    all_blocks_cse1 = collect_all_blocks(CSE1)
    last5 = all_blocks_cse1[-5:]

    for block, pidx in last5:
        roll, result, tokens, annotations = annotate_r1(block)
        print(f"\nRoll: {roll}  Result: {result}  Page: {pidx+1}  BlockLen: {len(block)}")
        print(f"  R0: {' '.join(w['text'] for w in block[0])}")
        print(f"  R1 raw tokens (text, x0, y):  {tokens}")
        print(f"  R1 annotated:  {[a[0] for a in annotations]}")
        # Float candidates
        floats_in_range = [(t, x) for t, x, _ in tokens
                           if re.match(r'^\d+\.\d+$', t) and 0.0 <= float(t) <= 10.0]
        print(f"  Float [0–10] candidates: {floats_in_range}")

    # Also: confirm CSE_1 header row for SGPA column order
    print("\n--- CSE_1 Header SGPA descriptor row ---")
    rows_p0 = get_page_header_raw(CSE1, 0)
    for i, r in enumerate(rows_p0[:15]):
        text = " ".join(w['text'] for w in r)
        if "SGPA" in text or "Percentage" in text or "Ext Total" in text or "Total(" in text:
            print(f"  Row {i:02d} (y={round(r[0]['top'],1)}): {text}")
            print(f"    Words+x: {[(w['text'], round(w['x0'],1)) for w in r]}")

    # =========================================================
    # DELIVERABLE 2: CSE_2 — y-coordinate clustering for 3 students
    # =========================================================
    print("\n" + "="*70)
    print("DELIVERABLE 2: CSE_2 — Header code-wrapping, y-coord clustering")
    print("="*70)

    # Raw words dump for the header region — page 0
    print("\n--- CSE_2 Page 0: Raw pdfplumber.extract_words() around subject-code row ---")
    with pdfplumber.open(CSE2) as pdf:
        words = pdf.pages[0].extract_words()

    # Find the cluster around the subject code rows (look for "Total Marks" region)
    total_marks_idx = None
    for i, w in enumerate(words):
        if "Total" in w['text'] and i+1 < len(words) and "Marks" in words[i+1]['text']:
            total_marks_idx = i
            break

    if total_marks_idx is not None:
        # Print 60 words before and 30 after Total Marks
        start = max(0, total_marks_idx - 60)
        end = min(len(words), total_marks_idx + 30)
        print(f"  Raw words [{start}:{end}] (showing text, x0, top/y):")
        prev_y = None
        for w in words[start:end]:
            y = round(w['top'], 1)
            marker = " ←NEW ROW" if prev_y is not None and abs(y - prev_y) > 3.0 else ""
            print(f"    '{w['text']:20s}'  x0={round(w['x0'],1):7.1f}  y={y:7.1f}{marker}")
            prev_y = y

    # Now show CREDIT row specifically
    print("\n--- CSE_2 Page 0: All rows where text contains 'CREDIT' or numeric-only near y=credit-row ---")
    rows_cse2_p0 = get_page_header_raw(CSE2, 0)
    for i, r in enumerate(rows_cse2_p0[:20]):
        text = " ".join(w['text'] for w in r)
        if "CREDIT" in text or (i >= 10 and i <= 15):
            y = round(r[0]['top'], 1)
            print(f"  Row {i:02d} (y={y}): {text}")
            print(f"    Words+x: {[(w['text'], round(w['x0'],1)) for w in r]}")

    # Pseudocode design: rejoin strategy
    print("\n--- DESIGN: Header rejoin pseudocode ---")
    print("""
  CURRENT (broken):
    subject_row = first row containing "Total Marks(...)"
    code_tokens = [w for w in subject_row if matches subject pattern]
    credit_row  = rows[subject_row_idx + 1]        ← picks up suffix fragments
    credit_vals = [w for w in credit_row if digit]  ← reads '1','2C','6A'... WRONG

  PROPOSED (fixed):
    subject_row = first row containing "Total Marks(...)"

    # Collect multi-row code fragments: subject_row AND the next row IF
    # it contains no 'CREDIT' keyword and its tokens look like code-suffixes
    # (short alphanumeric, no spaces, no 'CREDIT')
    next_row_text = join(rows[subject_row_idx + 1])
    if 'CREDIT' not in next_row_text:
        # Code split happened — suffix row. Skip it; codes are already
        # truncated but unique via x0 matching. Advance to credit row.
        credit_row_idx = subject_row_idx + 2
    else:
        credit_row_idx = subject_row_idx + 1

    credit_row = rows[credit_row_idx]
    credit_vals = [int(w) for w in credit_row if w.isdigit()]
    # Assign to subjects in order (left-to-right by x0)
    """)

    # Show 3 student blocks from CSE_2 with subject count mismatch
    print("\n--- CSE_2: 3 mismatch blocks (grade tokens vs subject count) ---")
    all_blocks_cse2 = collect_all_blocks(CSE2)
    shown = 0
    for block, pidx in all_blocks_cse2:
        if shown >= 3: break
        if len(block) < 2: continue
        r1_tokens = [w['text'] for w in block[1]]
        # Indicator: only 1-2 tokens on R1 (vs 7+ for cse5_reg) suggests new format
        roll = block[0][0]['text']
        result = block[0][-1]['text']
        if result != 'PASS': continue
        print(f"\n  Block (page {pidx+1}), Roll={roll}, result={result}, block_len={len(block)}")
        print(f"  R2 (Whole/Part row): {' '.join(w['text'] for w in block[2]) if len(block)>2 else ''}")
        # R2 col 3 = printed SGPA credit count
        r2 = [w['text'] for w in block[2]] if len(block) > 2 else []
        printed_cr = r2[3] if len(r2) > 3 else "?"
        print(f"  Printed credits (R2[3]): {printed_cr}")
        print(f"  R1 tokens: {r1_tokens}")
        # Grade row
        grade_row = [w['text'] for w in block[-1] if w['text'] != '|']
        print(f"  Grade tokens ({len(grade_row)}): {grade_row}")
        shown += 1

    # =========================================================
    # DELIVERABLE 3: groupA/B — confirm schema, scope decision
    # =========================================================
    print("\n" + "="*70)
    print("DELIVERABLE 3: groupA — full schema inspection")
    print("="*70)
    with pdfplumber.open(GRPA) as pdf:
        print(f"  Total pages: {len(pdf.pages)}")
        page = pdf.pages[0]
        words = page.extract_words()
        rows = extract_rows_with_y(page)
        print(f"  Total words on page 0: {len(words)}")
        print(f"  Total y-clustered rows: {len(rows)}")
        print("\n  First 5 rows (full word dump):")
        for i, r in enumerate(rows[:5]):
            print(f"    Row {i}: {[(w['text'], round(w['x0'],1)) for w in r]}")
        # Check for tables via pdfplumber's table extractor
        tables = page.extract_tables()
        print(f"\n  pdfplumber.extract_tables() count: {len(tables)}")
        if tables:
            print(f"  Table[0] shape: {len(tables[0])} rows x {len(tables[0][0]) if tables[0] else 0} cols")
            print(f"  Table[0][0] (header): {tables[0][0]}")
            print(f"  Table[0][1] (first data row): {tables[0][1] if len(tables[0]) > 1 else 'N/A'}")
        # Check unique column headers for schema identification
        if rows:
            print(f"\n  Row 0 (column headers): {[w['text'] for w in rows[0]]}")
            print(f"  Row 1 (first student): {[w['text'] for w in rows[1]]}")
            print(f"  Row 2 (second student): {[w['text'] for w in rows[2]]}")

if __name__ == "__main__":
    main()
