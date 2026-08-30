import json
import logging
import sys
from pathlib import Path
sys.path.append(str(Path(__file__).resolve().parent.parent))

from config import AUTH_FILE
from utils.logging_config import setup_logging

setup_logging()

class AllowlistManager:
    def __init__(self, auth_file=None):
        self.auth_file = Path(auth_file) if auth_file else AUTH_FILE
        self.auth_file.parent.mkdir(parents=True, exist_ok=True)
        self.allowlist = self._load()

    def _load(self):
        if not self.auth_file.exists():
            default = {
                "tenant_1": {
                    "telegram_users": ["telegram_user_123"],
                    "whatsapp_users": ["whatsapp_user_456"],
                    "description": "Default Prototype Tenant"
                }
            }
            self._save(default)
            return default

        try:
            with open(self.auth_file, "r", encoding="utf-8") as f:
                return json.load(f)
        except (json.JSONDecodeError, OSError) as e:
            # A corrupt/unreadable allowlist must not crash auth construction
            # (it would 500 /admin/status and break bot auth). Fail CLOSED: log
            # loudly and treat as an empty allowlist so every is_*_allowed check
            # denies, rather than silently granting the built-in default users.
            logging.error(
                "Allowlist at %s is corrupt/unreadable (%s); failing closed to an "
                "empty allowlist (all users denied until fixed).", self.auth_file, e
            )
            return {}

    def _save(self, data):
        with open(self.auth_file, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)

    def is_telegram_user_allowed(self, tenant_id: str, user_id: str) -> bool:
        tenant = self.allowlist.get(tenant_id)
        if not tenant:
            return False
        # Fallback to 'users' for backwards compatibility
        return user_id in tenant.get("telegram_users", tenant.get("users", []))

    def is_whatsapp_user_allowed(self, tenant_id: str, user_id: str) -> bool:
        tenant = self.allowlist.get(tenant_id)
        if not tenant:
            return False
        # Fallback to 'users' for backwards compatibility
        return user_id in tenant.get("whatsapp_users", tenant.get("users", []))

    def is_user_allowed(self, tenant_id: str, user_id: str) -> bool:
        """Channel-agnostic check: allowed if the user is on either channel's list."""
        return (self.is_telegram_user_allowed(tenant_id, user_id)
                or self.is_whatsapp_user_allowed(tenant_id, user_id))

    def get_role(self, tenant_id: str, user_id: str) -> str | None:
        """The user's role within a tenant, or None if unassigned.

        auth/allowlist.json has carried a "roles" map ({"admin": [...], "registrar": [],
        "faculty": [], "student": []}) since it was written, but nothing ever read it —
        so allow/deny was the only distinction the system could make, and every
        allowlisted user got registrar-grade answers including other students' names and
        roll numbers. This is the reader. It makes no access decision by itself; see
        config.PII_ROLE_GATE.

        An unassigned user returns None, which callers must treat as *non-privileged* —
        failing closed, the same way _load() fails closed on a corrupt allowlist.
        """
        tenant = self.allowlist.get(tenant_id)
        if not tenant:
            return None
        for role, members in (tenant.get("roles") or {}).items():
            if user_id in (members or []):
                return role
        return None

if __name__ == "__main__":
    mgr = AllowlistManager()
    print(f"Is Telegram user allowed? {mgr.is_telegram_user_allowed('tenant_1', 'telegram_user_123')}")
