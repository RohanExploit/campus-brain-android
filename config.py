"""
Central config. Single source of truth for paths + validation.

YOU control everything here:
  - PROJECT_ROOT auto-detects where this repo lives (works on R:, e:, anywhere).
    Override anytime with env var PROJECT_ROOT.
  - Validation is toggleable. Turn it off, loosen the pattern, or add tenants
    without touching code elsewhere.

Nothing else in the codebase should hardcode an absolute path. Import from here.
"""
import os
import re
from pathlib import Path

from dotenv import load_dotenv

# --------------------------------------------------------------------------
# PATHS  (your knob: set env PROJECT_ROOT to force a location; else auto-detect)
# --------------------------------------------------------------------------
# config.py sits at the repo root, so its parent IS the project root.
PROJECT_ROOT = Path(os.environ.get("PROJECT_ROOT", Path(__file__).resolve().parent))

# Load .env before anything else in the app reads os.environ (e.g.
# NVIDIA_API_KEY for the Ollama fallback in generation/answer.py). Every
# module imports config first, so this is the one place that guarantees it.
load_dotenv(PROJECT_ROOT / ".env")

DATA_ROOT           = PROJECT_ROOT / "data" / "tenants"
STAGING_ROOT        = PROJECT_ROOT / "data" / "staging"
AUTH_FILE           = PROJECT_ROOT / "auth" / "allowlist.json"
ENCRYPTION_KEY_PATH = Path(os.environ.get("ENCRYPTION_KEY_PATH", PROJECT_ROOT / ".encryption_key"))


def tenant_dir(tenant_id: str) -> Path:
    """Validated path to a tenant's data dir. Use this instead of DATA_ROOT / tenant_id."""
    return DATA_ROOT / validate_tenant_id(tenant_id)


# --------------------------------------------------------------------------
# VALIDATION CONTROLS  (your knobs)
# --------------------------------------------------------------------------
# Master switch. Set env VALIDATE_TENANT_ID=0 to disable ALL tenant_id checks.
VALIDATE_TENANT_ID = os.environ.get("VALIDATE_TENANT_ID", "1") != "0"

# What a legal tenant_id looks like. Edit freely. Default: letters/digits/_/-.
TENANT_ID_PATTERN = re.compile(os.environ.get("TENANT_ID_PATTERN", r"^[A-Za-z0-9_-]{1,64}$"))

# Master switch for upload filename sanitizing.
VALIDATE_FILENAMES = os.environ.get("VALIDATE_FILENAMES", "1") != "0"

# Master switch for upload_id checks. Set env VALIDATE_UPLOAD_ID=0 to disable.
VALIDATE_UPLOAD_ID = os.environ.get("VALIDATE_UPLOAD_ID", "1") != "0"

# upload_id is always server-generated via uuid.uuid4() (see api/main.py
# upload_file). What a legal upload_id looks like. Edit freely.
UPLOAD_ID_PATTERN = re.compile(os.environ.get("UPLOAD_ID_PATTERN", r"^[0-9a-fA-F-]{36}$"))


def validate_tenant_id(tenant_id: str) -> str:
    """
    Returns tenant_id if allowed, else raises ValueError.
    Blocks path traversal (.. / \\ / absolute drive) that would let one tenant
    read another's data. Disable via VALIDATE_TENANT_ID=0 if you need to.
    """
    if not VALIDATE_TENANT_ID:
        return tenant_id
    if not isinstance(tenant_id, str) or not TENANT_ID_PATTERN.match(tenant_id):
        raise ValueError(f"Invalid tenant_id: {tenant_id!r}")
    return tenant_id


def validate_upload_id(upload_id: str) -> str:
    """
    Returns upload_id if it looks like a server-generated uuid4, else raises
    ValueError. upload_id is used to build a filesystem path
    (staging/<upload_id>/...), so a caller-supplied value like ".." must be
    rejected before it reaches any mkdir/move/copy call. Disable via
    VALIDATE_UPLOAD_ID=0 if you need to.
    """
    if not VALIDATE_UPLOAD_ID:
        return upload_id
    if not isinstance(upload_id, str) or not UPLOAD_ID_PATTERN.match(upload_id):
        raise ValueError(f"Invalid upload_id: {upload_id!r}")
    return upload_id


