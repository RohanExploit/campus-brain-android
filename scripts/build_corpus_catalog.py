"""Corpus catalog: one row per distinct file, one canonical path per sha256.

Why this exists
---------------
The same bytes live in several places at once. `Dataset/` is copied wholesale
into `data/tenants/tenant_1/raw/` at ingest time; several CSVs sit both loose
in `Dataset/` and inside a subfolder; `Results Dataset/camelot_out/` holds four
byte-identical extraction outputs. Nothing recorded that, so the same document
could be parsed, chunked and embedded more than once with nothing to say it had
happened before.

The catalog is a ledger of blobs, not of paths. Each distinct sha256 gets one
`blobs` row naming the path we consider canonical, plus one `blob_aliases` row
for every path the bytes were found at. That is a cross-root fact, which is why
it cannot live inside a per-tenant manifest.db.

Files we deliberately do not ingest stay IN the catalog with `route='excluded'`
and a reason. "Why isn't this document in the index" needs an answer other than
silence, and `verify` can only prove a PII file never entered a tenant if the
catalog knows the file exists.

Nothing here deletes or moves anything from a source folder.

Subcommands
-----------
    scan     hash every file under --root, populate the catalog (idempotent)
    report   human-readable dedup + routing summary (no writes at all)

`stage` and `verify` follow once the routing table below has been reviewed;
staging is the first step that writes outside the catalog, so it is deliberately
not part of this commit.

Usage
-----
    python scripts/build_corpus_catalog.py scan
    python scripts/build_corpus_catalog.py report
    python scripts/build_corpus_catalog.py report --duplicates
"""
import argparse
import mimetypes
import os
import re
import shutil
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

# The one hash implementation. Imported from ingestion.manifest rather than
# ingestion.parse so `scan` and `report` do not need Docling installed.
from ingestion.manifest import _sha256

DEFAULT_CATALOG = PROJECT_ROOT / "data" / "catalog" / "corpus_catalog.db"

# Roots scanned by default, in canonical-preference order. A blob found in more
# than one root is attributed to the earliest one here: corpus/out is generated
# from a builder we control, Results Dataset and Dataset hold originals, and a
# tenant raw/ dir only ever holds copies OF those, so it can never be canonical.
DEFAULT_ROOTS = [
    "corpus/out",
    "Results Dataset",
    "Dataset",
    "data/tenants/tenant_1/raw",
    "data/tenants/tenant_2/raw",
]

SCHEMA = """
CREATE TABLE IF NOT EXISTS blobs (
    file_hash       TEXT PRIMARY KEY,
    canonical_path  TEXT NOT NULL,
    file_size_bytes INTEGER NOT NULL,
    mtime_utc       TEXT NOT NULL,
    ext             TEXT NOT NULL,
    mime            TEXT,
    route           TEXT NOT NULL,
    exclude_reason  TEXT,
    doc_id          TEXT,
    ingest_status   TEXT DEFAULT 'PENDING',
    last_indexed_at TEXT,
    error_message   TEXT,
    flags           TEXT
);

CREATE TABLE IF NOT EXISTS blob_aliases (
    file_hash    TEXT NOT NULL REFERENCES blobs(file_hash),
    path         TEXT NOT NULL,
    source_root  TEXT NOT NULL,
    is_canonical INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (file_hash, path)
);

CREATE INDEX IF NOT EXISTS idx_alias_hash ON blob_aliases(file_hash);
CREATE UNIQUE INDEX IF NOT EXISTS idx_blobs_doc_id
    ON blobs(doc_id) WHERE doc_id IS NOT NULL;
"""

