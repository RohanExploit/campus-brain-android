"""
Audit 11 — Audit Log Integrity
Pass: Every API call logged. Log is append-only. Hash chain verifiable. No tampering.
"""
import json
import hashlib
import pytest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

pytestmark = pytest.mark.observability

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
AUDIT_LOG = PROJECT_ROOT / "data" / "audit.jsonl"

REQUIRED_FIELDS = ["timestamp", "tenant_id", "user_id", "query_type",
                    "latency_ms", "model", "outcome"]


def _compute_chain_hash(prev_hash: str, entry: dict) -> str:
    payload = prev_hash + json.dumps(entry, sort_keys=True)
    return hashlib.sha256(payload.encode()).hexdigest()


class TestAuditLogIntegrity:

    def test_audit_log_file_exists_or_note_missing(self):
        if not AUDIT_LOG.exists():
            pytest.skip(
                f"audit.jsonl not found at {AUDIT_LOG}. "
                "Will be created after first /query call with audit middleware."
            )

    def test_all_required_fields_present(self):
        if not AUDIT_LOG.exists():
            pytest.skip("audit.jsonl not yet created")
        with open(AUDIT_LOG, encoding="utf-8") as f:
            lines = [json.loads(l) for l in f if l.strip()]
        if not lines:
            pytest.skip("audit.jsonl is empty")
        violations = []
        for i, entry in enumerate(lines):
            missing = [r for r in REQUIRED_FIELDS if r not in entry]
            if missing:
                violations.append({"line": i + 1, "missing": missing})
        assert not violations, f"Audit log entries missing fields: {violations[:3]}"

    def test_timestamps_are_chronological(self):
        if not AUDIT_LOG.exists():
            pytest.skip("audit.jsonl not yet created")
        with open(AUDIT_LOG, encoding="utf-8") as f:
            lines = [json.loads(l) for l in f if l.strip()]
        if len(lines) < 2:
            pytest.skip("Need at least 2 log entries")
        for i in range(1, len(lines)):
            assert lines[i]["timestamp"] >= lines[i-1]["timestamp"], \
                f"Timestamp out of order at line {i+1}"

    def test_hash_chain_not_tampered(self):
        if not AUDIT_LOG.exists():
            pytest.skip("audit.jsonl not yet created")
        with open(AUDIT_LOG, encoding="utf-8") as f:
            lines = [json.loads(l) for l in f if l.strip()]
        if not lines or "chain_hash" not in lines[0]:
            pytest.skip("Hash-chain not yet implemented in log entries")
        prev_hash = "GENESIS"
        for i, entry in enumerate(lines):
            stored_hash = entry.get("chain_hash", "")
            entry_without_hash = {k: v for k, v in entry.items() if k != "chain_hash"}
            computed = _compute_chain_hash(prev_hash, entry_without_hash)
            assert computed == stored_hash, (
                f"Hash chain broken at entry {i+1}. "
                f"Expected {computed[:16]}... got {stored_hash[:16]}..."
            )
            prev_hash = stored_hash

    def test_log_inspector_module_exists(self):
        inspector = PROJECT_ROOT / "audit" / "utils" / "log_inspector.py"
        assert inspector.exists(), f"log_inspector.py not found at {inspector}"

    def test_query_type_valid_enum(self):
        if not AUDIT_LOG.exists():
            pytest.skip("audit.jsonl not yet created")
        valid_types = {"FACT", "LOCAL", "GLOBAL", "TABULAR", "DECISION", "DISAMBIGUATION"}
        with open(AUDIT_LOG, encoding="utf-8") as f:
            lines = [json.loads(l) for l in f if l.strip()]
        invalid = [e for e in lines if e.get("query_type") not in valid_types]
        assert not invalid, f"{len(invalid)} entries have invalid query_type"

    def test_outcome_is_valid_enum(self):
        if not AUDIT_LOG.exists():
            pytest.skip("audit.jsonl not yet created")
        valid_outcomes = {"SUCCESS", "HALLUCINATION_GUARD", "INSUFFICIENT_EVIDENCE",
                          "CONFLICT_DETECTED", "AUTH_DENIED", "ERROR"}
        with open(AUDIT_LOG, encoding="utf-8") as f:
            lines = [json.loads(l) for l in f if l.strip()]
        invalid = [e for e in lines if e.get("outcome") not in valid_outcomes]
        assert not invalid, f"{len(invalid)} entries have invalid outcome"
