"""The student-identity role gate ships OFF, and OFF must mean *nothing changed*.

TABULAR roster answers list every matching student by full name and roll number, and
api/main.py returns TABULAR context verbatim, so these template strings are literally
what a user reads. The TABULAR invariant (21/22 on tenant_1) is measured against them.

So the contract under test is narrow and strict:
  * with the gate off, output is byte-for-byte the pre-change format;
  * with the gate on, a non-privileged requester gets counts without identities;
  * a privileged role is unaffected even with the gate on;
  * an unknown/unassigned user is treated as NON-privileged (fail closed).

Enabling the gate is a policy decision left to a human: every non-admin role list in
auth/allowlist.json is empty, so flipping it today would withhold identities from
everyone except the admin. These tests deliberately do not assert what the policy
should be — only that the mechanism is faithful in both positions.
"""
import json
import sys
from pathlib import Path

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import config
from auth.allowlist import AllowlistManager
from retrieval.sql_templates import _student_label


def test_unredacted_label_is_byte_identical_to_the_original_format():
    # the exact f-string these templates used before the gate existed
    name, roll = "JAGTAP ANANT TANAJI", "23063181242004"
    assert _student_label(name, roll, False) == f"{name or 'Unknown'} (Roll: {roll})"
    assert _student_label(name, roll, False) == "JAGTAP ANANT TANAJI (Roll: 23063181242004)"


def test_missing_name_still_renders_unknown_when_not_redacting():
    assert _student_label(None, "23063181242004", False) == "Unknown (Roll: 23063181242004)"


def test_redacted_label_withholds_both_name_and_roll():
    label = _student_label("JAGTAP ANANT TANAJI", "23063181242004", True)
    assert "JAGTAP" not in label
    assert "23063181242004" not in label
    # a partial roll is still an identifier — nothing of it may survive
    assert "2306" not in label


def test_gate_ships_off():
    assert config.PII_ROLE_GATE is False, (
        "PII_ROLE_GATE must ship OFF: turning it on withholds identities from every role "
        "that is not populated in auth/allowlist.json, which is a policy decision."
    )


@pytest.fixture
def mgr(tmp_path):
    auth_file = tmp_path / "allowlist.json"
    auth_file.write_text(json.dumps({
        "tenant_1": {
            "telegram_users": ["111", "222", "333"],
            "roles": {"admin": ["111"], "registrar": ["222"], "faculty": [], "student": ["333"]},
        }
    }), encoding="utf-8")
    return AllowlistManager(auth_file=auth_file)


def test_get_role_reads_the_roles_map_that_nothing_used_to_read(mgr):
    assert mgr.get_role("tenant_1", "111") == "admin"
    assert mgr.get_role("tenant_1", "222") == "registrar"
    assert mgr.get_role("tenant_1", "333") == "student"


def test_unassigned_and_unknown_users_have_no_role(mgr):
    assert mgr.get_role("tenant_1", "999") is None      # allowlisted nowhere
    assert mgr.get_role("no_such_tenant", "111") is None


def test_unassigned_user_is_not_privileged(mgr):
    """None must fail closed — the same posture as _load()'s corrupt-file handling."""
    role = mgr.get_role("tenant_1", "999")
    assert role not in config.PII_PRIVILEGED_ROLES


def test_privileged_roles_include_registrar_not_student():
    assert "registrar" in config.PII_PRIVILEGED_ROLES
    assert "admin" in config.PII_PRIVILEGED_ROLES
    assert "student" not in config.PII_PRIVILEGED_ROLES