# ─── Measured, per-file exclusions ────────────────────────────────────────────
# These are named individually because the reason is a measurement, not a rule a
# glob could express. Each was checked by 6-gram containment against the variants
# that are kept; the numbers are recorded so the decision can be re-argued rather
# than taken on faith. Everything here stays in the catalog with its reason, so
# re-including a file is a one-line change and `verify` still knows it exists.
#
# The RAG-MicroSim files look like eight versions of one paper. They are not.
# Measured pairwise containment splits them into four groups:
#   * a genuine near-duplicate cluster -- Semi Final.docx (5027w), ru.docx
#     (5631w), Final  Resarch paper.pdf (1734w), RAG-MicroSim....pdf (1678w),
#     with 67-95% mutual containment
#   * RAG-MicroSim Framework.docx (15607w) -- 0-3% overlap with any other file
#   * 1 RAG-MicroSim....docx (3535w)       -- 0-2% overlap
#   * Ai free....txt (1355w)               -- 0-2% overlap
# So only the first group is redundant. Within it the two PDFs are 84-95%
# contained in the two .docx files, which are kept; the two .docx are only 78-79%
# contained in each other, so dropping either would lose roughly a thousand words
# of unique text and BOTH are kept. Dropping the two PDFs removes duplicated
# chunks that would otherwise crowd a top-10 retrieval with the same paragraphs.
EXPLICIT_EXCLUSIONS = {
    # >=84% contained in Semi Final.docx / ru.docx, which are both kept.
    "Dataset/Final  Resarch paper.pdf": "superseded_by_docx_variant",
    "Dataset/RAG-MicroSim_ A Hybrid Retrieval-Augmented Generation and Market "
    "Micro-Simulation Framework for High-Frequency Trading Analysis.pdf":
        "superseded_by_docx_variant",
    # Zero extractable text -- no <w:t> content at all. Docling would emit an
    # empty parse and the coverage assert in embed.py would then have to decide
    # what an empty document means. Cheaper to name it here.
    "Dataset/Rutuja Version RAG-MicroSim_ A Hybrid Retrieval-Augmented "
    "Generation and Market Micro-Simulation Framework for High-Frequency "
    "Trading Analysis.docx": "no_extractable_text",
    # Extracted text of the result PDFs that already take the tabular_pdf route.
    # Ingesting both puts the same content in the index twice by two paths, and
    # the prose copy is the less useful of the two.
    "Results Dataset/raw_text.txt": "derived_extract",
}

# ─── Exclusion policy ─────────────────────────────────────────────────────────
# Order matters: the first matching rule wins, so the specific ones come first.
# Each entry is (predicate over a repo-relative POSIX path, reason).

def _excl_rules():
    def parts(p):
        return p.split("/")

    return [
        (lambda p: any("_bak_" in s or "_backup_" in s for s in parts(p)),
         "backup_snapshot"),
        (lambda p: p.startswith("data/tenants/tenant_1/parsed_real/"),
         "dead_snapshot"),
        (lambda p: Path(p).name.startswith("funsd_train_"),
         "ocr_benchmark_fixture"),
        (lambda p: Path(p).name == "SESSION-STUDENT-DETAILS-2.xlsx",
         "pii_holdout"),
        (lambda p: p.startswith("data/tenants/tenant_2/raw/"),
         "pii_tenant"),
        (lambda p: Path(p).suffix.lower() in {".zip", ".zip_old"} or p.endswith(".zip_old"),
         "archive_container"),
        (lambda p: p.startswith("Dataset/bench_v1/"),
         "eval_corpus_already_ingested"),
        (lambda p: p.startswith("Dataset/Untested stresskit"),
         "eval_corpus_already_ingested"),
        (lambda p: Path(p).name.startswith("golden_") and p.endswith(".json"),
         "eval_ground_truth"),
        (lambda p: Path(p).suffix.lower() in {".py", ".r", ".js", ".ipynb"},
         "source_code"),
        (lambda p: any(s in {"node_modules", "__pycache__", ".next", ".git",
                             ".venv312", "graphify-out", "debug_outputs"}
                       for s in parts(p)),
         "build_artifact"),
        (lambda p: Path(p).suffix.lower() in {".png", ".jpg", ".jpeg", ".gif", ".svg"},
         "image_no_text_route"),
        (lambda p: Path(p).suffix.lower() in {".json", ".pkl", ".npy", ".faiss",
                                              ".graphml", ".duckdb", ".db",
                                              ".index", ".lock"},
         "not_a_source_document"),
    ]