def safe_filename(name: str) -> str:
    """
    Strip any directory component from an uploaded filename so it cannot escape
    the staging dir (e.g. '..\\..\\windows\\x'). Returns just the base name.
    Disable via VALIDATE_FILENAMES=0.
    """
    if not VALIDATE_FILENAMES:
        return name
    base = os.path.basename(str(name).replace("\\", "/"))
    base = base.strip().lstrip(".") or "upload"
    return base


# --------------------------------------------------------------------------
# RUNTIME KNOBS  (all env-overridable — your control)
# --------------------------------------------------------------------------
DEFAULT_TENANT_ID = os.environ.get("DEFAULT_TENANT_ID", "tenant_1")

# Ollama model id + base URL. Previously hardcoded and duplicated across
# api/main.py, generation/answer.py, retrieval/router.py,
# retrieval/tabular_queries.py, ingestion/extract_entities.py and
# ingestion/summarize_communities.py — centralized here so it's set once.
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "qwen3:4b-instruct-2507-q4_K_M")
OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434")

# Seconds before an outbound LLM/API call is abandoned.
API_TIMEOUT = float(os.environ.get("API_TIMEOUT", "60"))

# Max upload size in bytes (default 50 MB). Uploads larger are rejected 413.
MAX_FILE_SIZE = int(os.environ.get("MAX_FILE_SIZE", str(50 * 1024 * 1024)))

# Seed for Louvain community detection so pipeline runs are reproducible.
LOUVAIN_SEED = int(os.environ.get("LOUVAIN_SEED", "42"))

# --------------------------------------------------------------------------
# API AUTH  (optional gate — OFF by default so local dev on 127.0.0.1 is
# frictionless. Turn ON before ever binding 0.0.0.0. See start.py docstring.)
# --------------------------------------------------------------------------
def _truthy(v) -> bool:
    return str(v).strip().lower() in ("1", "true", "yes", "on")


def require_api_key_enabled() -> bool:
    """Whether the X-API-Key gate is active. Read live from env so tests (and a
    running server told to reload) reflect the current value without reimport."""
    return _truthy(os.environ.get("REQUIRE_API_KEY", "0"))


def get_api_key() -> str:
    """The expected X-API-Key value. Empty when unset (misconfig if the gate is
    enabled — the dependency fails closed in that case)."""
    return os.environ.get("API_KEY", "").strip()


# --------------------------------------------------------------------------
# LLM egress / PII controls  (generation/answer.py)
#
# generation/answer.py answers non-TABULAR queries with a local Ollama model
# and, on failure, falls back to NVIDIA's cloud API — sending the prompt
# (which embeds document context that may contain student PII) off-machine.
# These two knobs let a PII-sensitive deployment forbid that egress entirely,
# or scrub obvious PII (roll numbers) out of the prompt before it leaves the
# machine. Both default to preserving today's behavior unchanged.
# --------------------------------------------------------------------------
# Default OFF (security, Phase -1.2). Forbid ALL external cloud LLM egress unless
# explicitly enabled. The NVIDIA fallback in generation/answer.py would otherwise
# ship document context — which can contain student PII (names/emails) — off the
# machine on ANY local-Ollama hiccup, silently. It also invalidates any local-4B
# measurement by swapping in a 70B cloud model. Set ALLOW_EXTERNAL_LLM=1 to opt
# back into the fallback for a deployment that has accepted that trade-off.
ALLOW_EXTERNAL_LLM = _truthy(os.environ.get("ALLOW_EXTERNAL_LLM", "0"))
# When ON, mask roll-number-like digit runs in the prompt before it leaves
# the machine.
LLM_PII_REDACTION = _truthy(os.environ.get("LLM_PII_REDACTION", "0"))


# --- Router robustness (retrieval/router.py) ---
# When a query is classified TABULAR but the tabular path can't answer it — the
# tenant has no tabular.duckdb (document-only tenant), or the intent cascade yields
# empty context, or a lookup raises — fall back to the FACT vector path instead of
# erroring/abstaining. Phase-0 measured 31/66 FACT stresskit questions misrouted to
# TABULAR and hard-failing; FACT-conditional accuracy is 96.6%, so recovering them
# is the highest-yield lever. Never overrides a *valid* tabular answer (incl. a
# legitimate "no rows") — only empty/raised outcomes trigger it. Default ON.
TABULAR_FACT_FALLBACK = _truthy(os.environ.get("TABULAR_FACT_FALLBACK", "1"))


