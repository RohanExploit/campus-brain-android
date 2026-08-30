"""
Hermetic generation tests. The Ollama client is created lazily
(generation.answer._get_http_client()), so these mock that accessor to return a
fake client whose .post is controlled — no httpx class patching, no network.
The API query path is covered in test_api.py.
"""
import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import generation.answer as answer


def _fake_client_with_post(post_mock):
    client = MagicMock()
    client.post = post_mock
    return client


@pytest.mark.asyncio
async def test_generate_answer_uses_ollama_response(monkeypatch):
    fake_resp = MagicMock()
    fake_resp.raise_for_status = MagicMock(return_value=None)
    fake_resp.json = MagicMock(return_value={"response": "  Mocked Answer  "})
    post = AsyncMock(return_value=fake_resp)
    monkeypatch.setattr(answer, "_get_http_client", lambda: _fake_client_with_post(post))

    out = await answer.generate_answer("What is this?", "This is a test context.")
    assert out == "Mocked Answer"  # stripped


@pytest.mark.asyncio
async def test_generate_answer_fallback_message_when_ollama_down_and_no_key(monkeypatch):
    # Ollama call raises; egress ALLOWED but no NVIDIA key -> deterministic graceful
    # message (no network). External egress is default-OFF as of Phase -1.2, so this
    # test explicitly opts in to reach the no-key fallback branch (the egress-disabled
    # branch is covered separately in test_answer_egress.py).
    import config
    monkeypatch.setattr(config, "ALLOW_EXTERNAL_LLM", True)
    post = AsyncMock(side_effect=RuntimeError("conn refused"))
    monkeypatch.setattr(answer, "_get_http_client", lambda: _fake_client_with_post(post))
    monkeypatch.delenv("NVIDIA_API_KEY", raising=False)

    out = await answer.generate_answer("q", "ctx")
    assert "fallback API key is configured" in out
