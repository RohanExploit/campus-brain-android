"use client";

import { useState, useRef } from "react";
import {
  postQuery,
  classifyAnswer,
  parseStudentRecord,
  type QueryResponse,
  type QueryType,
  type AnswerKind,
  type SourceRef,
} from "@/lib/api";
import { SearchIcon, SendIcon, AlertIcon } from "@/components/icons";

// ─── Types ─────────────────────────────────────────────────────

interface HistoryEntry {
  id: number;
  query: string;
  tenant: string;
  type: QueryType;
  hasFallback: boolean;
  ts: Date;
  response: QueryResponse;
}

// ─── Badge for query route type ─────────────────────────────────

function TypeBadge({ type }: { type: QueryType }) {
  const map: Record<QueryType, string> = {
    FACT: "badge-fact",
    LOCAL: "badge-local",
    GLOBAL: "badge-global",
    TABULAR: "badge-tabular",
    "LLM ANALYSED": "badge-info",
  };
  return <span className={`badge ${map[type]}`}>{type}</span>;
}

// ─── Route legend + example queries (empty-state onboarding) ────

const ROUTES: { type: QueryType; blurb: string }[] = [
  { type: "FACT", blurb: "Definitions & specific details from documents" },
  { type: "LOCAL", blurb: "Relationships & multi-hop entity connections" },
  { type: "GLOBAL", blurb: "Themes & summaries across the whole corpus" },
  { type: "TABULAR", blurb: "Aggregates & student records via live SQL" },
];

const EXAMPLES = [
  "How many students failed at least 2 subjects?",
  "search for gaikwad rohan vijay",
  "What is RAG-MicroSim?",
  "Which trust runs DACOE Karad?",
  "average SGPA",
];

// ─── Fallback warning banner ────────────────────────────────────

function FallbackBanner({ reason }: { reason: string }) {
  return (
    <div className="fallback-banner">
      <AlertIcon size={16} />
      <span>
        Router fell back to FACT —{" "}
        <span className="fallback-code">{reason}</span>. Classification may be wrong.
      </span>
    </div>
  );
}

// ─── Student record renderer ────────────────────────────────────