# ─── Routing policy ───────────────────────────────────────────────────────────
# Structured data must never take the doc route. Pushing a 2.6MB CSV through
# Docling produced a 166MB layout JSON in tenant_1/parsed, which then got
# chunked and embedded as if it were prose. Extension alone decides this.

DOC_EXTS = {".pdf", ".docx", ".pptx", ".md", ".txt"}
TABULAR_EXTS = {".csv", ".xlsx", ".xls"}


def classify(rel_path: str) -> tuple[str, str | None]:
    """Return (route, exclude_reason). route is one of
    doc | tabular | tabular_pdf | excluded."""
    if rel_path in EXPLICIT_EXCLUSIONS:
        return "excluded", EXPLICIT_EXCLUSIONS[rel_path]
    for predicate, reason in _excl_rules():
        if predicate(rel_path):
            return "excluded", reason

    ext = Path(rel_path).suffix.lower()
    if ext in TABULAR_EXTS:
        return "tabular", None
    if ext == ".pdf" and rel_path.startswith("Results Dataset/"):
        # Result PDFs carry student marks tables, not prose. They belong on
        # parse_tabular -> tabular.duckdb, not on the vector path.
        return "tabular_pdf", None
    if ext in DOC_EXTS:
        return "doc", None
    return "excluded", "unhandled_extension"


# ─── Canonical path selection ─────────────────────────────────────────────────

def _root_priority(rel_path: str, roots: list[str]) -> int:
    for i, root in enumerate(roots):
        if rel_path == root or rel_path.startswith(root.rstrip("/") + "/"):
            return i
    return len(roots)


def _canonical_rank(rel_path: str, roots: list[str]) -> tuple:
    """Deterministic, so re-running scan produces the same canonical choice.

    Depth breaks ties inside a root, which is what makes the loose
    Dataset/Indian_Students_Data.csv win over Dataset/archive/ copy of it.
    """
    return (_root_priority(rel_path, roots), rel_path.count("/"), rel_path)


def _source_root_of(rel_path: str, roots: list[str]) -> str:
    for root in roots:
        if rel_path.startswith(root.rstrip("/") + "/"):
            return root
    return "."


# ─── Catalog ──────────────────────────────────────────────────────────────────

def open_catalog(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    conn.commit()
    return conn


def iter_files(roots: list[str]):
    for root in roots:
        base = PROJECT_ROOT / root
        if not base.exists():
            print(f"  [skip] root not found: {root}")
            continue
        for p in base.rglob("*"):
            if p.is_file():
                yield p


def cmd_scan(args):
    roots = args.root or DEFAULT_ROOTS
    conn = open_catalog(Path(args.catalog))

    seen: dict[str, list[str]] = {}
    sizes: dict[str, int] = {}
    mtimes: dict[str, str] = {}
    n_files = 0

    for path in iter_files(roots):
        rel = path.relative_to(PROJECT_ROOT).as_posix()
        try:
            digest = _sha256(path)
        except OSError as exc:
            print(f"  [err ] unreadable, skipped: {rel} ({type(exc).__name__})")
            continue
        n_files += 1
        seen.setdefault(digest, []).append(rel)
        st = path.stat()
        sizes[digest] = st.st_size
        mtimes.setdefault(
            digest,
            datetime.fromtimestamp(st.st_mtime, timezone.utc).isoformat(),
        )
        if n_files % 100 == 0:
            print(f"  hashed {n_files}...")

    for digest, paths in seen.items():
        canonical = min(paths, key=lambda p: _canonical_rank(p, roots))
        route, reason = classify(canonical)
        conn.execute(
            """INSERT INTO blobs
                 (file_hash, canonical_path, file_size_bytes, mtime_utc, ext,
                  mime, route, exclude_reason)
               VALUES (?,?,?,?,?,?,?,?)
               ON CONFLICT(file_hash) DO UPDATE SET
                 canonical_path=excluded.canonical_path,
                 file_size_bytes=excluded.file_size_bytes,
                 mtime_utc=excluded.mtime_utc,
                 ext=excluded.ext,
                 mime=excluded.mime,
                 route=excluded.route,
                 exclude_reason=excluded.exclude_reason""",
            (digest, canonical, sizes[digest], mtimes[digest],
             Path(canonical).suffix.lower(),
             mimetypes.guess_type(canonical)[0], route, reason),
        )
        for p in paths:
            conn.execute(
                """INSERT INTO blob_aliases (file_hash, path, source_root, is_canonical)
                   VALUES (?,?,?,?)
                   ON CONFLICT(file_hash, path) DO UPDATE SET
                     source_root=excluded.source_root,
                     is_canonical=excluded.is_canonical""",
                (digest, p, _source_root_of(p, roots), int(p == canonical)),
            )
    conn.commit()

    dupe_groups = sum(1 for v in seen.values() if len(v) > 1)
    redundant_bytes = sum(sizes[d] * (len(v) - 1) for d, v in seen.items() if len(v) > 1)
    print()
    print(f"scanned  {n_files} files across {len(roots)} roots")
    print(f"blobs    {len(seen)} distinct sha256")
    print(f"dupes    {dupe_groups} groups, {_human(redundant_bytes)} redundant")
    print(f"catalog  {args.catalog}")
    conn.close()
    return 0


def _human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f}{unit}" if unit != "B" else f"{n}B"
        n /= 1024.0
    return f"{n}B"


