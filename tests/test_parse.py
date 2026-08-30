"""
Manual Docling parse harness (NOT a pytest test).

Previously this ran DocumentConverter().convert(...) at MODULE IMPORT, which hung
pytest collection for the whole suite and overwrote a PII-derived .md on every
import. It is now guarded behind __main__ so `pytest` can import it instantly and
it only runs (and writes) on explicit manual invocation:  python tests/test_parse.py
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
from pathlib import Path


def run():
    from docling.document_converter import DocumentConverter
    file_path = Path(f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf")
    out_md_path = Path(f"{PROJECT_ROOT}/cse_5_reg_parsed.md")
    converter = DocumentConverter()
    result = converter.convert(file_path)
    md_text = result.document.export_to_markdown()
    with open(out_md_path, "w", encoding="utf-8") as f:
        f.write(md_text)
    print(f"Saved to {out_md_path}")


if __name__ == "__main__":
    run()