function StudentCard({ raw }: { raw: string }) {
  const rec = parseStudentRecord(raw);
  const resultClass = rec.result === "PASS" ? "badge-pass" : rec.result === "FAIL" ? "badge-fail" : "badge-warn";
  return (
    <div className="student-card">
      <div className="student-card-header">
        <div style={{ marginRight: "auto" }}>
          <div className="student-name">{rec.name || "Student"}</div>
          <div className="student-roll font-data">Roll: {rec.rollNo}</div>
        </div>
        <span className={`badge ${resultClass}`}>{rec.result}</span>
        {rec.sgpa !== "N/A" && (
          <span className="badge badge-info">SGPA {rec.sgpa}</span>
        )}
        {rec.isSupply && <span className="badge badge-warn">Supplementary</span>}
        {rec.seatCancelled && <span className="badge badge-fail">Seat Cancelled</span>}
      </div>

      {rec.subjects.length > 0 && (
        <div className="card-body" style={{ padding: 0 }}>
          <table className="grade-table">
            <thead>
              <tr>
                <th>Subject</th>
                <th>Grade</th>
                <th>Points</th>
              </tr>
            </thead>
            <tbody>
              {rec.subjects.map(s => {
                // Single source of truth: models/grades.py. FF is the ONLY
                // academic fail; AU is an audit subject (0 pts, not a fail);
                // AB is a PASS (8.5), not an absence. Do NOT colour AB/AU red.
                const g = s.grade.toUpperCase();
                const gradeClass = g === "FF" ? "badge-fail" : g === "AU" ? "badge-warn" : "badge-pass";
                return (
                  <tr key={s.code}>
                    <td className="font-data">{s.code}</td>
                    <td>
                      <span className={`badge ${gradeClass}`}>
                        {s.grade}
                      </span>
                    </td>
                    <td className="font-data">{s.point}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {rec.totalMarks && (
        <div style={{ padding: "11px 14px", borderTop: "1px solid var(--color-border)", fontSize: "var(--text-xs)", color: "var(--color-muted)", fontFamily: "var(--font-mono)" }}>
          Total Marks: {rec.totalMarks}
        </div>
      )}
    </div>
  );
}

// ─── Disambiguation renderer ─────────────────────────────────────

function DisambiguationOptions({
  kind,
  onSelect,
}: {
  kind: Extract<AnswerKind, { kind: "disambiguation" }>;
  onSelect: (roll: string) => void;
}) {
  return (
    <div>
      <p style={{ fontSize: "var(--text-sm)", color: "var(--color-muted)", marginBottom: 12 }}>
        Multiple students match <span className="font-data" style={{ color: "var(--color-text)" }}>{kind.extracted}</span>. Select one:
      </p>
      <div className="disambiguation-grid">
        {kind.options.map(opt => (
          <button key={opt.roll} className="disambiguation-option" onClick={() => onSelect(opt.roll)}>
            <div>
              <div className="disambiguation-name">{opt.name}</div>
              <div className="disambiguation-meta">Roll: {opt.roll}</div>
            </div>
            <div className="match-score">
              {opt.score.toFixed(1)}% match
            </div>
          </button>
        ))}
      </div>
      <p style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)", marginTop: 10 }}>
        Click a name to fetch the full record.
      </p>
    </div>
  );
}

// ─── Sources panel ───────────────────────────────────────────────

// Which documents the answer was actually built from. Until now no surface showed
// this: the retrieval layer had the metadata and dropped it, while the GLOBAL prompt
// asked the model to write a citations section out of thin air. An answer about fees
// or rules is not checkable without it.
function SourcesPanel({ sources }: { sources?: SourceRef[] }) {
  if (!sources || sources.length === 0) return null;
  return (
    <div className="card">
      <div className="card-header">
        <span className="card-title">Sources</span>
        <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-faint)" }}>
          {sources.length} {sources.length === 1 ? "document" : "documents"}
        </span>
      </div>
      <div className="card-body">
        <ul style={{ margin: 0, paddingLeft: 18, fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>
          {sources.map((s, i) => (
            <li key={i} style={{ marginBottom: 4 }}>
              <span style={{ color: "var(--color-text)" }}>{s.source}</span>
              {s.section ? <span style={{ color: "var(--color-faint)" }}> › {s.section}</span> : null}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

// ─── Context panel ───────────────────────────────────────────────

function ContextPanel({ context }: { context: string }) {
  if (!context || context.trim() === "") return null;
  return (
    <div className="card">
      <div className="card-header">
        <span className="card-title">Context Used</span>
        <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-faint)" }}>
          {context.length} chars
        </span>
      </div>
      <div className="card-body">
        <pre style={{
          margin: 0,
          fontSize: "var(--text-xs)",
          color: "var(--color-muted)",
          fontFamily: "var(--font-mono)",
          whiteSpace: "pre-wrap",
          wordBreak: "break-word",
          lineHeight: 1.6,
          maxHeight: 420,
          overflowY: "auto",
        }}>
          {context}
        </pre>
      </div>
    </div>
  );
}

// ─── History sidebar item ────────────────────────────────────────

function HistoryList({
  history,
  onSelect,
}: {
  history: HistoryEntry[];
  onSelect: (e: HistoryEntry) => void;
}) {
  if (!history.length)
    return (
      <div className="empty-state" style={{ padding: "36px 16px" }}>
        <div className="empty-state-sub">No queries yet</div>
        <div style={{ fontSize: "var(--text-xs)", color: "var(--color-faint)" }}>
          Your recent queries appear here
        </div>
      </div>
    );
  return (
    <div style={{ padding: "6px 8px" }}>
      {history.map(e => (
        <div key={e.id} className="history-item" onClick={() => onSelect(e)}>
          <TypeBadge type={e.type} />
          <span className="history-query" title={e.query}>{e.query}</span>
          <span className="history-time">
            {e.ts.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" })}
          </span>
        </div>
      ))}
    </div>
  );
}

// ─── Loading skeleton ────────────────────────────────────────────

function ResultSkeleton() {
  return (
    <div className="result-wrap">
      <div>
        <div className="skeleton" style={{ height: 22, width: 90, marginBottom: 16 }} />
        <div className="skeleton" style={{ height: 13, width: "94%", marginBottom: 9 }} />
        <div className="skeleton" style={{ height: 13, width: "88%", marginBottom: 9 }} />
        <div className="skeleton" style={{ height: 13, width: "72%", marginBottom: 9 }} />
        <div className="skeleton" style={{ height: 13, width: "80%" }} />
      </div>
      <div className="skeleton" style={{ height: 180 }} />
    </div>
  );
}

// ─── Main page ───────────────────────────────────────────────────

// tenant_1 = real institutional data (369 students, DuckDB) — the TABULAR route.
// tenant_bench = the 30-document benchmark corpus the 88.9% was measured on.
const TENANTS = ["tenant_1", "tenant_bench"];

export default function QueryConsolePage() {
  const [query, setQuery] = useState("");
  const [tenant, setTenant] = useState("tenant_1");
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<QueryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const idRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const submit = async (q: string = query) => {
    if (!q.trim() || loading) return;
    abortRef.current?.abort();
    abortRef.current = new AbortController();
    setLoading(true);
    setError(null);
    setResponse(null);
    try {
      const res = await postQuery(q.trim(), tenant, abortRef.current.signal);
      setResponse(res);
      setHistory(prev => [
        {
          id: idRef.current++,
          query: q.trim(),
          tenant,
          type: res.query_type,
          hasFallback: !!res.metadata.fallback_reason,
          ts: new Date(),
          response: res,
        },
        ...prev.slice(0, 49),
      ]);
    } catch (e: unknown) {
      if ((e as Error).name === "AbortError") return;
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const runExample = (q: string) => { setQuery(q); submit(q); };

  const handleDisambiguationSelect = (roll: string) => {
    setQuery(roll);
    submit(roll);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); submit(); }
  };

  const classified = response ? classifyAnswer(response.answer) : null;

  return (
    <div className="console-layout" style={{ display: "flex", height: "calc(100vh - var(--strip-h))", overflow: "hidden" }}>
      {/* ─ Left: Query history ─────────────────────────────────── */}
      <div className="console-history" style={{
        width: 288, flexShrink: 0,
        borderRight: "1px solid var(--color-border)",
        background: "var(--color-shell)",
        display: "flex", flexDirection: "column", overflow: "hidden",
      }}>
        <div className="card-header" style={{ background: "var(--color-shell)" }}>
          <span className="card-title">Query History</span>
          {history.length > 0 && (
            <button
              onClick={() => setHistory([])}
              className="btn btn-ghost"
              style={{ padding: "4px 10px", fontSize: "var(--text-xs)" }}
            >
              Clear
            </button>
          )}
        </div>
        <div style={{ overflowY: "auto", flex: 1 }}>
          <HistoryList
            history={history}
            onSelect={e => { setQuery(e.query); setResponse(e.response); }}
          />
        </div>
      </div>

      {/* ─ Centre: Main query area ─────────────────────────────── */}
      <div style={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        <div className="page-header">
          <h1 className="page-title">Query Console</h1>
          <p className="page-subtitle">Ask the Campus Brain — every query auto-routes to fact lookup, multi-hop, tabular, or decision-assisting retrieval.</p>
        </div>

        <div className="page-body" style={{ flex: 1, overflowY: "auto", display: "flex", flexDirection: "column", gap: 20 }}>
          {/* Input row */}
          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <select
              className="tenant-select"
              value={tenant}
              onChange={e => setTenant(e.target.value)}
              aria-label="Tenant"
            >
              {TENANTS.map(t => <option key={t} value={t}>{t}</option>)}
            </select>

            <div className="query-input-wrap" style={{ flex: 1 }}>
              <SearchIcon size={16} />
              <input
                className="query-input"
                value={query}
                onChange={e => setQuery(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder='Ask anything — e.g. "average SGPA" or "what is RAG-MicroSim?"'
                autoFocus
              />
              <button
                className="query-submit"
                onClick={() => submit()}
                disabled={loading || !query.trim()}
                title="Submit (Enter)"
                aria-label="Submit query"
              >
                {loading
                  ? <span className="spinner" style={{ width: 15, height: 15 }} />
                  : <SendIcon size={16} />
                }
              </button>
            </div>
          </div>

          {/* Error */}
          {error && (
            <div style={{
              display: "flex", alignItems: "center", gap: 9,
              background: "var(--color-fail-bg)", border: "1px solid rgba(240,85,90,0.28)",
              borderRadius: "var(--radius-sm)", padding: "11px 14px",
              fontSize: "var(--text-sm)", color: "var(--color-fail)",
              fontFamily: "var(--font-mono)",
            }}>
              <AlertIcon size={16} /> {error}
            </div>
          )}

          {/* Loading */}
          {loading && <ResultSkeleton />}

          {/* Result */}
          {!loading && response && classified && (
            <div className="result-wrap">
              {/* Main answer */}
              <div className="result-main">
                {response.metadata.fallback_reason && (
                  <FallbackBanner reason={response.metadata.fallback_reason} />
                )}

                <div className="result-header">
                  <TypeBadge type={response.query_type} />
                  <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-faint)" }}>
                    tenant: {tenant}
                  </span>
                </div>

                {classified.kind === "student_record" && (
                  <StudentCard raw={classified.raw} />
                )}

                {classified.kind === "disambiguation" && (
                  <DisambiguationOptions
                    kind={classified}
                    onSelect={handleDisambiguationSelect}
                  />
                )}

                {classified.kind === "error" && (
                  <div style={{
                    display: "flex", alignItems: "flex-start", gap: 9,
                    background: "var(--color-warn-bg)", border: "1px solid rgba(240,180,41,0.28)",
                    borderRadius: "var(--radius-sm)", padding: "12px 16px",
                    fontSize: "var(--text-sm)", color: "var(--color-warn)",
                  }}>
                    <AlertIcon size={16} /> {classified.raw}
                  </div>
                )}

                {classified.kind === "text" && (
                  <div className="result-answer">{classified.raw}</div>
                )}
              </div>

              {/* Context panel */}
              <div className="result-context">
                <SourcesPanel sources={response.metadata.sources} />
                <ContextPanel context={response.context_used} />
              </div>
            </div>
          )}

          {/* Empty state — onboarding: examples + route legend */}
          {!response && !loading && !error && (
            <div style={{ display: "flex", flexDirection: "column", gap: 26, marginTop: 4 }}>
              <div>
                <div style={{ fontSize: "var(--text-xs)", fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.09em", color: "var(--color-faint)", marginBottom: 11 }}>
                  Try a query
                </div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 9 }}>
                  {EXAMPLES.map(ex => (
                    <button key={ex} className="chip" onClick={() => runExample(ex)}>
                      <SearchIcon size={13} /> {ex}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div style={{ fontSize: "var(--text-xs)", fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.09em", color: "var(--color-faint)", marginBottom: 11 }}>
                  How routing works
                </div>
                <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", gap: 12 }}>
                  {ROUTES.map(r => (
                    <div key={r.type} className="card card-hover" style={{ padding: "14px 16px" }}>
                      <TypeBadge type={r.type} />
                      <div style={{ fontSize: "var(--text-sm)", color: "var(--color-muted)", marginTop: 9, lineHeight: 1.5 }}>
                        {r.blurb}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
