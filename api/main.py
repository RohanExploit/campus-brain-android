from fastapi import FastAPI, HTTPException, Depends, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import sys
from pathlib import Path
sys.path.append(str(Path(__file__).resolve().parent.parent))

import asyncio
import contextlib
import logging
import httpx
import sqlite3
import subprocess
from datetime import datetime

from retrieval.router import QueryRouter
from generation.answer import generate_answer, aclose_http_client
from api.audit_router import router as audit_router
import config
from config import DATA_ROOT, validate_tenant_id, validate_upload_id, safe_filename, MAX_FILE_SIZE
from utils.logging_config import setup_logging
from auth import api_keys as api_keys_module
from auth.api_keys import Principal, resolve_principal


async def authenticate(request: Request, x_api_key: str | None = Header(default=None)):
    """Global auth gate. When REQUIRE_API_KEY is set, every request except
    /health must carry an X-API-Key header resolving to a Principal (an admin
    key, i.e. env API_KEY, or a scoped tenant key from auth/api_keys.json).
    OFF by default so localhost dev stays frictionless; turn ON before binding
    0.0.0.0.

    Applied as an app-level dependency so it also covers the audit router.
    CORS preflight (OPTIONS) is answered by CORSMiddleware before reaching here.

    Resolved principal is stashed on request.state.principal for downstream
    per-tenant authorization checks (_authorize_tenant, _principal).
    """
    if not config.require_api_key_enabled():
        request.state.principal = Principal("admin")  # dev mode = full access
        return
    if request.url.path == "/health":
        return  # unauthenticated liveness probe for load balancers

    # Accept the key from the X-API-Key header OR an ?api_key= query param. The
    # query-param path exists for EventSource/SSE (/audit/stream), which cannot
    # set custom request headers.
    presented = x_api_key or request.query_params.get("api_key")

    if not config.get_api_key() and not api_keys_module._load_keys():
        # Gate demanded but no USABLE key exists anywhere — fail closed, never
        # open. Check _load_keys() (not just file existence): a present but
        # empty/corrupt/all-invalid api_keys.json loads to [], which would
        # otherwise 401 every request silently with no misconfig signal.
        raise HTTPException(
            status_code=500,
            detail="REQUIRE_API_KEY is set but no keys configured (server misconfig)",
        )

    principal = resolve_principal(presented)
    if principal is None:
        raise HTTPException(status_code=401, detail="Missing or invalid X-API-Key")
    request.state.principal = principal

setup_logging()
OLLAMA_MODEL = config.OLLAMA_MODEL

routers = {}

def _require_tenant(tenant_id: str) -> str:
    """Validate a client-supplied tenant_id or raise HTTP 400 (blocks path traversal)."""
    try:
        return validate_tenant_id(tenant_id)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


def _require_upload_id(upload_id: str) -> str:
    """Validate a client-supplied upload_id or raise HTTP 400 (blocks path traversal).

    upload_id is always server-generated via uuid.uuid4() at /upload time, so
    a value that doesn't match that shape (e.g. "..") is rejected before it
    can be used to build staging_dir.
    """
    try:
        return validate_upload_id(upload_id)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

def get_router(tenant_id: str) -> QueryRouter:
    tenant_id = validate_tenant_id(tenant_id)
    if tenant_id not in routers:
        logging.info(f"Initializing router for {tenant_id}")
        routers[tenant_id] = QueryRouter(tenant_id)
    return routers[tenant_id]

