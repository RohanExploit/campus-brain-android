# Phase 4 — Contact With Reality: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the system giving confidently wrong answers, make it safe to expose, make ingestion work for a stranger's documents, and get 100 real questions from people outside the team.

**Architecture:** A polarity/synonym normaliser is inserted at the two places that classify a TABULAR question (`sql_templates.match_template` and `intent.classify_tabular_intent`), because the API short-circuits TABULAR without ever calling the generator — so no downstream guard can catch an inverted answer. The sentinel-driven TABULAR→FACT fallback in `router.py` is made discriminating rather than blanket. Auth defaults flip closed. Ingestion is repaired against a clone of the only corpus that exists.

**Tech Stack:** Python 3.12, FastAPI, DuckDB, FAISS, NetworkX, Ollama (`qwen3:4b-instruct-2507-q4_K_M`), pytest, Next.js 16.

## Global Constraints

- **The live demo must keep working.** `pytest tests/test_demo_script.py` must pass after every task from Task 2 onward.
- **Never delete or mutate anything derived from student data.** `data/tenants/tenant_1/` is read-only to this plan except through Task 12's explicit clone-then-cutover.
- **`data/` is gitignored.** `git ls-files data` returns zero files. The tenant_1 corpus exists in exactly one place on one machine.
- **Offline/on-premises claims stay exactly as written.** No task edits any offline, on-prem, local-first, or zero-egress wording.
- **Nothing already sent to a third party is silently edited.**
- **Solo execution.** No task names or assigns a second person.
- **Never delete a router keyword.** Tighten with word boundaries only: a false positive costs one wasted Ollama call, a false negative costs the demo.
- **Tests for Tasks 2–8 must be hermetic** — no GPU, no Ollama, no corpus — so they run in the existing `ubuntu-latest` CI job.
- Existing suite baseline: **310 passed, 1 skipped**. Never commit a red suite.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `tests/test_demo_script.py` | **Create.** Freezes the five DEMO_RUNBOOK queries against live tenant_1. Not hermetic; marked `@pytest.mark.demo`. | 1 |
| `docs/tenant1_backup.sha256` | **Create.** Manifest of the external corpus backup. | 1 |
| `retrieval/question_norm.py` | **Create.** The single home for polarity detection and synonym normalisation. No I/O, no imports from retrieval siblings — pure functions over strings. | 2, 4 |
| `tests/test_question_norm.py` | **Create.** Unit tests for the normaliser in isolation. | 2, 4 |
| `retrieval/sql_templates.py` | **Modify.** `match_template` consults the normaliser before its `pass`/`fail` branches. | 3 |
| `retrieval/intent.py` | **Modify.** `classify_tabular_intent` consults the normaliser; `paper` reaches the subject regex; SGPA-threshold phrasing stops falling to `name_search`. | 5 |
| `retrieval/router.py` | **Modify.** Sentinel fallback at ~line 336 becomes discriminating. | 6 |
| `retrieval/tabular_queries.py` | **Modify.** Sentinels become a named, importable set instead of inline literals. | 6 |
| `config.py` | **Modify.** `REQUIRE_API_KEY` and `LLM_PII_REDACTION` default to 1. | 7 |
| `start.py` | **Modify.** Refuse a `0.0.0.0` bind when the key gate is off. | 7 |
| `dashboard/src/app/upload/page.tsx` | **Modify.** Success string stops claiming indexing happened. | 8 |
| `tests/__init__.py`, `tests/eval/__init__.py` | **Create.** Empty; unblocks documented eval commands. | 8 |
| `docs/INVESTOR_LINK.md` | **Create.** Open/close commands for the tunnel. | 9 |
| `docs/parser_experiment.md` | **Create.** Outcome of the second-college parser test. | 10 |
| `ingestion/parse_tabular.py` | **Modify.** `--pdf-dir` / `--tenant` flags replace three hardcoded paths. | 11 |
| `ingestion/extract_entities.py` | **Modify.** Per-file checkpoint/resume; drop `student` from `excluded_keywords`. | 12 |
| `pipeline.py` | **Modify.** Manifest schema aligned to `ingestion/parse.py`. | 12 |
| `ingestion/pipeline.py` | **Delete.** 43-line all-commented stub. | 12 |
| `docs/ONBOARD_RUNBOOK.md` | **Create.** Exact commands to ingest a stranger's folder. | 12 |
| `retrieval/query_log.py` | **Create.** Append-only JSONL logger, PII-scrubbed. | 13 |

---

## Task 1: Freeze the demo and back up the only corpus

**Files:**
- Create: `tests/test_demo_script.py`
- Create: `docs/tenant1_backup.sha256`
- Modify: `pytest.ini` (register the `demo` marker)

**Interfaces:**
- Consumes: nothing.
- Produces: `pytest -m demo` as the regression gate every later task must keep green.

- [ ] **Step 1: Copy the corpus to external storage**

Plug in an external drive. Replace `E:` with its actual letter.

```powershell
robocopy "R:\Startup research\Start up V2\data\tenants\tenant_1" "E:\company-brain-backup\tenant_1" /MIR /R:2 /W:5
```

Expected: `robocopy` exits with code 0–7 (anything ≥8 is a failure). Confirm the copy is non-empty:

```powershell
(Get-ChildItem "E:\company-brain-backup\tenant_1" -Recurse -File | Measure-Object).Count
```

Expected: a count in the thousands, not 0.

- [ ] **Step 2: Write the backup manifest**

```powershell
cd "R:\Startup research\Start up V2"
Get-ChildItem "E:\company-brain-backup\tenant_1" -Recurse -File |
  Get-FileHash -Algorithm SHA256 |
  ForEach-Object { "$($_.Hash)  $($_.Path)" } |
  Set-Content -Encoding utf8 docs\tenant1_backup.sha256
(Get-Content docs\tenant1_backup.sha256 | Measure-Object -Line).Lines
```

Expected: same line count as the file count from Step 1.

- [ ] **Step 3: Register the `demo` marker**

Add to `pytest.ini` under `[pytest]`:

```ini
markers =
    demo: hits live tenant_1 and a running API; excluded from the hermetic CI run
```

- [ ] **Step 4: Write the demo-freeze test**

Create `tests/test_demo_script.py`. These five queries are the ones in `docs/DEMO_RUNBOOK.md` at lines 49, 58, 67, 75 and 83.

