"""
Phase -1.2 guardrails: the golden-set eval must never let an external cloud LLM
answer a question.

Two properties are locked here:
  1. config.ALLOW_EXTERNAL_LLM defaults to OFF (no env set -> egress forbidden).
  2. tests/eval/run_eval.enforce_no_egress() forces egress OFF regardless of the
     ambient value, so a stray ALLOW_EXTERNAL_LLM=1 in the environment cannot
     silently route an eval answer through the NVIDIA 70B fallback (which would
     both invalidate the local-model measurement and ship document PII off-box).

Hermetic: no Ollama, no network, no tenant data touched.
"""
import importlib
import importlib.util
from pathlib import Path

import config

RUN_EVAL_PATH = Path(__file__).resolve().parent / "eval" / "run_eval.py"


def _load_run_eval():
    spec = importlib.util.spec_from_file_location("_run_eval_under_test", RUN_EVAL_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_external_llm_egress_defaults_off(monkeypatch):
    """With no ALLOW_EXTERNAL_LLM in the environment, egress is forbidden."""
    monkeypatch.delenv("ALLOW_EXTERNAL_LLM", raising=False)
    reloaded = importlib.reload(config)
    try:
        assert reloaded.ALLOW_EXTERNAL_LLM is False
    finally:
        importlib.reload(config)  # restore ambient config for other tests


def test_run_eval_forces_egress_off_even_if_enabled(monkeypatch):
    """enforce_no_egress() must drive egress OFF even when it was switched on."""
    run_eval = _load_run_eval()
    # Simulate a deployment / stray env that turned the cloud fallback ON.
    monkeypatch.setattr(config, "ALLOW_EXTERNAL_LLM", True)
    run_eval.enforce_no_egress()
    assert config.ALLOW_EXTERNAL_LLM is False


def test_enforce_no_egress_is_wired_into_run():
    """The enforcement is actually called by the eval's run() entrypoint, not just
    defined — guard against someone deleting the call."""
    source = RUN_EVAL_PATH.read_text(encoding="utf-8")
    assert "enforce_no_egress()" in source
    # called inside run(), not only defined
    run_body = source.split("async def run(", 1)[1]
    assert "enforce_no_egress()" in run_body
