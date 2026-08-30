"""
Targeted investigation:
1. CSE_1 R1 column layout across PASS/FAIL/blank-SGPA cases
2. CSE_2 subject-code wrapping — raw header rows showing splits
3. Cross-check same layout variants in 127f859d, groupA, groupB
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

FOOTER_MARKERS = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]
ROLL_PATTERN = re.compile(r'^\d{10,15}$')

def get_student_blocks(page):
    rows = extract_rows(page)
    # Detect "Total Marks" header row for subject header
    header_rows = []
    for i, r in enumerate(rows):
        text = " ".join(w['text'] for w in r)
        if "Total Marks(" in text:
            # Capture wider context: rows 0 through i+3
            header_rows = [(j, " ".join(w['text'] for w in rows[j])) for j in range(max(0,i-8), min(len(rows), i+4))]
            break

    blocks, cur_block = [], []
    for r in rows:
        if not r: continue
        text = " ".join(w['text'] for w in r)
        fw = r[0]['text']
        if any(text.startswith(m) for m in FOOTER_MARKERS):
            if cur_block: blocks.append(cur_block); cur_block = []
            continue
        if ROLL_PATTERN.match(fw) and any(s in text for s in ["PASS", "FAIL", "COPYCASE", "WITHHELD"]):
            if cur_block: blocks.append(cur_block)
            cur_block = [r]
        elif cur_block:
            cur_block.append(r)
    if cur_block: blocks.append(cur_block)
    return blocks, header_rows


def analyze_r1(block):
    """Return R1 tokens and attempt to identify SGPA by pattern."""
    r1_tokens = [w['text'] for w in block[1]]
    r0_tokens = [w['text'] for w in block[0]]
    result = r0_tokens[-1]

    # Pattern: SGPA is a float in range [0.0, 10.0] with exactly one decimal place (e.g. 7.66)
    sgpa_candidates = []
    for i, t in enumerate(r1_tokens):
        if re.match(r'^\d+\.\d+$', t):
            val = float(t)
            if 0.0 <= val <= 10.0:
                sgpa_candidates.append((i, t, val))

    return {
        "result": result,
        "r1_raw": " ".join(r1_tokens),
        "r1_len": len(r1_tokens),
        "sgpa_candidates": sgpa_candidates,
        "last_token": r1_tokens[-1] if r1_tokens else "",
        "all_floats": [(i, t) for i, t in enumerate(r1_tokens) if re.match(r'^\d+\.\d+$', t)],
    }


def show_header_raw(path, page_idx=0):
    """Dump raw row text of header region from a page."""
    with pdfplumber.open(path) as pdf:
        page = pdf.pages[page_idx]
        rows = extract_rows(page)
        for i, r in enumerate(rows):
            text = " ".join(w['text'] for w in r)
            if "Total Marks(" in text:
                # Print rows from credit row onward
                print(f"  Header context (rows {max(0,i-2)} to {min(len(rows)-1, i+5)}):")
                for j in range(max(0, i-2), min(len(rows), i+6)):
                    print(f"    Row {j:02d}: {' '.join(w['text'] for w in rows[j])}")
                    # Also print individual words with x0 for code-splitting analysis
                    if j == i:
                        print(f"    Words: {[(w['text'], round(w['x0'],1)) for w in rows[j]]}")
                    if j == i+1:
                        print(f"    Words: {[(w['text'], round(w['x0'],1)) for w in rows[j]]}")
                return


def main():
    CSE1 = f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_1(APRIL_2024) - CR Report (2).pdf"
    CSE2 = f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_2(May_2025) - CR Report.pdf"
    UUID = f"{PROJECT_ROOT}/Results Dataset/127f859d-372d-4367-862e-1a9147714a74.pdf"
    GRPA = f"{PROJECT_ROOT}/Results Dataset/6757_results_groupA.pdf"
    GRPB = f"{PROJECT_ROOT}/Results Dataset/6757_results_groupB.pdf"

    # =========================================================
    # PART 1: CSE_1 — R1 layout for PASS + FAIL + blank-SGPA
    # =========================================================
    print("\n" + "="*70)
    print("PART 1: CSE_1 R1 analysis (PASS and FAIL cases)")
    print("="*70)
    with pdfplumber.open(CSE1) as pdf:
        samples = []
        for pidx in range(len(pdf.pages)):
            blocks, _ = get_student_blocks(pdf.pages[pidx])
            for b in blocks:
                if len(b) < 2: continue
                info = analyze_r1(b)
                r0 = " ".join(w['text'] for w in b[0])
                info['roll'] = b[0][0]['text']
                info['r0'] = r0
                samples.append(info)
            if len(samples) >= 20: break

    # Show 5 PASS and 5 FAIL
    pass_samples = [s for s in samples if s['result'] == 'PASS'][:5]
    fail_samples = [s for s in samples if s['result'] == 'FAIL'][:5]

    for label, group in [("PASS", pass_samples), ("FAIL", fail_samples)]:
        print(f"\n  --- {label} cases ---")
        for s in group:
            cands = s['sgpa_candidates']
            last3 = s['r1_raw'].split()[-3:] if len(s['r1_raw'].split()) >= 3 else s['r1_raw'].split()
            print(f"  Roll: {s['roll']:18s}  Result: {s['result']:12s}  R1_len: {s['r1_len']:3d}  "
                  f"Last3: {last3}  SGPA_candidates(0-10 float): {cands}  "
                  f"R1_raw: {s['r1_raw'][:80]}")

    # Pattern reliability check
    print("\n  Pattern reliability (0-10 float): does it uniquely identify SGPA?")
    zero_cands = [s for s in samples if len(s['sgpa_candidates']) == 0 and s['result'] == 'PASS']
    print(f"  PASS students with exactly 1 candidate: {len([s for s in pass_samples if len(s['sgpa_candidates'])==1])}")
    print(f"  PASS students with 0 candidates (no float in range): {len(zero_cands)}")
    print(f"  PASS students with >1 candidates (ambiguous): {len([s for s in samples if len(s['sgpa_candidates']) > 1 and s['result']=='PASS'])}")

    # =========================================================
    # PART 2: CSE_2 — raw header rows showing code splits
    # =========================================================
    print("\n" + "="*70)
    print("PART 2: CSE_2 raw header — subject code wrapping")
    print("="*70)
    print("\nPage 0 header:")
    show_header_raw(CSE2, 0)

    # Now count phantom credits
    print("\n  Credit assignment analysis (first 5 student blocks, page 0):")
    with pdfplumber.open(CSE2) as pdf:
        blocks2, _ = get_student_blocks(pdf.pages[0])
        for b in blocks2[:5]:
            # show the raw rows per block
            r0_text = " ".join(w['text'] for w in b[0])
            r1_text = " ".join(w['text'] for w in b[1]) if len(b) > 1 else ""
            print(f"\n  Roll: {b[0][0]['text']}, R0: {r0_text[:60]}")
            print(f"         R1: {r1_text}")
            print(f"         Block len: {len(b)}")
            for ri, r in enumerate(b):
                print(f"         [{ri:+d}/{ri-len(b)}] {' '.join(w['text'] for w in r)}")

    # =========================================================
    # PART 3: Cross-check other docs for same header variants
    # =========================================================
    print("\n" + "="*70)
    print("PART 3: Other docs header check")
    print("="*70)
    for name, path in [("127f859d", UUID), ("groupA", GRPA), ("groupB", GRPB)]:
        print(f"\n--- {name} (page 0) header ---")
        with pdfplumber.open(path) as pdf:
            rows = extract_rows(pdf.pages[0])
            for i, r in enumerate(rows[:15]):
                text = " ".join(w['text'] for w in r)
                print(f"  Row {i:02d}: {text[:100]}")
            # Check SGPA header row specifically
            for i, r in enumerate(rows):
                text = " ".join(w['text'] for w in r)
                if "SGPA" in text:
                    print(f"  SGPA row ({i}): {text}")
                    break

if __name__ == "__main__":
    main()