```python
"""Freezes the demo script from docs/DEMO_RUNBOOK.md against live tenant_1.

Not hermetic: needs a running API on 127.0.0.1:8000 with tenant_1 indexed and
Ollama warm. Excluded from CI via the `demo` marker. This is the tripwire for
every routing change in this plan -- if a fix breaks the demo, it fails here
before it fails in front of an audience.
"""
import json
import urllib.request

import pytest

pytestmark = pytest.mark.demo

API = "http://127.0.0.1:8000/query"
TENANT = "tenant_1"


def ask(query: str) -> dict:
    body = json.dumps({"query": query, "tenant_id": TENANT}).encode()
    req = urllib.request.Request(
        API, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read())


def test_tabular_multi_fail():
    """DEMO_RUNBOOK.md:49 -- the headline TABULAR query."""
    d = ask("How many students failed at least 2 subjects?")
    assert d["query_type"] == "TABULAR"
    assert "16" in d["answer"]


def test_local_multi_hop():
    """DEMO_RUNBOOK.md:58 -- the graph traversal that sells the demo."""
    d = ask("Which trust runs DACOE Karad?")
    assert d["query_type"] == "LOCAL"
    assert "Gujar" in d["answer"]


def test_fact_fees():
    """DEMO_RUNBOOK.md:67 -- FACT with provenance."""
    d = ask("What is the fee structure?")
    assert d["query_type"] == "FACT"
    assert "1500" in d["answer"]


def test_abstention_on_absent_fact():
    """DEMO_RUNBOOK.md:75 -- must refuse, never invent a rank."""
    d = ask("What NIRF rank did the college get?")
    assert "don't have enough information" in d["answer"].lower()


def test_knowledge_tier_explains_general_term():
    """DEMO_RUNBOOK.md:83 -- refuses the institutional claim, still explains the term."""
    d = ask("What does SGPA mean?")
    low = d["answer"].lower()
    assert "don't have enough information" in low
    assert "general knowledge" in low
```

- [ ] **Step 5: Bring the stack up and run the test**

```bash
powershell -ExecutionPolicy Bypass -File scripts/demo_up.ps1
.venv312/Scripts/python.exe -m pytest tests/test_demo_script.py -v -m demo
```

Expected: `5 passed`. If any assertion fails, **do not adjust the test to match** — the recorded values (16, Gujar, 1500) are today's verified-correct answers. A failure here means the stack is not fully up.

- [ ] **Step 6: Confirm the hermetic suite still collects cleanly**

```bash
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
```

Expected: `310 passed, 1 skipped`.

- [ ] **Step 7: Commit and tag**

```bash
git add tests/test_demo_script.py docs/tenant1_backup.sha256 pytest.ini
git commit -m "test(demo): freeze the demo script and record the corpus backup manifest

data/ is gitignored and git ls-files data returns zero files, so tenant_1's 161
documents exist in exactly one place on one laptop -- and the next tasks rewrite
the routing cascade it runs on. An external copy now exists with a committed
sha256 manifest, and the five DEMO_RUNBOOK queries are a pytest that asserts
today's verified answers. Marked `demo` because it needs a live API and a warm
model; CI runs -m 'not demo'."
git tag demo-known-good
```

---

## Task 2: The polarity normaliser

**Files:**
- Create: `retrieval/question_norm.py`
- Create: `tests/test_question_norm.py`

**Interfaces:**
- Consumes: nothing. Pure string functions, no imports from `retrieval` siblings, so it stays unit-testable and cannot create an import cycle.
- Produces:
  - `detect_polarity(query: str) -> str` returning `"positive"`, `"negative"`, or `"ambiguous"`.
  - `INVERTIBLE: dict[str, str]` mapping `"PASS" -> "FAIL"` and `"FAIL" -> "PASS"`.
  - `invert_status(status: str) -> str`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_question_norm.py`:

```python
"""Unit tests for the polarity/synonym normaliser.

Hermetic: no DB, no model, no corpus.
"""
import pytest

from retrieval.question_norm import detect_polarity, invert_status


@pytest.mark.parametrize("q", [
    "How many students did not pass?",
    "how many students didn't pass",
    "count of students who did not pass the semester",
    "how many students failed to pass",
    "number of students that did not clear the exam",
])
def test_negated_phrasings_detected(q):
    assert detect_polarity(q) == "negative"


@pytest.mark.parametrize("q", [
    "How many students passed?",
    "What percentage of students passed?",
    "how many students cleared the exam",
])
def test_plain_phrasings_are_positive(q):
    assert detect_polarity(q) == "positive"


def test_double_negative_is_ambiguous_not_guessed():
    """Two negations must refuse rather than pick a side."""
    assert detect_polarity("how many students did not fail to pass") == "ambiguous"


def test_invert_status_round_trips():
    assert invert_status("PASS") == "FAIL"
    assert invert_status("FAIL") == "PASS"


def test_invert_status_rejects_unknown():
    with pytest.raises(KeyError):
        invert_status("PENDING")
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v
```

Expected: `ModuleNotFoundError: No module named 'retrieval.question_norm'`.

- [ ] **Step 3: Write the implementation**

Create `retrieval/question_norm.py`:

```python
"""Polarity and synonym normalisation for TABULAR questions.

Why this module exists at all: api/main.py returns a TABULAR answer directly
without ever calling generate_answer(), so the generation-layer abstention
guards never see a tabular question. "How many students did not pass?" matched
the keyword "pass", hit result_count(status="PASS") and answered "334 students
passed" -- numerically inverted, phrased as a finished confident sentence, with
no downstream guard able to catch it. Polarity therefore has to be decided at
classification time, which happens in two separate places (sql_templates.
match_template and intent.classify_tabular_intent), so it lives here rather
than in either of them.

Pure functions over strings. No DB, no model, no imports from retrieval
siblings -- so it is unit-testable and cannot create an import cycle.
"""
import re

# Negation markers. Ordered longest-first is unnecessary (we only count), but
# each pattern is anchored on word boundaries so "cannot" does not match inside
# a longer token and "not" does not match inside "notable".
_NEGATIONS = [
    r"\bdid\s*n[o']t\b",
    r"\bdo\s*n[o']t\b",
    r"\bdoes\s*n[o']t\b",
    r"\bhave\s*n[o']t\b",
    r"\bhas\s*n[o']t\b",
    r"\bwere\s*n[o']t\b",
    r"\bwas\s*n[o']t\b",
    r"\bis\s*n[o']t\b",
    r"\bare\s*n[o']t\b",
    r"\bnot\b",
    r"\bnever\b",
    r"\bfailed\s+to\b",
    r"\bunable\s+to\b",
    r"\bwithout\b",
]
_NEG_RE = re.compile("|".join(_NEGATIONS), re.IGNORECASE)

# PASS and FAIL are the only statuses the schema stores at student level, and
# they are exact complements -- every student is one or the other. Any status
# added later must be added here deliberately, which is why invert_status
# raises on an unknown value instead of returning it unchanged.
INVERTIBLE: dict[str, str] = {"PASS": "FAIL", "FAIL": "PASS"}


def invert_status(status: str) -> str:
    """Flip PASS<->FAIL. Raises KeyError on anything else, by design."""
    return INVERTIBLE[status.upper()]


def detect_polarity(query: str) -> str:
    """Classify a question as positive, negative, or ambiguous.

    Returns "ambiguous" for two or more negations rather than trying to resolve
    them. A double negative is rare and genuinely hard; guessing produces the
    same class of confident-wrong answer this module exists to prevent, so the
    caller is expected to refuse instead.
    """
    hits = _NEG_RE.findall(query or "")
    if len(hits) == 0:
        return "positive"
    if len(hits) == 1:
        return "negative"
    return "ambiguous"
