"""
Full dataset diagnostic — imports directly from ingestion/parse_tabular.py.
Verifies all three fixes are active.
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import sys
sys.path.insert(0, f"{PROJECT_ROOT}")

from ingestion.parse_tabular import extract_rows, parse_header, parse_single_block
import pdfplumber
import re
import traceback

FOOTER_MARKERS = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]
ROLL_PATTERN = re.compile(r'^\d{10,15}$')

KNOWN_FLAGS = {
    "unverifiable_unscored_subject_present",
}

def run_on_pdf(path):
    total, passed, exceptions = 0, 0, []
    flag_counts = {}
    unexplained = []
    layout_issues = []

    with pdfplumber.open(path) as pdf:
        page_count = len(pdf.pages)
        # Schema check (Fix 3)
        full_text = " ".join(p.extract_text() or "" for p in pdf.pages[:3])
        if "Total Marks(" not in full_text:
            return {
                "pages": page_count, "total": 0, "passed": 0,
                "flag_counts": {}, "unexplained": [], "exceptions": [],
                "layout_issues": ["SCHEMA_UNSUPPORTED: no 'Total Marks(' header — flat-summary-table format"],
                "schema": "unsupported"
            }

        for pidx in range(page_count):
            page = pdf.pages[pidx]
            rows = extract_rows(page)
            subjects, exp_max, printed_sum = parse_header(rows)
            if not subjects:
                layout_issues.append(f"Page {pidx+1}: no parseable header")
                continue

            blocks, cur_block = [], []
            result_keywords = ["PASS", "FAIL", "COPYCASE", "WITHHELD"]  # Fix 3b
            for r in rows:
                if not r: continue
                text = " ".join(w['text'] for w in r)
                fw = r[0]['text']
                if any(text.startswith(m) for m in FOOTER_MARKERS):
                    if cur_block: blocks.append(cur_block); cur_block = []
                    continue
                if ROLL_PATTERN.match(fw) and any(kw in text for kw in result_keywords):
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
                            unexplained.append({
                                "roll": res['roll_no'],
                                "flags": res['flags'],
                                "raw": res['raw_block']
                            })
                except Exception as e:
                    exceptions.append({"error": str(e), "tb": traceback.format_exc()})

    return {
        "pages": page_count, "total": total, "passed": passed,
        "flag_counts": flag_counts, "unexplained": unexplained,
        "layout_issues": layout_issues, "exceptions": exceptions,
        "schema": "supported"
    }


def main():
    docs = {
        "cse5_reg (VALIDATED)": f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf",
        "AI-DS supply (VALIDATED)": f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Artificial Intelligence (AI) and Data Science)_3(DECEMBER_2025) - CR Report (1).pdf",
        "127f859d": f"{PROJECT_ROOT}/Results Dataset/127f859d-372d-4367-862e-1a9147714a74.pdf",
        "groupA": f"{PROJECT_ROOT}/Results Dataset/6757_results_groupA.pdf",
        "groupB": f"{PROJECT_ROOT}/Results Dataset/6757_results_groupB.pdf",
        "CSE_1 Apr2024": f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_1(APRIL_2024) - CR Report (2).pdf",
        "CSE_2 May2025": f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_2(May_2025) - CR Report.pdf",
    }

    grand_total = grand_passed = 0
    print("\n" + "="*72)
    for name, path in docs.items():
        res = run_on_pdf(path)
        schema_tag = " [SCHEMA_UNSUPPORTED]" if res.get("schema") == "unsupported" else ""
        print(f"\n### {name}{schema_tag}")
        print(f"  Pages={res['pages']}  Blocks={res['total']}  Passed={res['passed']}  "
              f"Exceptions={len(res['exceptions'])}")
        if res['flag_counts']:
            print(f"  Flags: {res['flag_counts']}")
        if res['layout_issues']:
            for li in res['layout_issues'][:2]:
                print(f"  LAYOUT: {li}")
        if res['unexplained']:
            print(f"  UNEXPLAINED ({len(res['unexplained'])}):")
            for u in res['unexplained'][:3]:
                print(f"    Roll={u['roll']}  Flags={u['flags']}")
                print(f"    RAW:\n{u['raw'][:400]}")
        if res['exceptions']:
            for ex in res['exceptions'][:1]:
                print(f"  EXCEPTION: {ex['error']}")
        grand_total += res['total']
        grand_passed += res['passed']
        print("="*72)

    print(f"\nGRAND TOTAL: {grand_passed}/{grand_total} passed cleanly across all documents")

if __name__ == "__main__":
    main()
