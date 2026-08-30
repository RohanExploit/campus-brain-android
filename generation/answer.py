import logging
import os
import re
import sys
import httpx
from pathlib import Path
from openai import AsyncOpenAI
sys.path.append(str(Path(__file__).resolve().parent.parent))

from config import API_TIMEOUT
import config
from utils.logging_config import setup_logging

setup_logging()

# Roll numbers are bare 10-15 digit tokens (see get_student_record). Masked out
# of the prompt before it leaves the machine when config.LLM_PII_REDACTION is on.
# The lookarounds reject runs that are part of a longer/decimal/grouped number
# (e.g. "123456789012.50" or "1,234,567,890") so legitimate monetary or measured
# values in the context aren't clipped to [ROLL] — real roll numbers, which are
# always standalone digit tokens, are still masked.
_ROLL_RE = re.compile(r"(?<![\d.,])\d{10,15}(?![\d.,])")


def _redact_pii(text: str) -> str:
    return _ROLL_RE.sub("[ROLL]", text)

# Ollama model + base URL are read live from config at call time (in
# generate_answer) rather than snapshotted into module constants at import, so an
# env override or a test monkeypatch of config.OLLAMA_* takes effect without
# re-importing this module — matching how retrieval/tabular_queries.py reads them.
OLLAMA_KEEP_ALIVE = "10m"

# Lazily create the shared client on first use so it binds to the running event
# loop (creating it at import time leaks a client tied to the import-time loop and
# produces "Event loop is closed" noise on Windows teardown). Closed on app
# shutdown via aclose_http_client() (wired in api/main.py's lifespan).
_http_client: httpx.AsyncClient | None = None


def _get_http_client() -> httpx.AsyncClient:
    global _http_client
    if _http_client is None or _http_client.is_closed:
        _http_client = httpx.AsyncClient(timeout=API_TIMEOUT)
    return _http_client


async def aclose_http_client() -> None:
    """Close the shared Ollama client. Idempotent; safe to call on shutdown."""
    global _http_client
    if _http_client is not None and not _http_client.is_closed:
        await _http_client.aclose()
    _http_client = None

async def generate_answer(query: str, context: str, qtype: str = "LOCAL") -> str:
    # NOTE: the GLOBAL template used to mandate a third section, "### 3. Citations /
    # Sources — [List the document sources or community references]". The GLOBAL route
    # feeds it CommunitySearch.get_all_summaries(), and those summaries are generated
    # from bare entity NAMES (ingestion/summarize_communities.py) — they contain no
    # source names at all. So the instruction could only ever be satisfied by invention,
    # and every GLOBAL answer shipped a fabricated citation block. Removed. Real
    # provenance now travels as metadata["sources"] from the retrieval path, and the
    # section earns its way back the day GLOBAL context actually carries labels.
    if qtype == "GLOBAL":
        prompt = f"""
You are the "Campus Brain", a helpful internal AI assistant answering a decision-assisting or broad query.
Answer the user's query based ONLY on the provided context. If the answer is not in the context, say "I don't have enough information to answer that."

You MUST format your answer using the following strict Markdown structure:
### 1. Recommendation / Summary
[Your high-level answer or recommendation]

### 2. Supporting Evidence
[Detailed points extracted from the context supporting the summary]

Context:
{context}

Query: {query}
Answer:
        """
    else:
        prompt = f"""
You are the "Campus Brain", a helpful internal AI assistant for an educational institution.
Answer the user's query using the provided context.

If the context does not contain the answer, decide which kind of question it is:

(a) It asks for a fact about THIS institution — its ranking, its fees, its results, its
staff, its policies, its dates, its numbers. Answer exactly: "I don't have enough
information to answer that." Never estimate, guess, or supply a typical value. A
plausible-sounding number here is worse than no answer.

(b) It asks what something MEANS or how something WORKS in general — a term, an
abbreviation, a standard procedure, a concept. Reply in exactly this shape:

I don't have enough information to answer that from your documents.

General knowledge (not from your institution's records): <a short, direct explanation>

Use option (b) only for genuinely general knowledge. Do not use it to introduce any
figure, name, date, or policy that could be mistaken for this institution's own.

Context:
{context}

Query: {query}
Answer:
        """

    payload = {
        "model": config.OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "keep_alive": OLLAMA_KEEP_ALIVE,
        "options": {"num_ctx": config.OLLAMA_NUM_CTX, "num_predict": 512, "temperature": 0}
    }

    try:
        response = await _get_http_client().post(f"{config.OLLAMA_BASE_URL}/api/generate", json=payload)
        response.raise_for_status()
        return response.json()["response"].strip()
    except Exception as e:
        logging.warning(f"Local Ollama generation failed: {e}. Attempting NVIDIA API fallback...")

        if not config.ALLOW_EXTERNAL_LLM:
            logging.warning(
                "Ollama failed and external LLM egress is disabled (ALLOW_EXTERNAL_LLM=0); "
                "returning local error."
            )
            return "Sorry, the local generation engine encountered an error and external LLM egress is disabled."

        nvidia_api_key = os.environ.get("NVIDIA_API_KEY")
        if not nvidia_api_key:
            logging.error("NVIDIA_API_KEY not found in environment variables. Fallback aborted.")
            return "Sorry, the local generation engine encountered an error and no fallback API key is configured."

        try:
            logging.warning("Falling back to EXTERNAL NVIDIA cloud API — prompt leaves the machine.")
            outbound_prompt = _redact_pii(prompt) if config.LLM_PII_REDACTION else prompt
            # async-with so the fallback client's connection pool is closed even
            # on error (it was previously created per call and never closed).
            async with AsyncOpenAI(
                base_url="https://integrate.api.nvidia.com/v1",
                api_key=nvidia_api_key,
                timeout=API_TIMEOUT,
            ) as client:
                completion = await client.chat.completions.create(
                    model="meta/llama-3.1-70b-instruct",
                    messages=[{"role": "user", "content": outbound_prompt}],
                    temperature=0,
                    max_tokens=1024,
                )
            fallback_answer = completion.choices[0].message.content.strip()
            logging.info("Successfully generated answer using NVIDIA API fallback.")
            return fallback_answer
        except Exception as fallback_e:
            logging.error(f"NVIDIA API fallback also failed: {fallback_e}")
            return "Sorry, both local generation and the fallback API encountered an error."

if __name__ == "__main__":
    import asyncio
    ctx = "RAG-MicroSim is a hybrid framework."
    q = "What is RAG-MicroSim?"
    # generate_answer is a coroutine — must be awaited, not printed directly.
    print(asyncio.run(generate_answer(q, ctx)))