```

- [ ] **Step 4: Run the tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v
```

Expected: `10 passed`.

- [ ] **Step 5: Commit**

```bash
git add retrieval/question_norm.py tests/test_question_norm.py
git commit -m "feat(retrieval): polarity detection for tabular questions

api/main.py short-circuits TABULAR without calling generate_answer(), so every
abstention safeguard in the generation layer is structurally unreachable for
tabular questions. Polarity has to be decided at classification time instead.
Two negations return 'ambiguous' rather than a guess -- resolving a double
negative wrongly produces exactly the confident-wrong answer this exists to
prevent."
```

---

## Task 3: Wire polarity into `match_template`

**Files:**
- Modify: `retrieval/sql_templates.py:378-381` (the `result_count(status="FAIL")` branch) and `:382-386` (the `result_count(status="PASS")` branch)
- Modify: `tests/test_question_norm.py` (add integration cases)

**Interfaces:**
- Consumes: `detect_polarity`, `invert_status` from Task 2.
- Produces: `match_template` returns `(result_count, {"status": "FAIL"})` for negated pass-questions, and `None` for ambiguous ones.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_question_norm.py`:

```python
from retrieval.sql_templates import match_template, result_count


def test_negated_pass_question_routes_to_fail_count():
    """The bug this plan exists for: 'did not pass' answered with the pass count."""
    matched = match_template("How many students did not pass?")
    assert matched is not None
    fn, kwargs = matched
    assert fn is result_count
    assert kwargs == {"status": "FAIL"}


def test_plain_pass_question_still_routes_to_pass_count():
    fn, kwargs = match_template("How many students passed?")
    assert fn is result_count
    assert kwargs == {"status": "PASS"}


def test_negated_fail_question_routes_to_pass_count():
    fn, kwargs = match_template("How many students did not fail?")
    assert fn is result_count
    assert kwargs == {"status": "PASS"}


def test_ambiguous_polarity_refuses_the_template():
    """No template is better than an inverted one."""
    assert match_template("how many students did not fail to pass") is None
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v -k "routes_to or refuses"
```

Expected: `test_negated_pass_question_routes_to_fail_count` FAILS with `kwargs == {'status': 'PASS'}`.

- [ ] **Step 3: Add the import at the top of `retrieval/sql_templates.py`**

Beside the existing imports:

```python
from retrieval.question_norm import detect_polarity, invert_status
```

- [ ] **Step 4: Replace the two `result_count` branches**

Find this block at `retrieval/sql_templates.py:373-386`:

```python
    if (("how many" in q or "number of" in q or "count of" in q) and "fail" in q
            and not named_subjects and "subject" not in q
            and "at least" not in q and "atleast" not in q
            and "most" not in q and "backlog" not in q):
        return result_count, {"status": "FAIL"}

    # passed-student count — subject-scoped questions fall through to dynamic SQL
    if ("pass" in q and ("how many" in q or "number of" in q or "count of" in q)
            and not named_subjects and "subject" not in q
            and "percent" not in q and "rate" not in q and "%" not in q):
        return result_count, {"status": "PASS"}
```

Replace with:

```python
    # Student-level PASS/FAIL counts. Both branches run their literal status
    # through the polarity check before returning: "how many students did not
    # pass" reaches the second branch on the keyword "pass" and, before this,
    # answered with the PASS count -- the exact inversion documented as a live
    # demo caveat. "not" is excluded from the guard sets below because polarity
    # now handles it; leaving it as a guard would make the question unmatchable
    # instead of correctly matched.
    _counting = ("how many" in q or "number of" in q or "count of" in q)
    _polarity = detect_polarity(query)

    if (_counting and "fail" in q
            and not named_subjects and "subject" not in q
            and "at least" not in q and "atleast" not in q
            and "most" not in q and "backlog" not in q):
        if _polarity == "ambiguous":
            return None
        status = "FAIL" if _polarity == "positive" else invert_status("FAIL")
        return result_count, {"status": status}

    # passed-student count — subject-scoped questions fall through to dynamic SQL
    if ("pass" in q and _counting
            and not named_subjects and "subject" not in q
            and "percent" not in q and "rate" not in q and "%" not in q):
        if _polarity == "ambiguous":
            return None
        status = "PASS" if _polarity == "positive" else invert_status("PASS")
        return result_count, {"status": status}
```

- [ ] **Step 5: Run the new tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v
```

Expected: `14 passed`.

- [ ] **Step 6: Run the full hermetic suite for regressions**

```bash
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
```

Expected: `310 passed, 1 skipped`. If `tests/test_tabular_analytics_fixes.py` or `tests/test_sql_templates.py` fail, a guard word was dropped that another test depends on — re-read the diff before changing any existing test.

- [ ] **Step 7: Verify the demo still passes**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_demo_script.py -v -m demo
```

Expected: `5 passed`.

- [ ] **Step 8: Commit**

```bash
git add retrieval/sql_templates.py tests/test_question_norm.py
git commit -m "fix(tabular): stop answering negated questions with the inverted count

'How many students did not pass?' matched the keyword 'pass', returned
result_count(status='PASS') and answered '334 students passed'. Both
student-level count branches now run their status through detect_polarity
before returning, and an ambiguous double negative returns no template at all
rather than a guess."
```

---

## Task 4: Synonym normalisation

**Files:**
- Modify: `retrieval/question_norm.py`
- Modify: `tests/test_question_norm.py`

**Interfaces:**
- Consumes: nothing new.
- Produces: `normalise_synonyms(query: str) -> str`, and `SGPA_THRESHOLD_RE` matching "scoring N or higher" style phrasings.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_question_norm.py`:

```python
from retrieval.question_norm import normalise_synonyms, SGPA_THRESHOLD_RE


def test_paper_becomes_subject():
    out = normalise_synonyms("How many students failed the paper BTCOC501?")
    assert "subject BTCOC501" in out


def test_papers_plural_becomes_subject():
    assert "subject" in normalise_synonyms("which papers have the most failures")


def test_subject_is_left_alone():
    q = "How many students failed the subject BTCOC501?"
    assert normalise_synonyms(q) == q


def test_newspaper_is_not_rewritten():
    """Word-boundary safety: never rewrite 'paper' inside a longer word."""
    q = "where is the newspaper archive"
    assert normalise_synonyms(q) == q


@pytest.mark.parametrize("q,expected", [
    ("List students scoring 8 or higher SGPA", 8.0),
    ("students scoring 7.5 or above", 7.5),
    ("count students with SGPA 9 or more", 9.0),
])
def test_sgpa_threshold_extracted(q, expected):
    m = SGPA_THRESHOLD_RE.search(q)
    assert m is not None
    assert float(m.group(1)) == expected
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v -k "paper or sgpa_threshold"
```

Expected: `ImportError: cannot import name 'normalise_synonyms'`.

- [ ] **Step 3: Append the implementation to `retrieval/question_norm.py`**