def cmd_report(args):
    path = Path(args.catalog)
    if not path.exists():
        print(f"No catalog at {path}. Run `scan` first.")
        return 1
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)

    n_blobs = conn.execute("SELECT COUNT(*) FROM blobs").fetchone()[0]
    n_alias = conn.execute("SELECT COUNT(*) FROM blob_aliases").fetchone()[0]
    total_bytes = conn.execute("SELECT COALESCE(SUM(file_size_bytes),0) FROM blobs").fetchone()[0]
    redundant = conn.execute(
        """SELECT COALESCE(SUM(b.file_size_bytes * (c.n - 1)), 0)
           FROM blobs b
           JOIN (SELECT file_hash, COUNT(*) n FROM blob_aliases GROUP BY file_hash) c
             ON c.file_hash = b.file_hash
           WHERE c.n > 1"""
    ).fetchone()[0]

    print("=" * 72)
    print("CORPUS CATALOG REPORT")
    print("=" * 72)
    print(f"paths catalogued : {n_alias}")
    print(f"distinct blobs   : {n_blobs}")
    print(f"unique bytes     : {_human(total_bytes)}")
    print(f"redundant bytes  : {_human(redundant)}")
    print()

    print("-- routes " + "-" * 62)
    for route, n, b in conn.execute(
        """SELECT route, COUNT(*), SUM(file_size_bytes)
           FROM blobs GROUP BY route ORDER BY COUNT(*) DESC"""
    ):
        print(f"  {route:<14} {n:>5} blobs   {_human(b or 0):>10}")
    print()

    print("-- exclusions " + "-" * 58)
    rows = conn.execute(
        """SELECT exclude_reason, COUNT(*), SUM(file_size_bytes)
           FROM blobs WHERE route='excluded'
           GROUP BY exclude_reason ORDER BY COUNT(*) DESC"""
    ).fetchall()
    for reason, n, b in rows:
        print(f"  {reason:<32} {n:>5} blobs   {_human(b or 0):>10}")
    if not rows:
        print("  (none)")
    print()

    print("-- what would be ingested " + "-" * 46)
    for route in ("doc", "tabular", "tabular_pdf"):
        rows = conn.execute(
            "SELECT canonical_path FROM blobs WHERE route=? ORDER BY canonical_path",
            (route,),
        ).fetchall()
        print(f"  [{route}] {len(rows)} files")
        if args.verbose:
            for (p,) in rows:
                print(f"      {p}")
    print()

    print("-- duplicate groups " + "-" * 52)
    groups = conn.execute(
        """SELECT b.file_hash, c.n, b.file_size_bytes, b.route
           FROM blobs b
           JOIN (SELECT file_hash, COUNT(*) n FROM blob_aliases GROUP BY file_hash) c
             ON c.file_hash = b.file_hash
           WHERE c.n > 1
           ORDER BY b.file_size_bytes * (c.n - 1) DESC"""
    ).fetchall()
    print(f"  {len(groups)} groups")
    if args.duplicates or args.verbose:
        for digest, n, size, route in groups:
            print(f"\n  {n} copies  {_human(size)} each  route={route}")
            for (p, is_canon) in conn.execute(
                "SELECT path, is_canonical FROM blob_aliases WHERE file_hash=? ORDER BY is_canonical DESC, path",
                (digest,),
            ):
                print(f"      {'CANON ' if is_canon else '      '}{p}")
    print()

    # Filename collisions across distinct blobs. parse.py keys the manifest on
    # the bare filename and writes parsed/{stem}.md, so two different blobs with
    # the same basename would overwrite each other on the way in.
    print("-- basename collisions across distinct blobs " + "-" * 27)
    # Computed in Python rather than SQL. A false "none" here means two distinct
    # documents silently overwrite each other during staging, so the check is not
    # a place for a clever basename-extraction expression.
    by_name: dict[str, list[str]] = {}
    for (cpath,) in conn.execute(
        "SELECT canonical_path FROM blobs WHERE route != 'excluded'"
    ):
        by_name.setdefault(Path(cpath).name, []).append(cpath)
    collisions = [(name, paths) for name, paths in by_name.items() if len(paths) > 1]
    if collisions:
        for name, paths in sorted(collisions):
            print(f"  {len(paths)}x  {name}")
            for cp in paths:
                print(f"        {cp}")
        print("  -> stage must disambiguate these with a hash suffix")
    else:
        print("  none among ingestable blobs")
    conn.close()
    return 0


