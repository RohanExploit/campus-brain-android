"""
Audit Scorecard — weighted score calculator.
Usage:
    from audit.scorecard import compute_scorecard, print_scorecard
    scorecard = compute_scorecard(results)   # results = list of audit result dicts
    print_scorecard(scorecard)
"""
import json
from dataclasses import dataclass, field
from pathlib import Path
from datetime import datetime, timezone

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# ─── Spec: category weights (must sum to 1.0) ────────────────────────────────
CATEGORY_WEIGHTS = {
    "integrity":     0.20,
    "security":      0.20,
    "retrieval":     0.15,
    "observability": 0.10,
    "reliability":   0.10,
    "performance":   0.10,
    "decision":      0.05,
    "regression":    0.03,
}

# Production gate audits — failure = deployment blocked
GATE_AUDIT_IDS = {"01", "03", "06", "07", "10"}


@dataclass
class CategoryScore:
    name: str
    weight: float
    passed: int
    total: int

    @property
    def pct(self) -> float:
        return round(self.passed / self.total * 100, 1) if self.total else 0.0

    @property
    def weighted_contribution(self) -> float:
        return round(self.weight * self.pct, 2)


@dataclass
class Scorecard:
    overall_pct:      float
    weighted_score:   float
    total_pass:       int
    total_audits:     int
    gate_passed:      bool
    gate_failures:    list[dict]
    production_ready: bool
    category_scores:  dict[str, CategoryScore]
    run_at:           str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())

    def to_dict(self) -> dict:
        return {
            "overall_pct":      self.overall_pct,
            "weighted_score":   self.weighted_score,
            "total_pass":       self.total_pass,
            "total_audits":     self.total_audits,
            "gate_passed":      self.gate_passed,
            "gate_failures":    self.gate_failures,
            "production_ready": self.production_ready,
            "run_at":           self.run_at,
            "category_scores":  {
                k: {
                    "name":                  v.name,
                    "weight":                v.weight,
                    "passed":                v.passed,
                    "total":                 v.total,
                    "pct":                   v.pct,
                    "weighted_contribution": v.weighted_contribution,
                }
                for k, v in self.category_scores.items()
            },
        }


def compute_scorecard(results: list[dict]) -> Scorecard:
    """
    Compute weighted scorecard from list of audit result dicts.
    Each result must have: id, name, category, gate, status (PASS|FAIL).
    """
    by_cat: dict[str, list[dict]] = {}
    for r in results:
        by_cat.setdefault(r["category"], []).append(r)

    category_scores: dict[str, CategoryScore] = {}
    total_weighted = 0.0

    for cat, weight in CATEGORY_WEIGHTS.items():
        audits = by_cat.get(cat, [])
        passed = sum(1 for a in audits if a["status"] == "PASS")
        total = len(audits)
        cs = CategoryScore(name=cat, weight=weight, passed=passed, total=total)
        category_scores[cat] = cs
        total_weighted += cs.weighted_contribution

    total_pass = sum(1 for r in results if r["status"] == "PASS")
    total_audits = len(results)
    overall_pct = round(total_pass / total_audits * 100, 1) if total_audits else 0.0

    gate_failures = [
        {"id": r["id"], "name": r["name"]}
        for r in results
        if r.get("gate") and r["status"] == "FAIL"
    ]
    gate_passed = len(gate_failures) == 0
    production_ready = gate_passed and overall_pct >= 80.0

    return Scorecard(
        overall_pct=overall_pct,
        weighted_score=round(total_weighted, 1),
        total_pass=total_pass,
        total_audits=total_audits,
        gate_passed=gate_passed,
        gate_failures=gate_failures,
        production_ready=production_ready,
        category_scores=category_scores,
    )