```python
# "paper" is the word students and staff actually use for a subject. The
# subject-code regex in intent.py is written as r'subject\s+(BT\w+)', so a
# question phrased "failed the paper BTCOC501" silently dropped the code and
# answered with the global failure count instead of the subject-scoped one.
# Word-boundary anchored so "newspaper" is never rewritten.
_SYNONYMS = [
    (re.compile(r"\bpapers\b", re.IGNORECASE), "subjects"),
    (re.compile(r"\bpaper\b", re.IGNORECASE), "subject"),
]

# "scoring 8 or higher", "with SGPA 7.5 or above", "9 or more" -- all of which
# previously fell through to name_search, which searches student NAMES and so
# returned a not-found message for a perfectly answerable aggregate.
SGPA_THRESHOLD_RE = re.compile(
    r"(\d+(?:\.\d+)?)\s*(?:or\s+(?:higher|above|more)|\+)",
    re.IGNORECASE,
)


def normalise_synonyms(query: str) -> str:
    """Rewrite user vocabulary into the vocabulary the matchers key on."""
    out = query or ""
    for rx, repl in _SYNONYMS:
        out = rx.sub(repl, out)
    return out
```

- [ ] **Step 4: Run the tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v
```

Expected: `21 passed`.

- [ ] **Step 5: Commit**

```bash
git add retrieval/question_norm.py tests/test_question_norm.py
git commit -m "feat(retrieval): synonym normalisation for paper/subject and SGPA thresholds

'paper' is what students call a subject, and intent.py's subject regex only
matches the literal word 'subject' -- so 'failed the paper BTCOC501' dropped
the code and answered with the global failure count. 'scoring 8 or higher'
fell through to name_search, which searches student names. Word boundaries
keep 'newspaper' intact."
```

---

## Task 5: Wire synonyms into `classify_tabular_intent`

**Files:**
- Modify: `retrieval/intent.py:44-80`
- Modify: `tests/test_question_norm.py`

**Interfaces:**
- Consumes: `normalise_synonyms`, `SGPA_THRESHOLD_RE`, `detect_polarity` from Tasks 2 and 4.
- Produces: `classify_tabular_intent` returns `TabularIntent("count_sgpa_at_least", {"threshold": float})` for threshold phrasings instead of `TabularIntent("name_search")`.

- [ ] **Step 1: Write the failing test**

Append to `tests/test_question_norm.py`:

```python
from retrieval.intent import classify_tabular_intent


def test_sgpa_threshold_does_not_reach_name_search():
    intent = classify_tabular_intent("List students scoring 8 or higher SGPA")
    assert intent.kind != "name_search"
    assert intent.params["threshold"] == 8.0


def test_paper_scoped_failure_count_keeps_the_subject_code():
    intent = classify_tabular_intent("How many students failed the paper BTCOC501?")
    assert intent.kind == "count_failures"
    assert intent.params["subject"] == "BTCOC501"


def test_plain_name_lookup_still_reaches_name_search():
    """Guard against over-reach: a real name lookup must be untouched."""
    intent = classify_tabular_intent("search for gaikwad rohan vijay")
    assert intent.kind == "name_search"
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v -k "name_search or paper_scoped"
```

Expected: `test_sgpa_threshold_does_not_reach_name_search` FAILS with `intent.kind == 'name_search'`.

- [ ] **Step 3: Add the import to `retrieval/intent.py`**

```python
from retrieval.question_norm import SGPA_THRESHOLD_RE, normalise_synonyms
```

- [ ] **Step 4: Normalise once at the top of `classify_tabular_intent`**

Find the line that lowercases the query (the `q_lower = ...` assignment near the top of the function) and replace it with:

```python
    # Rewrite user vocabulary into the vocabulary the branches below key on,
    # BEFORE any branch runs. The subject regex further down matches the
    # literal word "subject", so "paper BTCOC501" has to become
    # "subject BTCOC501" here or the code is silently dropped.
    query = normalise_synonyms(query)
    q_lower = query.lower()

    # An SGPA threshold question is an aggregate, not a person lookup. Checked
    # before the _LOOKUP_KW branch because "scoring" is in _LOOKUP_KW via
    # "score", which sent "students scoring 8 or higher" to name_search -- a
    # search over student NAMES for a question about a number.
    if "sgpa" in q_lower or "score" in q_lower or "scoring" in q_lower:
        m = SGPA_THRESHOLD_RE.search(query)
        if m and "below" not in q_lower and "under" not in q_lower:
            return TabularIntent("count_sgpa_at_least",
                                 {"threshold": float(m.group(1))})
```

Also extend the `kind` comment on the `TabularIntent` dataclass at
`retrieval/intent.py:11-12`, which enumerates the valid kinds, to include
`count_sgpa_at_least`. It is documentation, not validation, but a stale list is
how the next person picks a wrong kind.

- [ ] **Step 5: Confirm the router can dispatch the new intent kind**

`retrieval/router.py` dispatches on `intent.kind`. Add a branch beside the existing `elif intent.kind == "average_sgpa":` block:

```python
                    elif intent.kind == "count_sgpa_at_least":
                        from retrieval.sql_templates import count_sgpa_at_least
                        result = await asyncio.to_thread(
                            count_sgpa_at_least,
                            threshold=intent.params["threshold"],
                            tenant_id=self.tenant_id,
                        )
                        context = result.get("answer", "")
                        metadata["template"] = result.get("template")
```

- [ ] **Step 6: Run the tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_question_norm.py -v
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
```

Expected: `24 passed` then `310 passed, 1 skipped`.

- [ ] **Step 7: Verify the demo still passes**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_demo_script.py -v -m demo
```

Expected: `5 passed`.

- [ ] **Step 8: Commit**

```bash
git add retrieval/intent.py retrieval/router.py tests/test_question_norm.py
git commit -m "fix(intent): threshold questions stop searching student names

'score' is in _LOOKUP_KW, so 'List students scoring 8 or higher SGPA' matched
the person-lookup branch and searched student NAMES for a question about a
number. Threshold detection now runs first, and synonym normalisation runs
before any branch so 'paper BTCOC501' reaches the subject regex."
```

---

## Task 6: Make the sentinel fallback discriminating

**Files:**
- Modify: `retrieval/tabular_queries.py:554,560,581,589,592`
- Modify: `retrieval/router.py:336-341`
- Create: `tests/test_sentinel_fallback.py`

**Interfaces:**
- Consumes: nothing new.
- Produces: `retrieval.tabular_queries.ENGINE_FAILURE_SENTINELS: tuple[str, ...]` and `NO_ROWS_SENTINEL: str`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_sentinel_fallback.py`:

```python
"""The TABULAR->FACT fallback must distinguish engine failure from a true zero.

Hermetic: imports the sentinel constants and the classifier, no DB or model.
"""
from retrieval.tabular_queries import (
    ENGINE_FAILURE_SENTINELS,
    NO_ROWS_SENTINEL,
    is_engine_failure,
)


def test_engine_failures_are_recognised():
    for s in ("Failed to reach Ollama.",
              "Query rejected by guardrail: no SELECT",
              "Query rejected by guardrail on retry: no SELECT",
              "Error executing SQL: Binder Error"):
        assert is_engine_failure(s), s


def test_no_rows_is_not_an_engine_failure():
    """A correct zero must not be overwritten by a fluent vector answer."""
    assert not is_engine_failure(NO_ROWS_SENTINEL)


def test_a_real_answer_is_not_an_engine_failure():
    assert not is_engine_failure("Pass percentage: 90.5% (334 of 369 students).")


def test_sentinels_are_declared_not_inferred():
    assert NO_ROWS_SENTINEL not in ENGINE_FAILURE_SENTINELS
    assert len(ENGINE_FAILURE_SENTINELS) == 4
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_sentinel_fallback.py -v
```

Expected: `ImportError: cannot import name 'ENGINE_FAILURE_SENTINELS'`.

- [ ] **Step 3: Declare the sentinels in `retrieval/tabular_queries.py`**

Add near the top of the module:

```python
# The tabular path signals failure by returning a sentinel string in "answer".
# These four mean the ENGINE broke and produced no information -- falling back
# to the vector path can only improve the answer.
ENGINE_FAILURE_SENTINELS: tuple[str, ...] = (
    "Failed to reach Ollama.",
    "Query rejected by guardrail:",
    "Query rejected by guardrail on retry:",
    "Error executing SQL:",
)

# This one is different, and the difference matters. "Query returned no
# results." is frequently a CORRECT answer -- zero students match. Falling
# back to FACT here replaces an accurate robotic zero with a fluent vector
# answer that is very likely wrong, which is worse than what it replaced.
NO_ROWS_SENTINEL: str = "Query returned no results."


def is_engine_failure(answer: str) -> bool:
    """True when the tabular answer is an engine failure rather than a result."""
    a = (answer or "").strip()
    return any(a.startswith(s) for s in ENGINE_FAILURE_SENTINELS)
```

Then replace each inline literal at lines 554, 560, 581, 589 and 592 with the corresponding constant, e.g. line 592 becomes:

```python
        return {"answer": NO_ROWS_SENTINEL, "debug_sql": sql}
```

- [ ] **Step 4: Make the router discriminate**

`retrieval/router.py` currently falls back whenever context is empty. Sentinel strings are non-empty, so today they are passed through as answers. Replace the fallback block at `retrieval/router.py:336-341`:

```python
            if config.TABULAR_FACT_FALLBACK and not str(context).strip():
                context = self._fact_context(query)
                metadata["tabular_fallback"] = "TABULAR->FACT"
                qtype = "FACT"
                logging.info("TABULAR->FACT fallback engaged")
```

with:

```python
            # Three fallback conditions, deliberately distinguished.
            #
            #   empty context      -> the cascade produced nothing; fall back.
            #   engine failure     -> the engine broke and knows nothing; fall back.
            #   "no results"       -> usually a CORRECT zero. Falling back here
            #                         swaps an accurate robotic answer for a
            #                         fluent vector one that is probably wrong,
            #                         so it is rendered honestly instead.
            from retrieval.tabular_queries import NO_ROWS_SENTINEL, is_engine_failure

            _ctx = str(context).strip()
            if config.TABULAR_FACT_FALLBACK and (not _ctx or is_engine_failure(_ctx)):
                if _ctx:
                    metadata["tabular_error"] = "engine_sentinel"
                context = self._fact_context(query)
                metadata["tabular_fallback"] = "TABULAR->FACT"
                qtype = "FACT"
                logging.info("TABULAR->FACT fallback engaged")
            elif _ctx == NO_ROWS_SENTINEL:
                metadata["tabular_zero"] = True
                return qtype, "No records match that query.", metadata
```

- [ ] **Step 5: Run the tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_sentinel_fallback.py -v
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
```

Expected: `4 passed` then `310 passed, 1 skipped`.

- [ ] **Step 6: Verify the demo still passes**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_demo_script.py -v -m demo
```

Expected: `5 passed`.

- [ ] **Step 7: Commit**

```bash
git add retrieval/tabular_queries.py retrieval/router.py tests/test_sentinel_fallback.py
git commit -m "fix(router): distinguish engine failure from a correct zero

The four sentinel strings the tabular path returns were inline literals the
router never checked, so they rendered as answers. Three of them mean the
engine broke and now trigger the FACT fallback. The fourth, 'Query returned no
results.', usually means a correct zero -- falling back there would replace an
accurate answer with a fluent guess, so it renders as an honest no-match."
```

---

## Task 7: Auth and PII defaults fail closed

**Files:**
- Modify: `config.py:133`, `config.py:161`
- Modify: `start.py`
- Create: `tests/test_secure_defaults.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `REQUIRE_API_KEY` and `LLM_PII_REDACTION` default to `1`; `start.py` exits non-zero on an unauthenticated `0.0.0.0` bind.

**Note:** this task is a hard prerequisite for Task 9 (the investor tunnel). A tunnel exposes every route, not just `/m`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_secure_defaults.py`:

```python
"""The unauthenticated-by-default posture is the one thing a tunnel makes fatal.

Hermetic: reads config defaults with the env vars cleared.
"""
import importlib

import pytest


@pytest.fixture
def fresh_config(monkeypatch):
    for var in ("REQUIRE_API_KEY", "LLM_PII_REDACTION"):
        monkeypatch.delenv(var, raising=False)
    import config
    return importlib.reload(config)


def test_api_key_gate_defaults_on(fresh_config):
    assert fresh_config.require_api_key_enabled() is True


def test_pii_redaction_defaults_on(fresh_config):
    assert fresh_config.LLM_PII_REDACTION is True


def test_explicit_opt_out_still_works(fresh_config, monkeypatch):
    """Turning it off must stay possible -- deliberately, not by accident."""
    monkeypatch.setenv("REQUIRE_API_KEY", "0")
    assert fresh_config.require_api_key_enabled() is False
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_secure_defaults.py -v
```

Expected: two failures, `assert False is True`.

- [ ] **Step 3: Flip the defaults**

`config.py:133`:

```python
    return _truthy(os.environ.get("REQUIRE_API_KEY", "1"))
```

`config.py:161` — replace the `LLM_PII_REDACTION` line and its comment:

```python
# When ON, mask roll-number-like digit runs in the prompt before it leaves the
# machine. Defaults ON: this only has an effect on the cloud-fallback path,
# which is itself off by default, so the cost of the safe default is zero and
# the cost of the unsafe one is roll numbers in a third party's logs.
LLM_PII_REDACTION = _truthy(os.environ.get("LLM_PII_REDACTION", "1"))
```

- [ ] **Step 4: Refuse an unauthenticated public bind in `start.py`**

Insert immediately after `args = parser.parse_args()`:

```python
    # A 0.0.0.0 bind puts /upload, /review and /documents on every interface,
    # and those endpoints carry no auth of their own. Refuse rather than warn:
    # a warning scrolls past, and the failure mode is a corpus of real student
    # records writable by anyone on the network.
    import config
    if args.host not in ("127.0.0.1", "localhost") and not config.require_api_key_enabled():
        sys.stderr.write(
            f"\n[start.py] Refusing to bind {args.host} with REQUIRE_API_KEY=0.\n"
            "The admin API (/upload, /review, /documents) has no auth of its own.\n"
            "Set REQUIRE_API_KEY=1 and API_KEY=<secret>, or bind 127.0.0.1.\n\n"
        )
        sys.exit(2)
```

