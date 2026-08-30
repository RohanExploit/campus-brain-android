"""
Hermetic tests for the PII-egress controls in generation/answer.py.

generation/answer.py answers non-TABULAR queries with a local Ollama model
and, on failure, falls back to NVIDIA's cloud API. These tests force the
local call to fail (mocking _get_http_client) and then verify:
  - ALLOW_EXTERNAL_LLM=0 blocks the NVIDIA fallback entirely (no egress).
  - LLM_PII_REDACTION=1 masks roll-number-like PII before the prompt leaves
    the machine.
  - LLM_PII_REDACTION=0 (default) sends the raw prompt, unchanged.

No network calls are made; AsyncOpenAI is patched in all NVIDIA-reaching tests.
"""
import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))

import config
import generation.answer as answer


def _mock_ollama_down(monkeypatch):
    """Force the local Ollama call to fail so generate_answer falls through
    to the NVIDIA fallback branch."""
    client = MagicMock()
    client.post = AsyncMock(side_effect=RuntimeError("down"))
    monkeypatch.setattr(answer, "_get_http_client", lambda: client)


class _FakeCompletions:
    def __init__(self, capture: dict):
        self._capture = capture

    async def create(self, model, messages, **kwargs):
        self._capture["messages"] = messages
        result = MagicMock()
        result.choices = [MagicMock(message=MagicMock(content="fake nvidia answer"))]
        return result


class _FakeChat:
    def __init__(self, capture: dict):
        self.completions = _FakeCompletions(capture)


class _FakeAsyncOpenAI:
    """Minimal stand-in for AsyncOpenAI supporting `async with ... as client`
    and `await client.chat.completions.create(...)`."""

    def __init__(self, capture: dict):
        self._capture = capture
        self.chat = _FakeChat(capture)

    def __call__(self, *args, **kwargs):
        # AsyncOpenAI(...) is called with base_url/api_key/timeout kwargs;
        # return self so the instance is reused as the async context manager.
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        return False


@pytest.mark.asyncio
async def test_egress_disabled_skips_nvidia(monkeypatch):
    _mock_ollama_down(monkeypatch)
    monkeypatch.setattr(config, "ALLOW_EXTERNAL_LLM", False)

    fake_openai_ctor = MagicMock()
    monkeypatch.setattr(answer, "AsyncOpenAI", fake_openai_ctor)

    result = await answer.generate_answer("What is the roll number?", "context here")

    assert "disabled" in result.lower()
    fake_openai_ctor.assert_not_called()


@pytest.mark.asyncio
async def test_redaction_masks_roll_before_egress(monkeypatch):
    _mock_ollama_down(monkeypatch)
    monkeypatch.setattr(config, "ALLOW_EXTERNAL_LLM", True)
    monkeypatch.setattr(config, "LLM_PII_REDACTION", True)
    monkeypatch.setenv("NVIDIA_API_KEY", "x")

    capture = {}
    fake_instance = _FakeAsyncOpenAI(capture)
    monkeypatch.setattr(answer, "AsyncOpenAI", fake_instance)

    roll = "2267571242025"
    result = await answer.generate_answer("Who is this student?", f"Roll number: {roll}")

    assert result == "fake nvidia answer"
    sent_content = capture["messages"][0]["content"]
    assert "[ROLL]" in sent_content
    assert roll not in sent_content


@pytest.mark.asyncio
async def test_no_redaction_sends_raw(monkeypatch):
    _mock_ollama_down(monkeypatch)
    monkeypatch.setattr(config, "ALLOW_EXTERNAL_LLM", True)
    monkeypatch.setattr(config, "LLM_PII_REDACTION", False)
    monkeypatch.setenv("NVIDIA_API_KEY", "x")

    capture = {}
    fake_instance = _FakeAsyncOpenAI(capture)
    monkeypatch.setattr(answer, "AsyncOpenAI", fake_instance)

    roll = "2267571242025"
    result = await answer.generate_answer("Who is this student?", f"Roll number: {roll}")

    assert result == "fake nvidia answer"
    sent_content = capture["messages"][0]["content"]
    assert roll in sent_content
