"""Render every registered document (corpus/render_academic.DOCS plus every other renderer
module's DOCS) to corpus/out/.

    PYTHONUTF8=1 .venv312/Scripts/python.exe corpus/build_student_corpus.py
"""
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from corpus.render_academic import DOCS as ACADEMIC_DOCS, OUT  # noqa: E402
from corpus.render_notices import DOCS as NOTICES_DOCS  # noqa: E402
from corpus.render_services import DOCS as SERVICES_DOCS  # noqa: E402

DOCS = {**ACADEMIC_DOCS, **NOTICES_DOCS, **SERVICES_DOCS}
assert len(DOCS) == len(ACADEMIC_DOCS) + len(NOTICES_DOCS) + len(SERVICES_DOCS), (
    "a filename collided across renderer modules -- check the three DOCS dicts above"
)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, builder in DOCS.items():
        doc = builder()
        path = doc.save()
        print(f"rendered {name} -> {path}")
    print(f"rendered {len(DOCS)} PDFs -> {OUT}")


if __name__ == "__main__":
    main()