- [ ] **Step 5: Run the tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_secure_defaults.py -v
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
```

Expected: `3 passed`, then the full suite. **Existing tests that assumed an open API will now fail** — that is the point. Fix them by setting `REQUIRE_API_KEY=0` explicitly in those tests' fixtures, never by reverting the default.

- [ ] **Step 6: Verify the refusal works and the loopback path still runs**

```bash
.venv312/Scripts/python.exe start.py --host 0.0.0.0 --port 8000
```

Expected: exits 2 with the refusal message.

```bash
REQUIRE_API_KEY=0 powershell -ExecutionPolicy Bypass -File scripts/demo_up.ps1
.venv312/Scripts/python.exe -m pytest tests/test_demo_script.py -v -m demo
```

Expected: `5 passed`. Then update `scripts/demo_up.ps1` to export `REQUIRE_API_KEY=0` for its loopback default, so the demo keeps working without a key on localhost.

- [ ] **Step 7: Commit**

```bash
git add config.py start.py scripts/demo_up.ps1 tests/test_secure_defaults.py
git commit -m "fix(config): auth and PII redaction default closed

REQUIRE_API_KEY defaulted to 0 while /upload, /review and /documents carry no
auth of their own, so any 0.0.0.0 bind granted write access to a corpus of real
student records. start.py now refuses that bind outright rather than warning.
LLM_PII_REDACTION defaults on: it only affects the cloud-fallback path, which
is already off, so the safe default costs nothing."
```

---

## Task 8: The honesty fixes

**Files:**
- Modify: `dashboard/src/app/upload/page.tsx:178`
- Create: `tests/__init__.py`, `tests/eval/__init__.py`
- Delete or rename: `tests/test_router_fallback.py`, `tests/test_tabular.py`
- Modify: `README.md` (the `audit_06` deployment-gate claim)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Confirm the two test files really collect nothing**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_router_fallback.py tests/test_tabular.py --collect-only -q
```

Expected: `no tests ran`. If either collects tests, **do not delete it** — report and stop.

- [ ] **Step 2: Fix the upload success string**

`dashboard/src/app/upload/page.tsx:178`:

```tsx
                     status === "SUCCESS" ? "Parsed and staged — indexing runs separately." :
```

- [ ] **Step 3: Add the missing package markers**

```bash
touch tests/__init__.py tests/eval/__init__.py
```

- [ ] **Step 4: Remove the zero-test files**

```bash
git rm tests/test_router_fallback.py tests/test_tabular.py
```

- [ ] **Step 5: Point the audit_06 claim at the tests that earned it**

In `README.md`, find the line describing `audit_06_multi_tenant_isolation` as a deployment-blocking gate and append:

```markdown
(Tenant isolation is enforced and tested in `tests/test_api_rbac.py`, which asserts 403 on cross-tenant access through the real TestClient; `audit/audits/audit_06_multi_tenant_isolation.py` exercises mocks defined in its own file and is a smoke check, not the enforcement.)
```

- [ ] **Step 6: Verify**

```bash
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
cd dashboard && npm run build && cd ..
```

Expected: suite green; build succeeds with no TypeScript errors.

- [ ] **Step 7: Commit**

```bash
git add -A tests/ dashboard/src/app/upload/page.tsx README.md
git commit -m "fix(honesty): upload no longer claims indexing it did not do

_process_upload calls parse_main() only, but the UI printed 'Ingestion
complete! Promoted to live database.' on the one screen a pilot institution
would touch. Also adds the two missing __init__.py files that block every
documented eval command, removes two files that collect zero tests while
carrying the name of the most load-bearing routing behaviour, and points the
README's tenant-isolation gate claim at the 10 real RBAC tests that earned it."
```

---

## Task 9: The investor tunnel

**Files:**
- Create: `docs/INVESTOR_LINK.md`

**Interfaces:**
- Consumes: Task 7's auth defaults. **Do not start this task until Task 7 is committed and green.**

- [ ] **Step 1: Verify the API refuses unauthenticated calls**

```bash
REQUIRE_API_KEY=1 API_KEY=test-key-please-change .venv312/Scripts/python.exe start.py --host 127.0.0.1 --port 8000 &
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8000/documents?tenant_id=tenant_1
```

Expected: `401`.

- [ ] **Step 2: Write the runbook**

Create `docs/INVESTOR_LINK.md`:

````markdown
# Investor link

A deliberately-opened tunnel to the laptop. **Not** a hosted deployment: the
model, the index and the student records stay on this machine, and the link is
live only while the laptop is on and the tunnel is running.

## Before opening it, every time

A tunnel exposes **every** route, not just `/m`. `/upload`, `/review` and
`/documents` carry no auth of their own. Two gates are mandatory:

1. `REQUIRE_API_KEY=1` with a real `API_KEY` (Task 7 makes this the default).
2. A password at the tunnel edge, below.

Tunnel URLs are scanned by bots within minutes of being created. Obscurity is
not access control. The corpus behind this link contains 369 real students'
names, roll numbers and marks.

## Open

```powershell
$env:REQUIRE_API_KEY = "1"
$env:API_KEY = "<a long random string>"
powershell -ExecutionPolicy Bypass -File scripts\demo_up.ps1

ngrok http 3000 --basic-auth "investor:<a second long random string>"
```

Send the URL and the two credentials separately from the URL.

## Close

Ctrl-C the ngrok window, then:

```powershell
powershell -File scripts\demo_down.ps1
```

Close it after every call. Do not leave it running overnight.

## Verify it is actually gated

From a machine that is not this laptop:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<the-url>/documents?tenant_id=tenant_1
```

Expected: `401`. Anything else means the tunnel is open — close it immediately.
````

- [ ] **Step 3: Test the gate from outside**

Open the tunnel, then from a phone on mobile data (not the same wifi) hit the `/documents` URL.

Expected: a browser auth prompt, and `401` without credentials.

- [ ] **Step 4: Commit**

```bash
git add docs/INVESTOR_LINK.md
git commit -m "docs: investor tunnel runbook with mandatory edge auth