@contextlib.asynccontextmanager
async def lifespan(app: FastAPI):
    logging.info("FastAPI starting up: warming up models...")

    # 1. Eagerly load/warmup SentenceTransformer and index for default tenant
    try:
        default_router = get_router("tenant_1")
        if default_router.vs.model is None:
            logging.info("Loading SentenceTransformer model...")
            default_router.vs.load_index()
    except Exception as e:
        logging.error(f"Failed to eager load VectorSearch index: {e}")

    # 2. Warmup Ollama model to avoid first-query latency penalty
    logging.info(f"Sending warmup query to Ollama ({config.OLLAMA_MODEL})...")
    payload = {
        "model": config.OLLAMA_MODEL,
        "prompt": "Hello",
        "stream": False,
        "keep_alive": "10m",
        "options": {"num_predict": 2, "num_ctx": 2048}
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            await client.post(f"{config.OLLAMA_BASE_URL}/api/generate", json=payload)
            logging.info("Ollama model warmed up successfully.")
    except Exception as e:
        logging.warning(f"Ollama warmup failed (is the server running?): {e}")

    yield

    # Shutdown: close the shared Ollama httpx client so its pool is released
    # cleanly (avoids the "Event loop is closed" teardown noise on Windows).
    await aclose_http_client()

app = FastAPI(title="Campus Brain API", lifespan=lifespan,
              dependencies=[Depends(authenticate)])
app.include_router(audit_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://127.0.0.1:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _principal(request: Request) -> Principal:
    """Current caller's Principal. authenticate() (app-level dependency) always
    populates request.state.principal before any endpoint runs — a real admin
    in dev mode (gate off) or a resolved key otherwise; its 500/401 branches
    raise before reaching here. If state is somehow unset (e.g. an endpoint not
    covered by the dependency, or a middleware-ordering regression) we fail
    CLOSED with a 401 rather than defaulting to admin (which would silently
    grant full cross-tenant access)."""
    p = getattr(request.state, "principal", None)
    if p is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    return p


def _authorize_tenant(request: Request, tenant_id: str) -> None:
    """Raise 403 unless the caller is admin or is a tenant key scoped to tenant_id."""
    p = _principal(request)
    if not p.is_admin and p.tenant_id != tenant_id:
        raise HTTPException(status_code=403, detail="API key is not authorized for this tenant")


def _get_ollama_status() -> dict:
    """Check if Ollama is reachable and return model + VRAM info."""
    try:
        import httpx as _httpx
        r = _httpx.get(f"{config.OLLAMA_BASE_URL}/", timeout=2.0)
        ollama_ok = r.status_code < 400
    except Exception:
        ollama_ok = False

    vram_used, vram_total = None, None
    try:
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.used,memory.total",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0:
            parts = result.stdout.strip().split(", ")
            if len(parts) == 2:
                vram_used = round(int(parts[0]) / 1024, 1)   # GB
                vram_total = round(int(parts[1]) / 1024, 1)
    except Exception:
        pass

    return {
        "reachable": ollama_ok,
        "model": OLLAMA_MODEL,
        "vram_used_gb": vram_used,
        "vram_total_gb": vram_total,
    }


def _get_tenant_info() -> list:
    """Scan DATA_ROOT for tenants: doc count + last manifest ingestion."""
    from auth.allowlist import AllowlistManager
    mgr = AllowlistManager()
    allowlist = mgr.allowlist

    tenants = []
    if DATA_ROOT.exists():
        for tenant_dir in sorted(DATA_ROOT.iterdir()):
            if not tenant_dir.is_dir() or tenant_dir.name.startswith("{"):
                continue
            tid = tenant_dir.name
            raw_dir = tenant_dir / "raw"
            # Count indexed documents (manifest rows), not raw files: a raw file
            # that was never ingested has no manifest row and must not inflate the
            # count shown in the status strip / Document Library (they'd disagree).
            doc_count = len(list(raw_dir.iterdir())) if raw_dir.exists() else 0

            last_indexed = None
            manifest_db = tenant_dir / "manifest.db"
            if manifest_db.exists():
                try:
                    conn = sqlite3.connect(manifest_db)
                    row = conn.execute(
                        "SELECT COUNT(*), MAX(last_indexed_at) FROM manifest"
                    ).fetchone()
                    conn.close()
                    if row:
                        doc_count = row[0]
                        if row[1]:
                            last_indexed = row[1]
                except Exception:
                    pass

            tenants.append({
                "tenant_id": tid,
                "registered": tid in allowlist,
                "description": allowlist.get(tid, {}).get("description", ""),
                "doc_count": doc_count,
                "last_indexed_at": last_indexed,
                "has_pipeline_output": (tenant_dir / "embeddings").exists(),
            })
    return tenants


@app.get("/admin/status")
async def admin_status(request: Request):
    """Live system status for the admin dashboard status strip. Admin only."""
    if not _principal(request).is_admin:
        raise HTTPException(status_code=403, detail="admin only")
    ollama = await asyncio.to_thread(_get_ollama_status)
    tenants = await asyncio.to_thread(_get_tenant_info)
    registered = [t for t in tenants if t["registered"]]
    return {
        "ollama": ollama,
        "tenants": tenants,
        "registered_tenant_count": len(registered),
        "total_docs": sum(t["doc_count"] for t in registered),
        "timestamp": datetime.utcnow().isoformat() + "Z",
    }

class QueryRequest(BaseModel):
    query: str
    tenant_id: str
    # Who is asking. The bots historically posted only {query, tenant_id}, discarding the
    # sender at the bot boundary — so the API could not have made a per-user decision even
    # if it had wanted to, and every allowlisted user received registrar-grade answers.
    # Optional, and ignored entirely unless config.PII_ROLE_GATE is on.
    user_id: str | None = None
    channel: str | None = None   # "telegram" | "whatsapp" | None (dashboard/direct)

class QueryResponse(BaseModel):
    query_type: str
    answer: str
    context_used: str
    debug_sql: str | None = None   # The exact SQL executed (for Text-to-SQL queries)
    # Provenance rides inside metadata["sources"] as [{"source", "section"}, ...] rather
    # than in a new top-level field: metadata is already returned on every branch and
    # already typed as an open dict by the dashboard, so nothing downstream breaks.
    # Deliberately NOT injected into context_used or the prompt — 15 of 120 stress golds
    # and 16 of 46 tenant_1 golds pass on source-label text alone (e.g. "rag" from the
    # RAG-MicroSim filename), so putting labels in context would hand the model free
    # gold tokens to echo and inflate every score measured afterwards.
    metadata: dict = {}

@app.post("/query", response_model=QueryResponse)
async def query_documents(req: QueryRequest, request: Request):
    # An empty or whitespace-only query used to reach the router, which classified it
    # TABULAR (no keyword matched a FACT/LOCAL/GLOBAL pattern, and the aggregate branch is
    # the fallthrough) and answered it with an unfiltered roster — roughly seventy real
    # student names returned for pressing Enter on an empty box. Reject it here, before
    # any routing or retrieval happens.
    if not req.query or not req.query.strip():
        raise HTTPException(status_code=400, detail="Query must not be empty.")

    # Validate tenant_id first (clean 400); authorize; then load its data (500 on load failure).
    tenant_id = _require_tenant(req.tenant_id)
    _authorize_tenant(request, tenant_id)
    try:
        tenant_router = get_router(tenant_id)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to load tenant data: {e}")

    role = None
    if config.PII_ROLE_GATE and req.user_id:
        try:
            from auth.allowlist import AllowlistManager
            role = AllowlistManager().get_role(tenant_id, req.user_id)
        except Exception as e:   # a role lookup must never take the query path down
            logging.warning("role lookup failed (%s); treating as unprivileged", type(e).__name__)

    # Pass `role` only when there is one to pass. With the gate off this is the exact
    # single-argument call the router has always received — so "gate off" is byte-identical
    # at the call site too, not merely in effect.
    route_kwargs = {"role": role} if role else {}
    qtype, context, metadata = await tenant_router.route_query(req.query, **route_kwargs)
    context_text = str(context).strip()

    if not context_text:
        return QueryResponse(
            query_type=qtype,
            answer="I don't have enough information to answer that.",
            context_used="",
            metadata=metadata
        )

    # TABULAR context is always a finished, deterministic answer (SQL templates,
    # tabular_queries helpers, or generate_and_run_sql's markdown table) — never
    # raw material for LLM synthesis. Short-circuit unconditionally to protect
    # exact figures from LLM paraphrase/rounding (see P3.12).
    if qtype == "TABULAR":
        return QueryResponse(
            query_type=qtype,
            answer=context_text,
            context_used=context_text,
            metadata=metadata
        )

    answer = await generate_answer(req.query, context, qtype)

    return QueryResponse(
        query_type=qtype,
        answer=answer,
        context_used=context[:500] + "..." if len(context) > 500 else context,
        debug_sql=metadata.get("debug_sql"),
        metadata=metadata
    )

@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/tenants")
def tenants_overview(request: Request):
    """Return all tenant directories with doc count, student count, manifest status.
    Non-admin (tenant-scoped) keys only see their own tenant."""
    p = _principal(request)
    result = []
    if DATA_ROOT.exists():
        # A tenant-scoped key only ever returns its own row, so scan just that one
        # directory instead of opening every tenant's DBs and filtering afterward.
        if p.is_admin:
            scan_dirs = sorted(DATA_ROOT.iterdir())
        else:
            own = DATA_ROOT / p.tenant_id if p.tenant_id else None
            scan_dirs = [own] if own and own.is_dir() else []
        for tenant_dir in scan_dirs:
            if not tenant_dir.is_dir() or tenant_dir.name.startswith("{"):
                continue
            raw_dir = tenant_dir / "raw"
            # Indexed-doc count (manifest rows) — matches the Document Library and
            # the status strip. Falls back to raw file count only when no manifest.
            doc_count = len([f for f in raw_dir.iterdir() if f.is_file()]) if raw_dir.exists() else 0
            has_manifest = (tenant_dir / "manifest.db").exists()
            has_duckdb = (tenant_dir / "tabular.duckdb").exists()
            student_count = 0
            last_indexed = None
            if has_manifest:
                try:
                    conn = sqlite3.connect(tenant_dir / "manifest.db")
                    row = conn.execute("SELECT COUNT(*), MAX(last_indexed_at) FROM manifest").fetchone()
                    conn.close()
                    if row:
                        doc_count = row[0]
                        last_indexed = row[1]
                except Exception:
                    pass
            if has_duckdb:
                try:
                    import duckdb
                    con = duckdb.connect(str(tenant_dir / "tabular.duckdb"), read_only=True)
                    student_count = con.execute("SELECT COUNT(*) FROM students").fetchone()[0]
                    con.close()
                except Exception:
                    pass
            result.append({
                "id": tenant_dir.name,
                "doc_count": doc_count,
                "student_count": student_count,
                "has_manifest": has_manifest,
                "has_duckdb": has_duckdb,
                "last_indexed": last_indexed,
            })
    # Defense-in-depth: scan is already scoped above, but re-filter in case an
    # admin path ever widens it.
    if not p.is_admin:
        result = [t for t in result if t["id"] == p.tenant_id]
    return {"tenants": result}


@app.get("/review")
def review_queue(request: Request):
    """Return all records in the needs_review table across tenants.
    Non-admin (tenant-scoped) keys only see their own tenant's items."""
    p = _principal(request)
    items = []
    if DATA_ROOT.exists():
        # Scope the scan to the caller's own tenant DB when tenant-scoped, rather
        # than opening every tenant's tabular.duckdb and discarding the rest.
        if p.is_admin:
            scan_dirs = sorted(DATA_ROOT.iterdir())
        else:
            own = DATA_ROOT / p.tenant_id if p.tenant_id else None
            scan_dirs = [own] if own and own.is_dir() else []
        for tenant_dir in scan_dirs:
            if not tenant_dir.is_dir() or tenant_dir.name.startswith("{"):
                continue
            db_path = tenant_dir / "tabular.duckdb"
            if not db_path.exists():
                continue
            try:
                import duckdb
                con = duckdb.connect(str(db_path), read_only=True)
                rows = con.execute("SELECT * FROM needs_review LIMIT 200").fetchall()
                cols = [d[0] for d in con.description]
                con.close()
                for row in rows:
                    item = dict(zip(cols, row))
                    item["tenant_id"] = tenant_dir.name
                    items.append(item)
            except Exception:
                pass
    # Defense-in-depth: scan is already scoped above.
    if not p.is_admin:
        items = [i for i in items if i["tenant_id"] == p.tenant_id]
    return {"items": items, "total": len(items)}


@app.get("/documents")
def documents_list(request: Request, tenant_id: str = "tenant_1"):
    """Return manifest entries for a tenant — all documents with parse status."""
    tenant_id = _require_tenant(tenant_id)
    _authorize_tenant(request, tenant_id)
    tenant_dir = DATA_ROOT / tenant_id
    manifest_db = tenant_dir / "manifest.db"
    if not manifest_db.exists():
        return {"documents": [], "total": 0, "error": "manifest.db not found"}
    try:
        conn = _get_manifest_conn(tenant_dir)
        rows = conn.execute(
            "SELECT doc_id, file_hash, parse_status, last_indexed_at, "
            "error_message, page_count, file_size_bytes, flags "
            "FROM manifest ORDER BY last_indexed_at DESC"
        ).fetchall()
        conn.close()
        docs = []
        for r in rows:
            docs.append({
                "doc_id": r[0],
                "file_hash": r[1][:12] + "..." if r[1] else None,
                "parse_status": r[2],
                "last_indexed_at": r[3],
                "error_message": r[4],
                "page_count": r[5],
                "file_size_bytes": r[6],
                "flags": r[7],
            })
        return {"documents": docs, "total": len(docs)}
    except Exception as e:
        return {"documents": [], "total": 0, "error": str(e)}

import uuid
import shutil
import json
from fastapi import UploadFile, File, Form, BackgroundTasks
from ingestion.parse import main as parse_main, _get_manifest_conn, _manifest_update

def _check_upload_ownership(upload_id: str, tenant_id: str, filename: str) -> None:
    """Verify upload_id was actually issued for this tenant_id/filename.

    Reads the ownership sidecar written by /upload. If it's missing or
    doesn't match, the caller is trying to act on an upload_id that was
    never bound to that tenant/filename (cross-tenant IDOR) — reject.
    """
    owner_file = DATA_ROOT.parent / "staging" / upload_id / "_owner.json"
    if not owner_file.exists():
        raise HTTPException(
            status_code=403,
            detail="upload_id does not belong to the given tenant_id/filename",
        )
    try:
        owner = json.loads(owner_file.read_text())
    except (OSError, ValueError):
        raise HTTPException(
            status_code=403,
            detail="upload_id does not belong to the given tenant_id/filename",
        )
    if owner.get("tenant_id") != tenant_id or owner.get("filename") != filename:
        raise HTTPException(
            status_code=403,
            detail="upload_id does not belong to the given tenant_id/filename",
        )


@app.post("/upload")
async def upload_file(request: Request, tenant_id: str = Form(...), file: UploadFile = File(...)):
    tenant_id = _require_tenant(tenant_id)
    _authorize_tenant(request, tenant_id)
    filename = safe_filename(file.filename)
    upload_id = str(uuid.uuid4())
    staging_dir = DATA_ROOT.parent / "staging" / upload_id
    staging_dir.mkdir(parents=True, exist_ok=True)

    data = await file.read()
    if len(data) > MAX_FILE_SIZE:
        raise HTTPException(
            status_code=413,
            detail=f"File too large ({len(data)} bytes > MAX_FILE_SIZE {MAX_FILE_SIZE}).",
        )
    file_path = staging_dir / filename
    with open(file_path, "wb") as f:
        f.write(data)

    # Bind this upload_id to the tenant/filename that created it, so
    # /process and /status can reject cross-tenant reuse of the id.
    owner_file = staging_dir / "_owner.json"
    owner_file.write_text(json.dumps({"tenant_id": tenant_id, "filename": filename}))

    # Update live manifest to PENDING so frontend sees it immediately
    tenant_dir = DATA_ROOT / tenant_id
    tenant_dir.mkdir(parents=True, exist_ok=True)
    manifest_conn = _get_manifest_conn(tenant_dir)
    try:
        _manifest_update(manifest_conn, filename, "staging", "PENDING")
    finally:
        manifest_conn.close()

    return {"upload_id": upload_id, "filename": filename, "tenant_id": tenant_id}


def _process_upload(upload_id: str, tenant_id: str, filename: str):
    staging_dir = DATA_ROOT.parent / "staging" / upload_id
    raw_dir = staging_dir / "raw"
    parsed_dir = staging_dir / "parsed"
    raw_dir.mkdir(exist_ok=True)
    parsed_dir.mkdir(exist_ok=True)

    file_path = staging_dir / filename
    raw_file_path = raw_dir / filename
    if file_path.exists():
        shutil.move(str(file_path), str(raw_file_path))

    tenant_dir = DATA_ROOT / tenant_id
    manifest_conn = _get_manifest_conn(tenant_dir)

    try:
        # Run parse.py main() which will process everything in raw_dir
        parse_main(input_dir=str(raw_dir), output_dir=str(parsed_dir))

        md_file = parsed_dir / f"{raw_file_path.stem}.md"
        if md_file.exists():
            # Get actual hash and size from the temporary manifest created by parse.py
            staging_conn = _get_manifest_conn(staging_dir)
            try:
                staging_row = staging_conn.execute(
                    "SELECT file_hash, flags, file_size_bytes FROM manifest WHERE doc_id = ?", (filename,)
                ).fetchone()
            finally:
                staging_conn.close()

            # Move to live tenant
            live_raw_dir = tenant_dir / "raw"
            live_parsed_dir = tenant_dir / "parsed"
            live_raw_dir.mkdir(exist_ok=True)
            live_parsed_dir.mkdir(exist_ok=True)

            shutil.copy2(str(raw_file_path), str(live_raw_dir / filename))
            shutil.copy2(str(md_file), str(live_parsed_dir / f"{raw_file_path.stem}.md"))

            if staging_row:
                import json
                flags = json.loads(staging_row[1]) if staging_row[1] else []
                _manifest_update(manifest_conn, filename, staging_row[0], "SUCCESS",
                                 flags=flags, file_size_bytes=staging_row[2])
            else:
                _manifest_update(manifest_conn, filename, "unknown", "SUCCESS")
        else:
            _manifest_update(manifest_conn, filename, "staging", "FAILED", error_message="Parsing produced no output")
    except Exception as e:
        _manifest_update(manifest_conn, filename, "staging", "FAILED", error_message=str(e))
    finally:
        manifest_conn.close()


@app.post("/upload/{upload_id}/process")
def process_upload(upload_id: str, tenant_id: str, filename: str, background_tasks: BackgroundTasks, request: Request):
    upload_id = _require_upload_id(upload_id)
    tenant_id = _require_tenant(tenant_id)
    _authorize_tenant(request, tenant_id)
    filename = safe_filename(filename)
    _check_upload_ownership(upload_id, tenant_id, filename)
    background_tasks.add_task(_process_upload, upload_id, tenant_id, filename)
    return {"status": "processing_started"}


@app.get("/upload/{upload_id}/status")
def upload_status(upload_id: str, tenant_id: str, filename: str, request: Request):
    upload_id = _require_upload_id(upload_id)
    tenant_id = _require_tenant(tenant_id)
    _authorize_tenant(request, tenant_id)
    filename = safe_filename(filename)
    _check_upload_ownership(upload_id, tenant_id, filename)
    tenant_dir = DATA_ROOT / tenant_id
    try:
        manifest_conn = _get_manifest_conn(tenant_dir)
        row = manifest_conn.execute(
            "SELECT parse_status, error_message FROM manifest WHERE doc_id = ?", (filename,)
        ).fetchone()
        manifest_conn.close()

        if row:
            return {"status": row[0], "error": row[1]}
        return {"status": "UNKNOWN", "error": "Not found in manifest"}
    except Exception as e:
        return {"status": "ERROR", "error": str(e)}


