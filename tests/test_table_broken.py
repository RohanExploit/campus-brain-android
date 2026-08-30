import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import sys
sys.path.insert(0, f"{PROJECT_ROOT}")
from ingestion.parse import check_table_broken

def main():
    print("=== Testing check_table_broken() ===")

    clean_table = """# Some Markdown
Here is a table:
| Header 1 | Header 2 | Header 3 |
|---|---|---|
| Cell 1 | Cell 2 | Cell 3 |
| Cell 4 | Cell 5 | Cell 6 |

Some text after.
"""

    broken_table = """# Some Markdown
Here is a table:
| Header 1 | Header 2 | Header 3 |
|---|---|---|
| Cell 1 | Cell 2 |
| Cell 4 | Cell 5 | Cell 6 | Cell 7 |
"""

    no_table = """# Just some text
No pipes here.
It is very clean.
"""

    docx_false_positive = """# Some text
Here is a random | pipe character but no table.
Wait, | what if there are multiple | ?
"""

    print(f"Clean Table (Expect False): {check_table_broken(clean_table)}")
    print(f"Broken Table (Expect True): {check_table_broken(broken_table)}")
    print(f"No Table (Expect False): {check_table_broken(no_table)}")
    print(f"Docx False Positive (Expect True, because it lacks --|--): {check_table_broken(docx_false_positive)}")

    # Wait, what if docx false positive has a pipe but is NOT a table?
    # Our function requires --|-- if | is present, otherwise it returns True (broken).
    # This means any doc with | but no table is flagged as broken table.

if __name__ == "__main__":
    main()