# --- Text-to-SQL guardrails (retrieval/tabular_queries.py) ---
# Row cap injected when the generated SQL has no LIMIT.
SQL_ROW_LIMIT = int(os.environ.get("SQL_ROW_LIMIT", "200"))
# Tables the generated SQL is allowed to read. Empty set = allow any (off).
SQL_ALLOWED_TABLES = set(
    t for t in os.environ.get(
        "SQL_ALLOWED_TABLES", "students,student_subjects,needs_review"
    ).split(",") if t.strip()
)


# --- Vector-index PII guard (ingestion/embed.py) — Phase -1.3 ---
# Bulk third-party PII (e.g. a payment/enrolment CSV rendered to markdown) must
# never enter the FACT vector index: it is retrieval poison AND, combined with any
# cloud egress, an exfiltration path. The guard treats any single source whose
# email-bearing chunk count EXCEEDS this threshold as bulk PII and excludes all of
# that source's chunks from embedding. A low count — e.g. a research paper's own
# author-contact line — is preserved (so a legitimate "author email" FACT answer
# survives). Set 0 to disable the guard entirely.
VECTOR_PII_EMAIL_BULK_THRESHOLD = int(
    os.environ.get("VECTOR_PII_EMAIL_BULK_THRESHOLD", "5")
)


# --- Student-identity role gate (auth/allowlist.py + retrieval/sql_templates.py) ---
# TABULAR answers list real students by full name and roll number, e.g.
#   "- JAGTAP ANANT TANAJI (Roll: 23063181242004): 5 subjects".
# Today every allowlisted chat user receives that: the bots discard the sender's identity
# before calling /query, and auth/allowlist.py exposes only allowed/not-allowed booleans,
# so the API cannot distinguish a registrar from a student even in principle.
#
# This flag turns on the MECHANISM (identity plumbing + role lookup + redaction of names
# and roll numbers for non-privileged roles). It ships OFF, and OFF is byte-for-byte
# today's behaviour — asserted by tests/test_pii_role_gate.py.
#
# Turning it ON is a POLICY decision (who may see student identities) and is deliberately
# left to a human: every non-admin role list in auth/allowlist.json is currently empty, so
# enabling the gate would deny everyone except the admin. Populate the "registrar"/"faculty"
# lists first, then set PII_ROLE_GATE=1.
PII_ROLE_GATE = _truthy(os.environ.get("PII_ROLE_GATE", "0"))

# Roles permitted to see student names/roll numbers when the gate is ON.
PII_PRIVILEGED_ROLES = set(
    r.strip() for r in os.environ.get("PII_PRIVILEGED_ROLES", "admin,registrar").split(",")
    if r.strip()
)


# --- Ollama context window (every LLM call site) ---
# Was hardcoded to 2048 in six places. Measured on the RTX 2050 (4 GB):
#   2048  -> 100% resident on GPU, 33.9 tok/s decode   <- the only fully-resident setting
#   4096  ->  82% resident, 18.3 tok/s  (-46%)
#   8192  ->  71% resident,  7.2 tok/s  (-79%)  breaches API_TIMEOUT on long answers
#  16384  ->  53% resident,  5.05 tok/s
# The KV cache spills to system RAM rather than OOM-ing, so the cost of a bigger window is
# throughput, not stability. 4096 keeps the worst observed answer inside API_TIMEOUT=60;
# 8192+ does not, and an answer that times out is a product regression, not a slow success.
#
# It is deliberately ONE knob for ALL call sites: Ollama reloads the model whenever num_ctx
# changes between requests (measured 2.9-3.0 s clean, up to 170 s under RAM pressure), so a
# mixed configuration would pay two reloads per question.
OLLAMA_NUM_CTX = int(os.environ.get("OLLAMA_NUM_CTX", "2048"))

# Character budget for the packed FACT/vector context. Must stay well inside OLLAMA_NUM_CTX
# (~4 chars/token) with room for the prompt template and the answer. At num_ctx 2048 the
# safe budget is ~5000 chars; raising one without the other buys nothing, because Ollama
# silently truncates the overflow — and it keeps the prompt TAIL, so an over-budget context
# loses its best-ranked chunks first.
CONTEXT_BUDGET_CHARS = int(os.environ.get("CONTEXT_BUDGET_CHARS", "5000"))

# Candidates pulled from FAISS before budget packing. More candidates only help if the
# budget can hold them: on tenant_1 (mean 861 chars/chunk) a 5000-char budget fits ~6.
FACT_TOP_K = int(os.environ.get("FACT_TOP_K", "10"))


