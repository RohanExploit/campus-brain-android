"""
Bootstrap manifest.db for all tenant directories that are missing one.
Run once: python scripts/bootstrap_manifests.py

The schema and the hash function are imported from ingestion.parse rather than
redeclared here. This file used to carry its own copy of MANIFEST_SCHEMA that
was missing the `flags` column, so a manifest bootstrapped by this script and
then written by parse.py disagreed about its own shape.
"""
import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))

from ingestion.manifest import _get_manifest_conn, _sha256 as sha256_file

DATA_ROOT = Path(__file__).resolve().parent.parent / "data" / "tenants"

def bootstrap_tenant(tenant_dir: Path):
    # _get_manifest_conn creates the table if absent and ALTERs in `flags` on
    # manifests that predate that column, so bootstrapping also repairs.
    conn = _get_manifest_conn(tenant_dir)

    # Scan raw/ and register any existing files
    raw_dir = tenant_dir / "raw"
    if raw_dir.exists():
        for f in raw_dir.iterdir():
            if not f.is_file():
                continue
            file_hash = sha256_file(f)
            size = f.stat().st_size
            # Check if already in manifest
            existing = conn.execute(
                "SELECT doc_id FROM manifest WHERE doc_id = ?", (f.name,)
            ).fetchone()
            if not existing:
                conn.execute(
                    """INSERT INTO manifest
                       (doc_id, file_hash, parse_status, last_indexed_at, file_size_bytes)
                       VALUES (?, ?, 'SUCCESS', datetime('now'), ?)""",
                    (f.name, file_hash, size)
                )
                print(f"  [OK] Registered: {f.name} ({size} bytes)")
            else:
                print(f"  [--] Already registered: {f.name}")

    conn.commit()
    conn.close()
    print(f"[OK] manifest.db ready for {tenant_dir.name}")


if __name__ == "__main__":
    if not DATA_ROOT.exists():
        print(f"Data root not found: {DATA_ROOT}")
        exit(1)

    tenant_dirs = [d for d in DATA_ROOT.iterdir()
                   if d.is_dir() and not d.name.startswith("audit_") and not d.name.startswith("{")]

    print(f"Found {len(tenant_dirs)} tenants: {[d.name for d in tenant_dirs]}\n")
    for tenant_dir in tenant_dirs:
        print(f"Bootstrapping {tenant_dir.name}...")
        try:
            bootstrap_tenant(tenant_dir)
        except Exception as e:
            print(f"  [ERR] Error: {e}")
        print()

    print("Done. All tenants have manifest.db.")
