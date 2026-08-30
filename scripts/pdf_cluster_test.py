import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pdfplumber

def main():
    pdf_path = f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf"

    with pdfplumber.open(pdf_path) as pdf:
        # Step B1: Extract words on page 1 only
        page = pdf.pages[0]
        words = page.extract_words()

    if not words:
        print("No words found on page 1.")
        return

    # Step B2: Cluster words by y-coordinate (allow ~3px tolerance)
    # Sort words primarily by 'top' coordinate, then 'x0'
    words_sorted_by_y = sorted(words, key=lambda w: (w['top'], w['x0']))

    rows = []
    current_row = []
    current_y = None

    TOLERANCE = 3.0

    for word in words_sorted_by_y:
        word_top = word['top']

        if current_y is None:
            current_y = word_top
            current_row.append(word)
        else:
            if abs(word_top - current_y) <= TOLERANCE:
                current_row.append(word)
                # optionally update current_y to a rolling average, but this is simple enough
            else:
                rows.append(current_row)
                current_row = [word]
                current_y = word_top

    if current_row:
        rows.append(current_row)

    # Print the reconstructed rows
    print(f"Total reconstructed rows: {len(rows)}")

    # We'll print just the first 80 rows to see a few students
    for i, row in enumerate(rows[:80]):
        # Sort each row's words by x-coordinate just to be safe
        row_sorted = sorted(row, key=lambda w: w['x0'])
        row_text = " ".join([w['text'] for w in row_sorted])
        print(f"Row {i:03d}: {row_text}")

if __name__ == "__main__":
    main()
