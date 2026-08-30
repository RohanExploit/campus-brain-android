"""
Per-tenant API key store (RBAC).

The existing env API_KEY remains a single admin key (backward compatible,
checked first in resolve_principal). This module adds an OPTIONAL JSON key
store, auth/api_keys.json, for scoped tenant keys — a "tenant" key may only
act on its bound tenant_id; an "admin" entry here behaves like the env key.

Fail-closed load pattern mirrors auth/allowlist.py: a missing file means "no
extra keys" (empty list, not an error); a corrupt/unreadable file logs an
error and also degrades to an empty list (never crashes, never silently
grants access). Malformed individual entries are skipped with a warning
rather than aborting the whole load.
"""
import json
import logging
import secrets
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))

import config
from utils.logging_config import setup_logging

setup_logging()


@dataclass(frozen=True)
class Principal:
    role: str
    tenant_id: str | None = None

    @property
    def is_admin(self) -> bool:
        return self.role == "admin"


def _keys_path() -> Path:
    """Path to the optional per-tenant key store. A module function (not a
    module-level constant) so tests can monkeypatch it to point at a tmp file."""
    return config.PROJECT_ROOT / "auth" / "api_keys.json"


def _load_keys() -> list[dict]:
    """Load and validate the optional key store. Fails closed exactly like
    auth/allowlist.py: missing file -> [], corrupt/unreadable file -> [] (logged
    error, never raises). Individual malformed entries are skipped with a
    logged warning rather than aborting the whole load."""
    path = _keys_path()
    if not path.exists():
        return []

    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        logging.error(
            "API key store at %s is corrupt/unreadable (%s); failing closed to "
            "no extra keys (only the env API_KEY admin key applies until fixed).",
            path, e,
        )
        return []

    if not isinstance(raw, list):
        logging.error(
            "API key store at %s is not a JSON list; failing closed to no extra keys.",
            path,
        )
        return []

    entries = []
    for entry in raw:
        if not isinstance(entry, dict):
            logging.warning("Skipping malformed api_keys.json entry (not an object): %r", entry)
            continue
        key = entry.get("key")
        role = entry.get("role")
        tenant_id = entry.get("tenant_id")
        if not isinstance(key, str) or not key:
            logging.warning("Skipping malformed api_keys.json entry (missing/empty key): %r", entry)
            continue
        if role not in ("admin", "tenant"):
            logging.warning("Skipping malformed api_keys.json entry (invalid role): %r", entry)
            continue
        if role == "tenant" and (not isinstance(tenant_id, str) or not tenant_id):
            logging.warning(
                "Skipping malformed api_keys.json entry (tenant role missing tenant_id): %r", entry
            )
            continue
        entries.append({"key": key, "role": role, "tenant_id": tenant_id if role == "tenant" else None})

    return entries


def resolve_principal(presented: str | None) -> "Principal | None":
    """Resolve a presented X-API-Key value to a Principal, or None if it
    matches nothing. Uses secrets.compare_digest for every comparison
    (constant-time, avoids timing side-channels on key matching)."""
    if not presented:
        return None

    admin_key = config.get_api_key()
    if admin_key and secrets.compare_digest(presented, admin_key):
        return Principal("admin")

    for entry in _load_keys():
        if secrets.compare_digest(presented, entry["key"]):
            return Principal(entry["role"], entry["tenant_id"])

    return None