# --- Stage -------------------------------------------------------------------

TENANT_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _staged_name(canonical_path: str, digest: str, claimed: dict) -> str:
    """Filename to stage under.

    Disambiguated when two distinct blobs share a basename, because
    ingestion.parse keys the manifest on the bare filename and writes
    parsed/{stem}.md -- two blobs with one basename would overwrite each other
    on the way in and collide on the manifest primary key.
    """
    name = Path(canonical_path).name
    owner = claimed.get(name)
    if owner is None or owner == digest:
        claimed[name] = digest
        return name
    stem, suffix = Path(name).stem, Path(name).suffix
    return stem + "__" + digest[:8] + suffix


def cmd_stage(args):
    if not TENANT_RE.match(args.tenant):
        print("Invalid tenant id: " + repr(args.tenant))
        return 2

    catalog_path = Path(args.catalog)
    if not catalog_path.exists():
        print("No catalog at " + str(catalog_path) + ". Run `scan` first.")
        return 1

    tenant_root = PROJECT_ROOT / "data" / "tenants" / args.tenant
    raw_dir = tenant_root / "raw"

    # Never stage into a tenant that already carries a built index. tenant_1 is
    # the live demo and every frozen baseline was measured against it; the bench
    # and stress tenants are instruments. Rebuilding any of them silently moves
    # the thing those numbers were measured with.
    if (tenant_root / "embeddings" / "faiss.index").exists() and not args.force:
        print("REFUSING: " + args.tenant + " already has embeddings/faiss.index.")
        print("Stage into a new tenant, or pass --force if you really mean it.")
        return 2

    conn = sqlite3.connect(catalog_path)
    routes = args.route or ["doc"]
    qmarks = ",".join("?" * len(routes))
    rows = conn.execute(
        "SELECT file_hash, canonical_path, route FROM blobs "
        "WHERE route IN (" + qmarks + ") ORDER BY canonical_path",
        routes,
    ).fetchall()

    # Basenames already spoken for by a different blob, including any staged by
    # an earlier run, so re-staging never hands one name to two blobs.
    claimed = {}
    for name, digest in conn.execute(
        "SELECT doc_id, file_hash FROM blobs WHERE doc_id IS NOT NULL"
    ):
        claimed[name] = digest

    planned, renamed, missing = [], [], []
    for digest, cpath, route in rows:
        src = PROJECT_ROOT / cpath
        if not src.exists():
            missing.append(cpath)
            continue
        name = _staged_name(cpath, digest, claimed)
        if name != Path(cpath).name:
            renamed.append((cpath, name))
        planned.append((digest, cpath, name))

    print("tenant   : " + args.tenant)
    print("routes   : " + ", ".join(routes))
    print("to stage : " + str(len(planned)) + " files -> " + str(raw_dir))
    if renamed:
        print("renamed  : " + str(len(renamed)) + " (basename collision)")
        for cpath, name in renamed:
            print("    " + cpath + "  ->  " + name)
    if missing:
        print("MISSING  : " + str(len(missing)) + " catalogued paths no longer on disk")
        for m in missing:
            print("    " + m)

    if not args.apply:
        print("")
        print("dry run. nothing written. re-run with --apply to stage.")
        conn.close()
        return 0

    raw_dir.mkdir(parents=True, exist_ok=True)
    linked = copied = 0
    for digest, cpath, name in planned:
        src = PROJECT_ROOT / cpath
        dst = raw_dir / name
        if dst.exists():
            dst.unlink()
        try:
            # Hardlink: same NTFS volume, no admin needed, and the duplicated
            # bytes cost nothing a second time. parse.py's iterdir cannot tell.
            os.link(src, dst)
            linked += 1
        except OSError:
            shutil.copy2(src, dst)
            copied += 1
        conn.execute(
            "UPDATE blobs SET doc_id=?, ingest_status='STAGED' WHERE file_hash=?",
            (name, digest),
        )
    conn.commit()
    conn.close()
    print("")
    print("staged " + str(linked) + " hardlinked, " + str(copied) + " copied into " + str(raw_dir))
    return 0


