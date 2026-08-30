import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))

from auth.allowlist import AllowlistManager


def test_allowlist_user_allowed(tmp_path):
    mgr = AllowlistManager(auth_file=str(tmp_path / "allowlist.json"))
    # default seed registers telegram_user_123 on tenant_1
    assert mgr.is_user_allowed("tenant_1", "telegram_user_123") is True
    assert mgr.is_user_allowed("tenant_1", "unknown_user") is False
    assert mgr.is_user_allowed("unknown_tenant", "telegram_user_123") is False


def test_allowlist_channel_specificity(tmp_path):
    mgr = AllowlistManager(auth_file=str(tmp_path / "allowlist.json"))
    # seeded defaults: telegram_user_123 (telegram), whatsapp_user_456 (whatsapp)
    assert mgr.is_telegram_user_allowed("tenant_1", "telegram_user_123") is True
    assert mgr.is_telegram_user_allowed("tenant_1", "whatsapp_user_456") is False
    assert mgr.is_whatsapp_user_allowed("tenant_1", "whatsapp_user_456") is True
    assert mgr.is_whatsapp_user_allowed("tenant_1", "telegram_user_123") is False


def test_allowlist_persists(tmp_path):
    auth_file = tmp_path / "allowlist.json"
    AllowlistManager(auth_file=str(auth_file))
    assert auth_file.exists()  # first init seeds + writes the default allowlist
    mgr2 = AllowlistManager(auth_file=str(auth_file))
    assert "tenant_1" in mgr2.allowlist
