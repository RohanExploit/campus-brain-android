import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
from pathlib import Path
from docling.document_converter import DocumentConverter

# NOTE: renamed from test_docling so pytest does NOT collect it — it runs heavy
# Docling AND overwrites a PII-derived .md. Manual only.
def run_docling():
    input_file = Path(f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf")
    converter = DocumentConverter()
    print("Running docling on", input_file)
    result = converter.convert(input_file)
    md_text = result.document.export_to_markdown()

    out_md = Path(f"{PROJECT_ROOT}/Results Dataset/cse 5 reg_docling.md")
    with open(out_md, "w", encoding="utf-8") as f:
        f.write(md_text)

    print(f"Saved docling output to {out_md}")

    # Print the first few lines that look like a table
    lines = md_text.split('\n')
    table_lines = [l for l in lines if '|' in l]
    print(f"Found {len(table_lines)} table lines. First 20:")
    for l in table_lines[:20]:
        print(l)

if __name__ == '__main__':
    run_docling()