# --- Verify ------------------------------------------------------------------

def cmd_verify(args):
    catalog_path = Path(args.catalog)
    tenant_root = PROJECT_ROOT / "data" / "tenants" / args.tenant
    manifest_db = tenant_root / "manifest.db"

    if not catalog_path.exists():
        print("No catalog at " + str(catalog_path) + ".")
        return 1

    cat = sqlite3.connect("file:" + catalog_path.as_posix() + "?mode=ro", uri=True)
    failures, checks = [], []

    def check(name, ok, detail=""):
        checks.append((name, ok, detail))
        if not ok:
            failures.append(name)

    n_blobs = cat.execute("SELECT COUNT(*) FROM blobs").fetchone()[0]
    n_alias_hashes = cat.execute(
        "SELECT COUNT(DISTINCT file_hash) FROM blob_aliases").fetchone()[0]
    check("every alias belongs to a blob", n_blobs == n_alias_hashes,
          str(n_blobs) + " blobs vs " + str(n_alias_hashes) + " aliased hashes")

    unrouted = cat.execute(
        "SELECT COUNT(*) FROM blobs WHERE route IS NULL OR route=''").fetchone()[0]
    check("no unclassified blob", unrouted == 0, str(unrouted) + " unrouted")

    orphan_alias = cat.execute(
        "SELECT COUNT(*) FROM blob_aliases a LEFT JOIN blobs b "
        "ON b.file_hash=a.file_hash WHERE b.file_hash IS NULL").fetchone()[0]
    check("no orphan alias", orphan_alias == 0, str(orphan_alias) + " orphans")

    one_canon = cat.execute(
        "SELECT COUNT(*) FROM (SELECT file_hash, SUM(is_canonical) s "
        "FROM blob_aliases GROUP BY file_hash HAVING s != 1)").fetchone()[0]
    check("exactly one canonical alias per blob", one_canon == 0,
          str(one_canon) + " blobs violate")

    doc_hashes = {r[0] for r in cat.execute(
        "SELECT file_hash FROM blobs WHERE route='doc'")}

    if manifest_db.exists():
        man = sqlite3.connect("file:" + manifest_db.as_posix() + "?mode=ro", uri=True)
        has_table = man.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='manifest'"
        ).fetchone()
        if has_table:
            ok_hashes = {r[0] for r in man.execute(
                "SELECT file_hash FROM manifest WHERE parse_status='SUCCESS'")}
            missing = doc_hashes - ok_hashes
            phantom = ok_hashes - doc_hashes
            # Both directions. One catches a dropped document, the other catches
            # a document in the index that the catalog never authorised.
            check("every doc-route blob parsed", not missing,
                  str(len(missing)) + " catalogued docs absent from manifest")
            check("no unauthorised doc in manifest", not phantom,
                  str(len(phantom)) + " manifest rows not in the doc route")
            if missing and args.verbose:
                for h in sorted(missing):
                    row = cat.execute(
                        "SELECT canonical_path FROM blobs WHERE file_hash=?", (h,)).fetchone()
                    print("    missing: " + row[0])
        else:
            check("tenant manifest has a manifest table", False,
                  "manifest.db exists but has no manifest table")
        man.close()
    else:
        print("note: " + str(manifest_db) + " does not exist yet -- ingest has not run.")

    # A PII blob must never have entered the tenant. Only provable because
    # excluded blobs keep their hash in the catalog rather than vanishing.
    pii = {r[0] for r in cat.execute(
        "SELECT file_hash FROM blobs WHERE exclude_reason IN ('pii_holdout','pii_tenant')")}
    if pii and manifest_db.exists():
        man = sqlite3.connect("file:" + manifest_db.as_posix() + "?mode=ro", uri=True)
        if man.execute("SELECT 1 FROM sqlite_master WHERE type='table' "
                       "AND name='manifest'").fetchone():
            present = {r[0] for r in man.execute("SELECT file_hash FROM manifest")} & pii
            check("no PII blob entered the tenant", not present, str(len(present)) + " present")
        man.close()

    cat.close()
    print("")
    for name, ok, detail in checks:
        tag = "PASS" if ok else "FAIL"
        extra = ("  (" + detail + ")") if detail else ""
        print("  [" + tag + "] " + name + extra)
    print("")
    if failures:
        print("VERIFY FAILED: " + str(len(failures)) + " check(s)")
        return 1
    print("VERIFY OK")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_scan = sub.add_parser("scan", help="hash every file under --root into the catalog")
    p_scan.add_argument("--root", action="append",
                        help=f"repo-relative root; repeatable. default: {DEFAULT_ROOTS}")
    p_scan.add_argument("--catalog", default=str(DEFAULT_CATALOG))
    p_scan.set_defaults(func=cmd_scan)

    p_rep = sub.add_parser("report", help="dedup + routing summary; never writes")
    p_rep.add_argument("--catalog", default=str(DEFAULT_CATALOG))
    p_rep.add_argument("--duplicates", action="store_true", help="list every duplicate group")
    p_rep.add_argument("-v", "--verbose", action="store_true", help="list every file")
    p_rep.set_defaults(func=cmd_report)

    p_stage = sub.add_parser("stage", help="hardlink catalogued files into a tenant raw/ dir")
    p_stage.add_argument("--tenant", required=True)
    p_stage.add_argument("--route", action="append",
                         help="route to stage; repeatable. default: doc")
    p_stage.add_argument("--catalog", default=str(DEFAULT_CATALOG))
    p_stage.add_argument("--apply", action="store_true",
                         help="actually write. without this it is a dry run")
    p_stage.add_argument("--force", action="store_true",
                         help="stage into a tenant that already has an index")
    p_stage.set_defaults(func=cmd_stage)

    p_ver = sub.add_parser("verify", help="prove nothing was dropped and nothing extra got in")
    p_ver.add_argument("--tenant", required=True)
    p_ver.add_argument("--catalog", default=str(DEFAULT_CATALOG))
    p_ver.add_argument("-v", "--verbose", action="store_true")
    p_ver.set_defaults(func=cmd_verify)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