The tunnel is the one artifact in Phase 4 that puts real student records on a
reachable address. Both gates -- the API key and a password at the tunnel edge
-- are written as preconditions, with an external verification step, because a
tunnel exposes every route and tunnel URLs are scanned within minutes."
```

---

## Task 10: The parser experiment

**Files:**
- Create: `docs/parser_experiment.md`

**Interfaces:** none. This task writes no code. Its output decides the outreach target list.

- [ ] **Step 1: Confirm the skip condition**

```bash
grep -n "Total Marks(" ingestion/parse_tabular.py
```

Expected: the literal string used to decide whether a PDF is parseable.

- [ ] **Step 2: Obtain a second college's result PDF**

Download a publicly available semester result PDF from any other DBATU-affiliated college. Save to `C:\Users\ACER\.claude\jobs\35f7bdb4\tmp\external_result.pdf`.

- [ ] **Step 3: Run the parser against it**

```bash
.venv312/Scripts/python.exe ingestion/parse_tabular.py --pdf-dir "C:\Users\ACER\.claude\jobs\35f7bdb4\tmp" --tenant tenant_probe
```

(If Task 11 is not yet done, run the module directly with the paths edited in a scratch copy — do not edit the tracked file for this experiment.)

- [ ] **Step 4: Record the outcome**

Create `docs/parser_experiment.md` with: the college named, whether the PDF parsed, the row count produced or the reason it was skipped, and the one-line consequence — *"the target list is every DBATU-affiliated college"* or *"the target list is document-only institutions."*

- [ ] **Step 5: Commit**

```bash
git add docs/parser_experiment.md
git commit -m "docs: record whether the tabular parser generalises to a second college

