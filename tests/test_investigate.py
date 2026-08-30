import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pytest
pytest.skip(
    "manual PDF-parsing diagnostic; depends on scripts/build_parser not on sys.path",
    allow_module_level=True,
)
import pdfplumber
import build_parser

def investigate():
    cse_pdf = f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf"
    aids_pdf = f"{PROJECT_ROOT}/Results Dataset/Bachelor of Technology (Artificial Intelligence (AI) and Data Science)_3(DECEMBER_2025) - CR Report (1).pdf"

    # 1. Look for FAIL students
    print("--- FAIL STUDENTS ---")
    with pdfplumber.open(cse_pdf) as pdf:
        for page_idx in range(4): # Just first 4 pages
            page = pdf.pages[page_idx]
            rows = build_parser.extract_rows(page)
            subjects = build_parser.parse_header(rows)
            if not subjects: continue
            blocks = build_parser.parse_student_blocks(rows, subjects)
            for b in blocks:
                if b['result'] == 'FAIL':
                    print(f"Roll: {b['roll_no']}, SGPA: {b['sgpa']}, Name: {b['name']}")

    # 2. Look for AI DS Header info for BTES211P
    print("\n--- AI DS HEADER ---")
    with pdfplumber.open(aids_pdf) as pdf:
        page = pdf.pages[0]
        rows = build_parser.extract_rows(page)
        for i, r in enumerate(rows[:20]):
            text = " ".join([w['text'] for w in r])
            if "BTES211P" in text or "TOTAL" in text or "CREDIT" in text or "MARKS" in text.upper():
                print(f"Row {i:03d}: {text}")

        # 3. Look at Supply sheet students for marks_match gap
        print("\n--- SUPPLY STUDENTS GAP ---")
        subjects = build_parser.parse_header(rows)
        blocks = build_parser.parse_student_blocks(rows, subjects)
        for b in blocks:
            gap = b['total_marks'] - b['validation']['calc_total_marks']
            print(f"Roll: {b['roll_no']}, Printed Total: {b['total_marks']}, Calc: {b['validation']['calc_total_marks']}, Gap: {gap}")

if __name__ == "__main__":
    investigate()
