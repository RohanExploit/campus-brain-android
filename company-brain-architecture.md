# Company Brain — Architecture & Technical Plan

## Locked requirements (from your answers)
- Prototype: 50 mixed docs (docx, xlsx, pptx, pdf), local laptop (RTX 2050 4GB / 16GB RAM)
- Production: 500+ docs, upgraded machine, colleges / MIDC / mid-size companies as customers
- Query types: fact lookup, multi-hop relationship reasoning, decision assisting
- Access: Telegram bot + WhatsApp bot, initially local
- Ingestion: one-time batch now, weekly continuous in production
- This is a product, not a portfolio piece → multi-tenant isolation and security are real requirements from day one, not later

One design change from my earlier take: "decision assisting" needs synthesis across multiple documents (e.g. "should we renew this vendor"), which plain entity-graph + vector retrieval can't do well. This requires the community-detection + summarization layer that real GraphRAG uses. I've added it below. Also, since you're on mixed office formats now (not just PDF), Docling is now justified — it handles docx/xlsx/pptx/pdf through one interface instead of stitching four parsers.

---

## 1. Two-tier system

**Tier A — Prototype (now, local machine)**
Single tenant, single machine, all components in one Python process or docker-compose.

**Tier B — Production (post-funding/admission, upgraded machine)**
Multi-tenant. Each customer (college / MIDC unit / company) gets an isolated data namespace. Weekly incremental ingestion. Bots gated by an allowlist per tenant.

Build Tier A so Tier B is a config change, not a rewrite. The trick: make "tenant" a first-class folder/namespace from day one, even with one tenant.

```
company-brain/
├── data/
│   └── tenants/
│       └── {tenant_id}/
│           ├── raw/              # uploaded originals
│           ├── parsed/           # Docling output (json/md per doc)
│           ├── chunks.parquet    # chunk text + metadata
│           ├── vectors.faiss     # per-tenant FAISS index
│           ├── graph.pkl         # per-tenant networkx graph
│           ├── communities.json  # community summaries (v2)
│           └── manifest.db       # sqlite: doc hash, version, last_indexed
├── ingestion/
│   ├── parse.py                  # Docling wrapper
│   ├── chunk.py
│   ├── embed.py
│   ├── extract_entities.py       # LLM entity/relation extraction
│   ├── build_graph.py
│   ├── build_communities.py      # Leiden clustering + summaries
│   └── pipeline.py                # orchestrates the above, per tenant
├── retrieval/
│   ├── vector_search.py
│   ├── graph_traverse.py
│   ├── community_search.py       # global/decision-assist queries
│   └── router.py                  # decides which retrieval path(s) to use
├── generation/
│   └── answer.py                  # prompt assembly + citation formatting
├── bots/
│   ├── telegram_bot.py
│   └── whatsapp_bot.py
├── api/
│   └── main.py                    # FastAPI, bots call this, not the pipeline directly
├── auth/
│   └── allowlist.py               # tenant_id -> {telegram_ids, wa_numbers}
├── scheduler/
│   └── weekly_ingest.py           # APScheduler/cron job, prod only
└── requirements.txt
```

---

## 2. Indexing pipeline (detailed)

```
raw docs (docx/xlsx/pptx/pdf)
    │
    ▼
[1] Docling parse → per-doc markdown/json + table structure preserved
    │  (batch job, unloads after — do not run alongside Ollama)
    ▼
[2] Chunk (semantic, ~300-500 tokens, keep table rows intact, tag with doc_id/section/page)
    │
    ▼
[3] Embed chunks → bge-small-en-v1.5 → store in FAISS (per tenant)
    │
    ▼
[4] LLM entity/relation extraction (Qwen3-4B via Ollama, batch job, loaded only for this step)
    │  - schema-constrained JSON output, not free text
    │  - one gleaning pass max on this hardware (2+ passes will be too slow at 50→500 doc scale)
    │  - fallback: spaCy NER pass to catch entities the small model misses, merge before graph build
    ▼
[5] Build graph (networkx): nodes = entities, edges = relations, both tagged with source chunk_id for citation
    │
    ▼
[6] Community detection (networkx has Louvain built in — use that instead of Leiden, avoids adding igraph as a dependency) on the entity graph → cluster related entities → LLM writes a short summary per cluster
    │  This is what powers "decision assisting" queries — the model reads community summaries, not just single chunks
    ▼
[7] Persist: chunks.parquet, vectors.faiss, graph.pkl, communities.json, manifest.db (hash of each source file, for incremental updates later)
```

Why staged, not streaming: on a 4GB card you cannot have Docling's layout model and Qwen3-4B loaded at once without spilling into slow CPU fallback. Run each stage as a discrete batch job that loads its model, finishes, unloads. `pipeline.py` just calls these in sequence.

---

## 3. Query pipeline (routing by query type)

