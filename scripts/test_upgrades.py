import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import sys
from pathlib import Path

# Setup paths
project_root = Path(f"{PROJECT_ROOT}")
sys.path.append(str(project_root))

from auth.allowlist import AllowlistManager
from pipeline import check_for_changes

def run_allowlist():
    print("Testing AllowlistManager...")
    mgr = AllowlistManager()

    # Test valid tenant/users
    telegram_allowed = mgr.is_telegram_user_allowed('tenant_1', 'telegram_user_123')
    whatsapp_allowed = mgr.is_whatsapp_user_allowed('tenant_1', 'whatsapp_user_456')
    print(f"Telegram user allowed: {telegram_allowed}")
    print(f"WhatsApp user allowed: {whatsapp_allowed}")

    # Test fallback compatibility
    legacy_allowed = mgr.is_telegram_user_allowed('tenant_1', '1990648223') # from original json if untouched
    print(f"Legacy user allowed: {legacy_allowed}")

    # Test unauthorized
    unauth = mgr.is_whatsapp_user_allowed('tenant_1', 'hacker_999')
    print(f"Unauthorized user allowed: {unauth}")
    print("Allowlist test complete.\n")

def run_incremental_ingestion():
    print("Testing Incremental Ingestion Logic...")

    # A throwaway tenant dir, NOT data/tenants/tenant_1. This used to drop a
    # test.txt straight into the live tenant's raw/ folder, where a crash before
    # the unlink() left it behind to be ingested for real.
    import tempfile
    from ingestion.manifest import _get_manifest_conn, _manifest_update, _sha256

    with tempfile.TemporaryDirectory() as td:
        tenant_root = Path(td)
        raw_dir = tenant_root / "raw"
        raw_dir.mkdir()
        test_file = raw_dir / "test.txt"
        test_file.write_text("Hello World")
        print(f"Created {test_file}")

        # New file, not in the manifest yet.
        changed_1 = check_for_changes(str(raw_dir), "tmp")
        print(f"First check (expect True): {changed_1}")

        # The gate is read-only: only a completed parse records a file. Stand in
        # for that here, the way ingestion.parse does once Docling returns.
        conn = _get_manifest_conn(tenant_root)
        _manifest_update(conn, test_file.name, _sha256(test_file), "SUCCESS",
                         file_size_bytes=test_file.stat().st_size)
        conn.close()

        # Recorded and unchanged.
        changed_2 = check_for_changes(str(raw_dir), "tmp")
        print(f"Second check (expect False): {changed_2}")

        # Contents change -> hash differs from the recorded one.
        test_file.write_text("Hello World 2")
        changed_3 = check_for_changes(str(raw_dir), "tmp")
        print(f"Third check (expect True): {changed_3}")

        ok = changed_1 and not changed_2 and changed_3
        print(f"Incremental ingestion test {'PASSED' if ok else 'FAILED'}.")
        print()
        return ok

if __name__ == "__main__":
    run_allowlist()
    run_incremental_ingestion()
