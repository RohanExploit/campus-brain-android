"""
Log Inspector — parse and validate structured JSONL audit logs.
Supports: required field validation, hash-chain tamper detection.
"""
import json
import hashlib
from pathlib import Path
from datetime import datetime
from typing import Any

REQUIRED_FIELDS = [
    "timestamp",
    "tenant_id",
    "user_id",
    "query_type",
    "latency_ms",
    "model",
    "outcome",
]


class AuditLogInspector:
    def __init__(self, log_path: Path):
        self.log_path = Path(log_path)
        self.events: list[dict] = []
        if self.log_path.exists():
            self._load()

    def _load(self):
        self.events = []
        with open(self.log_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        self.events.append(json.loads(line))
                    except json.JSONDecodeError:
                        self.events.append({"_corrupt_line": line})

    def reload(self):
        self._load()

    # ── Field validation ────────────────────────────────────────────────────

    def validate_required_fields(self, required: list[str] | None = None) -> list[str]:
        """
        Returns a list of violation strings.
        Empty list = all events pass required-field check.
        """
        fields = required or REQUIRED_FIELDS
        violations = []
        for i, event in enumerate(self.events):
            for f in fields:
                if f not in event or not event[f]:
                    violations.append(f"Event[{i}] missing '{f}': {event}")
        return violations

    def all_events_have(self, field: str) -> bool:
        return all(field in e and e[field] for e in self.events)

    def filter(self, **kwargs) -> list[dict]:
        return [e for e in self.events if all(e.get(k) == v for k, v in kwargs.items())]

    # ── Hash-chain tamper detection ─────────────────────────────────────────

    def compute_chain_hashes(self) -> list[str]:
        """
        Compute SHA-256 hash of each event JSON (deterministic key sort).
        Returns list of hex digests, one per event.
        """
        return [
            hashlib.sha256(
                json.dumps(e, sort_keys=True, ensure_ascii=False).encode()
            ).hexdigest()
            for e in self.events
        ]

    def tamper_event(self, index: int, field: str, new_value: Any):
        """
        Simulate tamper: modify an in-memory event (does NOT write to disk).
        Use compute_chain_hashes() before and after to detect the change.
        """
        if 0 <= index < len(self.events):
            self.events[index][field] = new_value

    def detect_tampering(self, original_hashes: list[str]) -> list[int]:
        """
        Compare current in-memory event hashes against a snapshot.
        Returns list of indices where hash differs (tampered events).
        """
        current_hashes = self.compute_chain_hashes()
        tampered = [
            i for i, (orig, curr) in enumerate(zip(original_hashes, current_hashes))
            if orig != curr
        ]
        # zip() stops at the shorter list, so a change in event COUNT (events
        # added or removed since the snapshot) would go undetected. A count
        # change IS tampering — flag every index in the non-overlapping tail.
        overlap = min(len(original_hashes), len(current_hashes))
        longer = max(len(original_hashes), len(current_hashes))
        tampered.extend(range(overlap, longer))
        return tampered

    # ── Timestamp ordering ──────────────────────────────────────────────────

    def is_chronological(self) -> bool:
        """Assert log events are in ascending timestamp order."""
        timestamps = []
        for e in self.events:
            ts = e.get("timestamp")
            if ts:
                try:
                    timestamps.append(datetime.fromisoformat(ts.rstrip("Z")))
                except ValueError:
                    return False
        return timestamps == sorted(timestamps)

    # ── Summary ─────────────────────────────────────────────────────────────

    def summary(self) -> dict:
        return {
            "total_events": len(self.events),
            "violations": self.validate_required_fields(),
            "is_chronological": self.is_chronological(),
            "outcomes": {
                o: sum(1 for e in self.events if e.get("outcome") == o)
                for o in set(e.get("outcome", "UNKNOWN") for e in self.events)
            },
        }