parse_tabular.py silently skips any PDF lacking the literal string 'Total
Marks(' in its first three pages, and nobody had ever run it against another
college's result sheet. This is the two-hour experiment that decides whether
the funnel is DBATU-affiliated colleges or document-only institutions."
```

---

## Task 11: `parse_tabular.py` takes arguments

**Files:**
- Modify: `ingestion/parse_tabular.py:468-473`
- Create: `tests/test_parse_tabular_cli.py`

**Interfaces:**
- Produces: `python ingestion/parse_tabular.py --pdf-dir <dir> --tenant <id>`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_parse_tabular_cli.py`:

```python
"""The parser must take a directory and a tenant, not three hardcoded paths.

Hermetic: parses the argument parser only, never touches a PDF.
"""
import pytest

from ingestion.parse_tabular import build_arg_parser


def test_requires_pdf_dir_and_tenant():
    p = build_arg_parser()
    args = p.parse_args(["--pdf-dir", "/tmp/pdfs", "--tenant", "tenant_smoke"])
    assert args.pdf_dir == "/tmp/pdfs"
    assert args.tenant == "tenant_smoke"


def test_missing_tenant_is_an_error():
    p = build_arg_parser()
    with pytest.raises(SystemExit):
        p.parse_args(["--pdf-dir", "/tmp/pdfs"])
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_parse_tabular_cli.py -v
```

Expected: `ImportError: cannot import name 'build_arg_parser'`.

- [ ] **Step 3: Add the argument parser**

In `ingestion/parse_tabular.py`, replace the hardcoded path block at lines 468-473 with:

```python
def build_arg_parser() -> argparse.ArgumentParser:
    """CLI for the tabular parser.

    Deliberately a SEPARATE command from the 8-stage pipeline rather than a
    stage inside it: this parser is DBATU-result-sheet shaped (it skips any PDF
    without the literal string "Total Marks("), and wiring a format-specific
    step into the general pipeline would make every tenant depend on it.
    """
    p = argparse.ArgumentParser(description="Parse result-sheet PDFs into a tenant's tabular.duckdb")
    p.add_argument("--pdf-dir", required=True, help="Directory of result-sheet PDFs")
    p.add_argument("--tenant", required=True, help="Target tenant id")
    return p
```

and make `__main__` use it.

- [ ] **Step 4: Run the tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_parse_tabular_cli.py -v
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
```

Expected: `2 passed`, then the full suite green.

- [ ] **Step 5: Commit**

```bash
git add ingestion/parse_tabular.py tests/test_parse_tabular_cli.py
git commit -m "feat(ingestion): parse_tabular takes --pdf-dir and --tenant

Three hardcoded DBATU paths meant a second corpus needed a code edit. Kept as a
separate command rather than wired into the 8-stage pipeline: the parser is
result-sheet-format specific and every tenant should not depend on it."
```

---

## Task 12: Ingestion works end to end on a stranger's folder

**Files:**
- Modify: `pipeline.py:35-36`
- Modify: `ingestion/extract_entities.py:102-107,114-141`
- Delete: `ingestion/pipeline.py`
- Create: `docs/ONBOARD_RUNBOOK.md`
- Create: `tests/test_manifest_schema.py`

**Interfaces:**
- Consumes: Task 11's CLI.
- Produces: a documented single command that ingests a directory into a new tenant.

**Constraint:** all work runs against a clone. Cut over only after `pytest -m demo` passes against the clone.

- [ ] **Step 1: Clone the tenant**

```powershell
robocopy "R:\Startup research\Start up V2\data\tenants\tenant_1" "R:\Startup research\Start up V2\data\tenants\tenant_1_migrate" /MIR /R:2 /W:5
```

- [ ] **Step 2: Write the failing schema test**

Create `tests/test_manifest_schema.py`:

```python
"""pipeline.py and ingestion/parse.py wrote different manifest schemas.

pipeline.py:35-36 expected (filepath, hash, last_indexed_at); parse.py:20-30
writes (doc_id, file_hash, parse_status) to the same manifest.db -- so
pipeline.py raised OperationalError on every tenant on disk. parse.py's schema
wins: all four on-disk manifests and GET /documents already use it.

Hermetic: builds a manifest in a tmp dir.
"""
import sqlite3

from pipeline import MANIFEST_COLUMNS


def test_pipeline_expects_the_schema_parse_actually_writes():
    assert MANIFEST_COLUMNS == ("doc_id", "file_hash", "parse_status")


def test_pipeline_reads_a_parse_written_manifest(tmp_path):
    db = tmp_path / "manifest.db"
    con = sqlite3.connect(db)
    con.execute(
        "CREATE TABLE manifest (doc_id TEXT PRIMARY KEY, file_hash TEXT, parse_status TEXT)"
    )
    con.execute("INSERT INTO manifest VALUES ('a.pdf', 'deadbeef', 'SUCCESS')")
    con.commit()
    con.close()

    con = sqlite3.connect(db)
    rows = con.execute(f"SELECT {','.join(MANIFEST_COLUMNS)} FROM manifest").fetchall()
    assert rows == [("a.pdf", "deadbeef", "SUCCESS")]
```

- [ ] **Step 3: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_manifest_schema.py -v
```

Expected: `ImportError: cannot import name 'MANIFEST_COLUMNS'`.

- [ ] **Step 4: Align the schema**

In `pipeline.py`, replace the column names at lines 35-36 with a module constant and use it in the query:

```python
# parse.py's schema wins. All four on-disk manifests and GET /documents already
# use these names; pipeline.py's (filepath, hash, last_indexed_at) matched
# nothing on disk and raised OperationalError on every tenant.
MANIFEST_COLUMNS = ("doc_id", "file_hash", "parse_status")
```

- [ ] **Step 5: Stop dropping documents named "student"**

`ingestion/extract_entities.py:102-107` — remove the bare `"student"` entry from `excluded_keywords`, leaving the more specific terms. A partner's `student_handbook.pdf` was silently dropped from the graph with no error.

- [ ] **Step 6: Add per-file checkpointing**

In `ingestion/extract_entities.py:114-141`, move the `json.dumps` write inside the per-file loop and skip files already present in the output. The loop is already file-outer, so this is a few lines. Add a comment recording why: a crash at chunk 5,700 previously lost a full night of exclusive GPU time.

- [ ] **Step 7: Delete the stub**

```bash
git rm ingestion/pipeline.py
```

- [ ] **Step 8: Ingest a stranger-shaped folder end to end**

Assemble five PDFs — including one named `students_handbook.pdf` — in a scratch directory, then run the documented command into a brand-new `tenant_smoke`.

Verify:

```bash
ls data/tenants/tenant_smoke/chunked data/tenants/tenant_smoke/embeddings data/tenants/tenant_smoke/graph
```

Expected: all three non-empty. Then ask one FACT and one LOCAL question against `tenant_smoke` through the API and confirm both answer correctly.

- [ ] **Step 9: Verify resume works**

Start the ingest, kill it mid entity-extraction, rerun it. Expected: it resumes rather than restarting. Record the wall-clock difference in the runbook.

- [ ] **Step 10: Write the runbook and cut over**

Create `docs/ONBOARD_RUNBOOK.md` with the exact commands. Make `README.md:271-273` match it or delete that section.

Run `pytest -m demo` against the clone. Only when it passes, swap `tenant_1_migrate` into place.

- [ ] **Step 11: Commit**

```bash
git add -A pipeline.py ingestion/ docs/ONBOARD_RUNBOOK.md tests/test_manifest_schema.py README.md
git commit -m "fix(ingestion): repair the two broken documented paths

pipeline.py expected (filepath, hash, last_indexed_at) while parse.py writes
(doc_id, file_hash, parse_status) to the same manifest.db, so pipeline.py
raised OperationalError on every tenant on disk -- the one claim a reviewer can
falsify in ninety seconds from a clean checkout. parse.py's schema wins. Entity
extraction now checkpoints per file instead of losing a night's GPU time to a
crash at chunk 5,700, the generic word 'student' no longer silently drops a
partner's student_handbook.pdf from the graph, and the all-commented-out
ingestion/pipeline.py stub is gone."
```

---

## Task 13: Query logging for the capture phase

**Files:**
- Create: `retrieval/query_log.py`
- Modify: `retrieval/router.py:277,339`
- Create: `tests/test_query_log.py`

**Interfaces:**
- Produces: `log_query(...) -> None` appending one JSON object per line.

- [ ] **Step 1: Write the failing test**

Create `tests/test_query_log.py`:

```python
"""Query logging for the real-user capture phase.

The pre-fallback route must be recorded. router.py overwrites qtype to 'FACT'
at two points before anything logs it, which is why route classification
measures 54.3% while answers measure 88.9% -- the metric is scoring successful
rescues as routing failures.

Hermetic: writes to tmp_path.
"""
import json

from retrieval.query_log import log_query


def test_records_both_routes(tmp_path):
    p = tmp_path / "queries.jsonl"
    log_query(p, query="how many failed?", tenant="t1",
              route_classified="TABULAR", route_served="FACT",
              sources=[], answer="16 students", latency_s=1.2)
    rec = json.loads(p.read_text().strip())
    assert rec["route_classified"] == "TABULAR"
    assert rec["route_served"] == "FACT"


def test_roll_numbers_are_scrubbed(tmp_path):
    p = tmp_path / "queries.jsonl"
    log_query(p, query="cgpa of 23063181242004", tenant="t1",
              route_classified="TABULAR", route_served="TABULAR",
              sources=[], answer="SGPA 7.2", latency_s=0.3)
    body = p.read_text()
    assert "23063181242004" not in body
    assert "[ROLL]" in body


def test_appends_rather_than_overwrites(tmp_path):
    p = tmp_path / "queries.jsonl"
    for i in range(3):
        log_query(p, query=f"q{i}", tenant="t1", route_classified="FACT",
                  route_served="FACT", sources=[], answer="a", latency_s=0.1)
    assert len(p.read_text().strip().splitlines()) == 3
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_query_log.py -v
```

Expected: `ModuleNotFoundError: No module named 'retrieval.query_log'`.

- [ ] **Step 3: Implement**

Create `retrieval/query_log.py`. Reuse the roll-number pattern already in `generation/answer.py` (`_ROLL_RE`) rather than writing a second one, and append one `json.dumps` per line with the fields the test asserts.

- [ ] **Step 4: Record the pre-fallback route in the router**

At `retrieval/router.py:277` and `:339`, capture `qtype` into a local before it is reassigned to `"FACT"`, and pass both values through to the caller in `metadata` as `route_classified` and `route_served`.

- [ ] **Step 5: Run the tests**

```bash
.venv312/Scripts/python.exe -m pytest tests/test_query_log.py -v
.venv312/Scripts/python.exe -m pytest -q -m "not demo"
```

Expected: `3 passed`, then the full suite green.

- [ ] **Step 6: Commit**

```bash
git add retrieval/query_log.py retrieval/router.py tests/test_query_log.py
git commit -m "feat(logging): append-only query log with pre-fallback route

router.py overwrites qtype to FACT at two points before anything records it, so
every successful TABULAR->FACT rescue scored as a routing failure -- which is
most of the gap between 54.3% route accuracy and 88.9% answer accuracy. Both
routes are now recorded. Roll numbers are scrubbed before anything is written."
```

---

## Steps 3 and 6 of the spec: outreach and capture

These are not implementation tasks and have no code. They run on the calendar,
starting day 1, per the spec's step 0:

- **Outreach** (~1h/day from day 1). Target list decided by Task 10's outcome.
  Qualify on: an authorising sponsor, ≥5 named users, a dated two-week window.
  Hardware is not a criterion — the system runs on this laptop and the partner
  reaches it over LAN or tunnel.
- **Hard stop at 15 business days.** If no partner qualifies, execute the
  pre-committed fallback: recruit 15–20 individual students and faculty as
  users of tenant_1's existing corpus over the `/m` PWA.
- **Capture and grading** (spec step 6) begins once Task 13 is deployed and
  someone outside the team is using the system. Reserve 30% of queries sealed
  and unread; grade the visible 70% blind; fix the top three failure classes in
  frequency order, each with the real failing query added as a permanent test;
  score the sealed 30% once, at the end.

---

## Self-review notes

- **Spec coverage.** Spec step 1 → Task 1. Step 2 → Tasks 2–8. Step 3 (tunnel)
  → Task 9. Step 4 (parser experiment) → Task 10. Step 5 (ingestion) → Tasks
  11–12. Step 6 (capture) → Task 13 plus the calendar section above.
- **Ordering dependency.** Task 9 must not start before Task 7 is green; the
  tunnel is safe only behind the auth defaults.
- **Known risk in Task 7.** Flipping `REQUIRE_API_KEY` to 1 will break existing
  tests that assumed an open API. Those must be fixed by setting the env var
  explicitly in their fixtures, never by reverting the default.
- **Task 12 is the riskiest.** It is the only task that touches the corpus, and
  it is gated behind a clone plus the Task 1 backup.