```
user query (via Telegram/WhatsApp)
    │
    ▼
[router.py] classify query type (cheap: keyword/heuristic first, LLM classifier fallback)
    │
    ├─ FACT LOOKUP → vector_search.py only
    │    top-k chunks by embedding similarity → LLM answers with citation
    │
    ├─ MULTI-HOP RELATIONSHIP → vector_search.py (find entry entities) + graph_traverse.py (1-2 hop expansion)
    │    → merged context → LLM answers, cites source chunks per hop
    │
    └─ DECISION ASSISTING → community_search.py + graph_traverse.py + vector_search.py combined
         → relevant community summaries (broad context) + specific supporting chunks (grounding)
         → LLM produces structured answer: recommendation + supporting evidence + citations
         → this path is slowest, budget 10-20s response time on your current hardware, set bot UX expectations accordingly (e.g. "thinking..." message on Telegram/WA before the real answer)
```

Router heuristic to start (skip LLM classification cost for v1):
- Contains "who/what/when/where" + single entity → fact lookup
- Contains two+ named entities or "related to/worked with/connected" → multi-hop
- Contains "should/recommend/risk/compare/decide" → decision assisting

---

## 4. Graph schema (draft — lock this before you extract, changing it mid-corpus means re-extraction)

**Node types:** `Person`, `Organization`, `Document`, `Policy`, `Project`, `Date`, `Amount`, `Location` — keep this to 6-8 types max, more types = noisier extraction from a 4B model.

**Edge types:** `WORKS_ON`, `REPORTS_TO`, `MENTIONED_IN`, `RELATED_TO`, `GOVERNED_BY`, `PART_OF` — generic `RELATED_TO` as catch-all, refine per-tenant type list once you see real extraction output on your 50 docs.

**Every node and edge carries:** `source_chunk_id`, `source_doc_id`, `confidence` (from extraction), `tenant_id`.

---

## 5. Bot integration

**Telegram:** `python-telegram-bot`, webhook mode once you have a public endpoint, polling for local dev. Straightforward, official API, no ban risk.

**WhatsApp — Open-WA (unofficial automation), committed path including production:**
Open-WA automates a real WhatsApp Web session. Meta's ToS prohibits this; a flagged number gets banned, not just the integration. Since this is going to paying customers, mitigate rather than ignore:
- Dedicated number per tenant, never their primary business line
- Rate-limit aggressively, randomized delays, no burst sends
- One Open-WA session/container per tenant — isolates ban blast radius, lets you rotate independently
- Build the "session dropped / needs QR re-scan" alert into ops from day one, this happens even without a ban
- Disclose the risk to customers in writing before onboarding — contract clause, not a surprise later

Both bots should be thin clients hitting your FastAPI `/query` endpoint — never call the retrieval pipeline directly from bot code. Keeps bot-specific formatting (Telegram markdown vs WhatsApp plain text) separate from retrieval logic.

---

## 6. Multi-tenancy & security (needed even at prototype stage if you're pitching this)

- One tenant = one folder under `data/tenants/{tenant_id}/`, own FAISS index, own graph, own manifest. No shared vector store with metadata filtering at this scale — full folder isolation is simpler and safer (zero cross-tenant leak risk by construction, not by filter logic that could have a bug).
- `auth/allowlist.py`: maps tenant_id to a list of authorized Telegram user IDs and WhatsApp numbers. Bot rejects any message from an unlisted ID before it touches the pipeline.
- Uploaded docs at rest: encrypt the `raw/` folder per tenant if you're storing anything confidential (contracts, HR docs) — this will come up in any procurement conversation with a mid-size company.
- Log queries per tenant for audit, but don't log document content in logs.

---

## 7. Incremental ingestion (production, weekly)

- `manifest.db` stores `(doc_id, file_hash, last_indexed_at)` per tenant.
- Weekly job (APScheduler or cron) diffs the tenant's `raw/` folder against the manifest: new hash → re-parse/re-chunk/re-embed/re-extract that doc only, delete-and-rebuild only the affected graph nodes/edges (tag by `source_doc_id` to know what to remove).
- Don't rebuild communities every week — that's expensive. Rebuild communities on a slower cadence (monthly, or triggered manually) unless the graph changed a lot.

---

## 8. Build order (prototype, in sequence)

1. Docling parsing on your 50 docs, verify output quality on your actual xlsx/pptx files before anything else (Docling's table/slide handling is the biggest unknown — check it early, it's the step most likely to surprise you)
2. Chunking + embedding + FAISS — get vector-only fact lookup working end to end first
3. Telegram bot wired to FAISS-only retrieval — this alone is a demoable v0
4. Entity/relation extraction + networkx graph — add multi-hop retrieval
5. Community detection + summaries — add decision-assisting
6. WhatsApp bot (unofficial, for demo)
7. Tenant folder structure + allowlist (do this before you show it to a second person/org, even in prototype)

Steps 1-3 alone give you something to put in front of a college admin or MIDC contact within days, before you've built the harder graph layer. Worth testing whether fact-lookup-only is already enough to get interest, since that's the cheapest slice to validate before you sink time into steps 4-5.

---

## 9. Open questions to settle before you start extraction (changing these later means re-running the pipeline)
- Final list of node/edge types for your first tenant's document set
- Chunk size (start 400 tokens, tune after seeing retrieval quality on a 10-question eval set)
- What counts as a "decision assisting" answer format — free text, or a structured recommendation + evidence template? Structured is easier to make trustworthy for a paying customer, worth deciding now.
