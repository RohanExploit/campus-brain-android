"""Corpus catalog: dedup, canonical selection, routing, exclusions.

Built over a throwaway tree so the assertions state what the catalog guarantees
rather than what today's Dataset/ happens to contain.
"""
import sqlite3
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from scripts import build_corpus_catalog as bcc


@pytest.fixture
def tree(tmp_path, monkeypatch):
    """A miniature repo with a known duplicate, a known basename collision,
    and one file per routing outcome."""
    (tmp_path / "corpus" / "out").mkdir(parents=True)
    (tmp_path / "Results Dataset" / "camelot_out").mkdir(parents=True)
    (tmp_path / "Dataset" / "student").mkdir(parents=True)
    (tmp_path / "data" / "tenants" / "tenant_1" / "raw").mkdir(parents=True)

    # Same bytes in Dataset/ and in a tenant raw/ -- the ingest-copy case.
    dup = b"identical bytes\n"
    (tmp_path / "Dataset" / "paper.pdf").write_bytes(dup)
    (tmp_path / "data" / "tenants" / "tenant_1" / "raw" / "paper.pdf").write_bytes(dup)

    # Same bytes loose and in a subfolder -- depth must break the tie.
    csv = b"a,b\n1,2\n"
    (tmp_path / "Dataset" / "marks.csv").write_bytes(csv)
    (tmp_path / "Dataset" / "student" / "marks.csv").write_bytes(csv)

    # Distinct bytes, same basename -- a collision, not a duplicate.
    (tmp_path / "corpus" / "out" / "notice.pdf").write_bytes(b"kriet notice\n")
    (tmp_path / "Dataset" / "notice.pdf").write_bytes(b"a different notice\n")

    # Routing samples.
    (tmp_path / "Results Dataset" / "results_2025.pdf").write_bytes(b"marks table\n")
    (tmp_path / "Results Dataset" / "camelot_out" / "table_2.csv").write_bytes(b"x\n")
    (tmp_path / "Dataset" / "SESSION-STUDENT-DETAILS-2.xlsx").write_bytes(b"pii\n")
    (tmp_path / "Dataset" / "helper.py").write_bytes(b"print(1)\n")
    (tmp_path / "Dataset" / "archive.zip").write_bytes(b"PK\x03\x04\n")

    monkeypatch.setattr(bcc, "PROJECT_ROOT", tmp_path)
    return tmp_path


def _scan(tree, catalog=None):
    catalog = catalog or (tree / "catalog.db")
    rc = bcc.main(["scan", "--catalog", str(catalog),
                   "--root", "corpus/out",
                   "--root", "Results Dataset",
                   "--root", "Dataset",
                   "--root", "data/tenants/tenant_1/raw"])
    assert rc == 0
    return sqlite3.connect(catalog)


def test_one_blob_row_per_hash_and_every_path_aliased(tree):
    conn = _scan(tree)
    n_files = sum(1 for p in tree.rglob("*") if p.is_file() and p.name != "catalog.db")
    n_blobs = conn.execute("SELECT COUNT(*) FROM blobs").fetchone()[0]
    n_alias = conn.execute("SELECT COUNT(*) FROM blob_aliases").fetchone()[0]

    assert n_alias == n_files, "every scanned path must be accounted for"
    assert n_blobs == n_files - 2, "the two duplicate pairs collapse to one blob each"


def test_tenant_raw_copy_is_never_canonical(tree):
    conn = _scan(tree)
    row = conn.execute(
        """SELECT b.canonical_path FROM blobs b
           JOIN blob_aliases a ON a.file_hash = b.file_hash
           WHERE a.path = 'data/tenants/tenant_1/raw/paper.pdf'"""
    ).fetchone()
    assert row[0] == "Dataset/paper.pdf"


def test_shallower_path_wins_within_a_root(tree):
    conn = _scan(tree)
    row = conn.execute(
        "SELECT canonical_path FROM blobs WHERE canonical_path LIKE '%marks.csv'"
    ).fetchone()
    assert row[0] == "Dataset/marks.csv", "loose file beats the subfolder copy"