# --- LOCAL route context source (retrieval/router.py) — Phase-B T1.2 ---
# The LOCAL route answers relationship questions from 2-hop graph edges rendered as
# "A -> RELATION -> B" lines. Measured on the stress corpus, the gold answer string
# appears in that edge context for 2 of 20 LOCAL questions and in plain retrieved chunk
# text for 18-19 of 20 — and on 5 questions entity linking matches a junk node, which
# makes `edges` non-empty and so suppresses the existing empty-edges fallback.
#
# Default ON (graph), i.e. unchanged behaviour: that measurement is substring-of-answer
# on name-shaped golds, and an edge projection deliberately discards the sentence, so it
# understates the graph. Set LOCAL_GRAPH_CONTEXT=0 to serve LOCAL from vector chunks.
LOCAL_GRAPH_CONTEXT = _truthy(os.environ.get("LOCAL_GRAPH_CONTEXT", "1"))
LOCAL_VECTOR_K = int(os.environ.get("LOCAL_VECTOR_K", "15"))

# graph | vector | hybrid. Measured on the bench with routing forced to 100%:
#   graph   LOCAL 30-31/54   vector  LOCAL 42/54
# but the vector arm loses three questions REPRODUCIBLY (BL011, BL040, BL051 — identical in
# both pairs, while the graph arm's own run-to-run noise falls on different questions
# entirely). All three are two-hop questions whose SECOND hop lives in a document the
# question's own wording does not retrieve: "how many faculty in the department that runs
# lab X" needs the lab->department line and then the department's faculty count, and a
# single query embedding pulls the first and misses the second. The graph answers them
# because entity linking walks the ego network across both documents.
#
# So the two context types are complementary rather than competing, and `hybrid` supplies
# both: the linked entity's edges AND retrieved chunk text. Measured, forced routing:
#   graph  31/54 and 33/54      vector 42/54      hybrid 43/54 and 44/54
# Hybrid passed the pre-registered rule on both pairs (b=12 c=0 net 12 > 5; b=12 c=1
# net 11 > 7), fixed all three questions the vector arm lost reproducibly, and regressed
# FACT and GLOBAL by nothing (b=0 c=0 on each). Latency cost is real but small: LOCAL
# median 1.21s -> 2.11s, max 6.95s -> 8.72s, against API_TIMEOUT=60.
#
# Default `hybrid`. Set graph or vector to get either half alone.
LOCAL_CONTEXT_MODE = os.environ.get("LOCAL_CONTEXT_MODE", "hybrid").strip().lower()


# --- GLOBAL route context source (retrieval/router.py) — Phase-B ---
# The GLOBAL route builds context from LLM-written community summaries. Those summaries are
# generated from bare entity NAMES (ingestion/summarize_communities.py passes
# "Entities: {names}" and nothing else), so they contain no figures, dates or source names
# and are frequently degenerate — one bench summary reads "The entity '62' appears to be a
# single numerical value without contextual information".
#
# Measured on the 208-question bench with routing held at 100% (run_eval.py --force-route),
# so that route quality is separated from routing accuracy:
#   community summaries  GLOBAL 20/57 (35.1%)  and on a second pair 21/57 (36.8%)
#   chunk fan-out        GLOBAL 47/57 (82.5%)  on both pairs
# Pre-registered endpoint on the confirmatory pair: b=27, c=1, net 26 against a required
# 11 (artifact-floor movement 4 + table threshold 7) -> ACCEPT. FACT b=0/c=0 and LOCAL
# b=1/c=1, i.e. no collateral.
#
# Default ON. The community-summary path is not merely weaker; it is worse than not having
# a GLOBAL route at all, because those summaries are generated from bare entity NAMES
# (ingestion/summarize_communities.py) and so contain no figures, dates or source names.
# Set 0 to restore the summary path.
GLOBAL_CHUNK_FANOUT = _truthy(os.environ.get("GLOBAL_CHUNK_FANOUT", "1"))

# Candidates pulled for a GLOBAL fan-out. Higher than FACT_TOP_K because these questions
# want breadth (a ranking across a whole table, a theme across documents) rather than the
# single best passage — but the char budget still bounds what actually reaches the prompt.
GLOBAL_FANOUT_K = int(os.environ.get("GLOBAL_FANOUT_K", "30"))
