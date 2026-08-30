"""
Adversarial trace for Task 1.
Extracts specific blocks from CSE_1 and CSE_2 using the production parser,
then dumps the raw text and the parsed block to verify correctness manually.
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import sys
import pdfplumber
sys.path.insert(0, f"{PROJECT_ROOT}")
from ingestion.parse_tabular import extract_rows, parse_header, parse_single_block
import re
ROLL_PATTERN = re.compile(r'^\d{10,15}$')
FOOTER_MARKERS = ["GRADE:", "Note :-", "AOO =", "Print By", "Cancel Seat No's"]

def trace_student(pdf_path, target_roll):
    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:
            rows = extract_rows(page)
            subjects, exp_max, printed_sum = parse_header(rows)
            if not subjects:
                continue

            blocks, cur_block = [], []
            result_keywords = ["PASS", "FAIL", "COPYCASE", "WITHHELD"]
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
                r0_text = " ".join(w['text'] for w in b[0])
                roll = r0_text.split()[0]
                if roll == target_roll:
                    print(f"\n{'='*80}")
                    print(f"FOUND TARGET ROLL: {target_roll}")
                    print("RAW BLOCK TEXT:")
                    raw_text = "\n".join(" ".join(w['text'] for w in r) for r in b)
                    print(raw_text)
                    print("-" * 40)
                    try:
                        res = parse_single_block(b, subjects, exp_max, printed_sum)
                        print("PARSER OUTPUT:")
                        print(f"  Roll: {res['roll_no']}")
                        print(f"  Name: {res['name']}")
                        print(f"  SGPA: {res.get('sgpa', 'None')}")
                        print(f"  Gap: {res.get('gap', 'None')} (Derived max: {res.get('derived_max')})")
                        print(f"  Passed All: {res['passed_all']}")
                        print(f"  Flags: {res.get('flags', [])}")
                        print(f"  Subjects Count: {len(res['subjects'])}")

                        credit_sum = sum(s['credit'] for s in res['subjects'])
                        print(f"  Total Credits Extracted: {credit_sum}")

                        print("  Subject breakdown (first 3 + last 3):")
                        subs = res['subjects']
                        show = subs[:3] + subs[-3:] if len(subs) > 6 else subs
                        for s in show:
                            print(f"    Code: {s['code']:15s}  Credit: {s['credit']}  Grade: {s['grade']:2s}  Pts: {s['grade_point']}")
                    except Exception as e:
                        print(f"EXCEPTION: {e}")
                    print(f"{'='*80}\n")
                    return

def main():
    CSE1 = f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_1(APRIL_2024) - CR Report (2).pdf"
    CSE2 = f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Computer Science and Engineering)_2(May_2025) - CR Report.pdf"

    # 24067571242100 (PASS from CSE1)
    trace_student(CSE1, "24067571242100")

    # 24067571242001 (FAIL from CSE1)
    trace_student(CSE1, "24067571242001")

    # 24067571242132 (WITHHELD from CSE2)
    trace_student(CSE2, "24067571242132")

if __name__ == "__main__":
    main()
