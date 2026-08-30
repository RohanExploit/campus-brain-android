"""
Security regression: a corrupt/unreadable allowlist must fail CLOSED — the
manager returns an empty allowlist (all users denied) and never crashes auth
construction, rather than silently granting the built-in default users.

Hermetic: writes a throwaway allowlist file under tmp_path; no real auth file
is touched.
"""
import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))

from auth.allowlist import AllowlistManager


def test_corrupt_allowlist_fails_closed(tmp_path):
    bad = tmp_path / "allowlist.json"
    bad.write_text("{ this is not valid json", encoding="utf-8")

    mgr = AllowlistManager(auth_file=bad)

    assert mgr.allowlist == {}
    assert mgr.is_telegram_user_allowed("tenant_1", "telegram_user_123") is False
    assert mgr.is_whatsapp_user_allowed("tenant_1", "whatsapp_user_456") is False
    assert mgr.is_user_allowed("tenant_1", "anyone") is False


def test_missing_allowlist_seeds_default(tmp_path):
    # A missing file (not corrupt) is seeded with the default prototype tenant —
    # distinct from the corrupt-file fail-closed path above.
    fresh = tmp_path / "sub" / "allowlist.json"
    mgr = AllowlistManager(auth_file=fresh)

    assert "tenant_1" in mgr.allowlist
    assert fresh.exists()