def test_canonical_selection_is_deterministic(tree):
    """Re-scanning must not shuffle which alias is canonical."""
    first = dict(_scan(tree, tree / "a.db").execute(
        "SELECT file_hash, canonical_path FROM blobs"))
    second = dict(_scan(tree, tree / "b.db").execute(
        "SELECT file_hash, canonical_path FROM blobs"))
    assert first == second


def test_scan_is_idempotent(tree):
    catalog = tree / "catalog.db"
    _scan(tree, catalog).close()
    before = sqlite3.connect(catalog).execute(
        "SELECT COUNT(*) FROM blobs").fetchone()[0]
    conn = _scan(tree, catalog)
    after = conn.execute("SELECT COUNT(*) FROM blobs").fetchone()[0]
    alias_dupes = conn.execute(
        "SELECT COUNT(*) FROM (SELECT file_hash, path FROM blob_aliases GROUP BY 1,2 HAVING COUNT(*)>1)"
    ).fetchone()[0]
    assert before == after
    assert alias_dupes == 0


def test_basename_collision_is_visible(tree):
    """Two distinct blobs share notice.pdf. Staging must disambiguate them, so
    the catalog has to make the collision findable rather than hide it."""
    conn = _scan(tree)
    names = {}
    for (cpath,) in conn.execute(
        "SELECT canonical_path FROM blobs WHERE route != 'excluded'"
    ):
        names.setdefault(Path(cpath).name, []).append(cpath)
    assert sorted(names["notice.pdf"]) == ["Dataset/notice.pdf", "corpus/out/notice.pdf"]


def test_structured_data_never_takes_the_doc_route(tree):
    conn = _scan(tree)
    for path, route in conn.execute("SELECT canonical_path, route FROM blobs"):
        if Path(path).suffix.lower() in {".csv", ".xlsx", ".xls"}:
            assert route != "doc", f"{path} would be Docling-parsed as prose"


def test_result_pdfs_route_to_tabular_pdf(tree):
    conn = _scan(tree)
    route = conn.execute(
        "SELECT route FROM blobs WHERE canonical_path='Results Dataset/results_2025.pdf'"
    ).fetchone()[0]
    assert route == "tabular_pdf"


def test_corpus_pdfs_route_to_doc(tree):
    conn = _scan(tree)
    route = conn.execute(
        "SELECT route FROM blobs WHERE canonical_path='corpus/out/notice.pdf'"
    ).fetchone()[0]
    assert route == "doc"


@pytest.mark.parametrize("name,reason", [
    ("Dataset/SESSION-STUDENT-DETAILS-2.xlsx", "pii_holdout"),
    ("Dataset/helper.py", "source_code"),
    ("Dataset/archive.zip", "archive_container"),
])
def test_excluded_files_stay_in_the_catalog_with_a_reason(tree, name, reason):
    """Exclusion must be auditable. A file that is simply absent gives no answer
    to 'why isn't this indexed', and verify cannot prove a PII file stayed out
    of a tenant unless the catalog knows the file exists."""
    conn = _scan(tree)
    row = conn.execute(
        "SELECT route, exclude_reason FROM blobs WHERE canonical_path=?", (name,)
    ).fetchone()
    assert row is not None, f"{name} vanished from the catalog instead of being excluded"
    assert row == ("excluded", reason)


def test_every_blob_has_a_route(tree):
    conn = _scan(tree)
    unclassified = conn.execute(
        "SELECT COUNT(*) FROM blobs WHERE route IS NULL OR route=''"
    ).fetchone()[0]
    assert unclassified == 0


def test_report_never_writes(tree):
    catalog = tree / "catalog.db"
    _scan(tree, catalog).close()
    before = catalog.stat().st_mtime_ns, catalog.stat().st_size
    rc = bcc.main(["report", "--catalog", str(catalog)])
    assert rc == 0
    assert (catalog.stat().st_mtime_ns, catalog.stat().st_size) == before
