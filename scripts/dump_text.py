import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import pdfplumber

def extract_text():
    path = f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf"
    with pdfplumber.open(path) as pdf:
        with open(f"{PROJECT_ROOT}/Results Dataset/raw_text.txt", "w", encoding="utf-8") as f:
            for i in range(len(pdf.pages)):
                text = pdf.pages[i].extract_text(layout=True)
                f.write(text + "\n")

if __name__ == '__main__':
    extract_text()