def print_scorecard(sc: Scorecard) -> None:
    """Print a clean terminal scorecard."""
    PASS_SYM = "[PASS]"
    FAIL_SYM = "[FAIL]"
    BLOCK_SYM = "[BLOCKED]"
    READY_SYM = "[PRODUCTION READY]"

    print("\n" + "=" * 60)
    print("  COMPANY BRAIN — ENTERPRISE AUDIT SCORECARD")
    print("=" * 60)
    print(f"  Overall     : {sc.overall_pct}%  ({sc.total_pass}/{sc.total_audits} audits)")
    print(f"  Weighted    : {sc.weighted_score}%")
    print(f"  Gate        : {PASS_SYM if sc.gate_passed else BLOCK_SYM}")
    print(f"  Production  : {READY_SYM if sc.production_ready else '[NOT READY]'}")
    print(f"  Run at      : {sc.run_at}")
    print()
    print("  CATEGORY BREAKDOWN")
    print("  " + "-" * 56)
    for cat, cs in sc.category_scores.items():
        bar_len = int(cs.pct / 5)
        bar = "#" * bar_len + "." * (20 - bar_len)
        print(f"  {cat:<14} [{bar}] {cs.pct:5.1f}%  (weight {cs.weight:.0%})")
    print()
    if sc.gate_failures:
        print("  GATE FAILURES (blocking production deployment)")
        for f in sc.gate_failures:
            print(f"    {FAIL_SYM}  [{f['id']}] {f['name']}")
    else:
        print("  All production gates passed.")
    print("=" * 60 + "\n")


def save_scorecard(sc: Scorecard, output_path: Path | None = None) -> Path:
    """Save scorecard as JSON."""
    if output_path is None:
        output_path = PROJECT_ROOT / "audit" / "scorecard_latest.json"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(sc.to_dict(), indent=2), encoding="utf-8")
    return output_path


if __name__ == "__main__":
    # Demo with mock data
    mock_results = [
        {"id": "01", "name": "Document Integrity",       "category": "integrity",     "gate": True,  "status": "PASS"},
        {"id": "02", "name": "Extraction Verification",  "category": "integrity",     "gate": False, "status": "PASS"},
        {"id": "03", "name": "Hallucination Resistance", "category": "retrieval",     "gate": True,  "status": "PASS"},
        {"id": "04", "name": "Source Attribution",       "category": "retrieval",     "gate": False, "status": "PASS"},
        {"id": "05", "name": "Cross-Doc Consistency",    "category": "integrity",     "gate": False, "status": "PASS"},
        {"id": "06", "name": "Multi-Tenant Isolation",   "category": "security",      "gate": True,  "status": "PASS"},
        {"id": "07", "name": "Prompt Injection",         "category": "security",      "gate": True,  "status": "PASS"},
        {"id": "08", "name": "Retrieval Poisoning",      "category": "retrieval",     "gate": False, "status": "PASS"},
        {"id": "09", "name": "SQL Injection",            "category": "security",      "gate": False, "status": "PASS"},
        {"id": "10", "name": "Authorization RBAC",       "category": "security",      "gate": True,  "status": "FAIL"},
        {"id": "11", "name": "Audit Log Integrity",      "category": "observability", "gate": False, "status": "PASS"},
        {"id": "12", "name": "Explainability",           "category": "observability", "gate": False, "status": "PASS"},
        {"id": "13", "name": "Performance P99",          "category": "performance",   "gate": False, "status": "PASS"},
        {"id": "14", "name": "Recovery",                 "category": "reliability",   "gate": False, "status": "PASS"},
        {"id": "15", "name": "Idempotency",              "category": "reliability",   "gate": False, "status": "PASS"},
        {"id": "16", "name": "Adversarial OCR",          "category": "integrity",     "gate": False, "status": "PASS"},
        {"id": "17", "name": "Unicode Support",          "category": "retrieval",     "gate": False, "status": "PASS"},
        {"id": "18", "name": "Fuzzy Search",             "category": "retrieval",     "gate": False, "status": "PASS"},
        {"id": "19", "name": "Regression Benchmark",     "category": "regression",    "gate": False, "status": "FAIL"},
        {"id": "20", "name": "Enterprise Chaos",         "category": "reliability",   "gate": False, "status": "PASS"},
        {"id": "21", "name": "Decision Intelligence",    "category": "decision",      "gate": False, "status": "PASS"},
    ]
    sc = compute_scorecard(mock_results)
    print_scorecard(sc)
    path = save_scorecard(sc)
    print(f"Saved to {path}")
