"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import styles from "./audit.module.css";
import { apiUrl } from "@/lib/api";

// ─── Types ────────────────────────────────────────────────────────────────────

type AuditStatus = "idle" | "running" | "pass" | "fail" | "pending";

interface AuditCheck {
  name: string;
  passed: boolean;
  detail: string;
}

interface AuditResult {
  id: string;
  name: string;
  category: string;
  gate: boolean;
  status: "PASS" | "FAIL" | "running" | "pending";
  checks_passed: number;
  checks_total: number;
  failures: string[];
  details: AuditCheck[];
  duration_ms: number;
}

interface Scorecard {
  overall_pct: number;
  total_pass: number;
  total_audits: number;
  gate_passed: boolean;
  gate_failures: { id: string; name: string }[];
  category_scores: Record<string, { passed: number; total: number; pct: number }>;
  production_ready: boolean;
}

const CATEGORY_META: Record<string, { label: string; color: string; icon: string }> = {
  integrity:    { label: "Data Integrity",    color: "#3B6EF5", icon: "⬡" },
  security:     { label: "Security",          color: "#E5484D", icon: "⚑" },
  retrieval:    { label: "Retrieval",         color: "#A78BFA", icon: "◈" },
  observability:{ label: "Observability",     color: "#5B9BD5", icon: "⊞" },
  performance:  { label: "Performance",       color: "#E5A500", icon: "⚡" },
  reliability:  { label: "Reliability",       color: "#2EA97A", icon: "✓" },
  regression:   { label: "Regression",        color: "#F97316", icon: "↻" },
  decision:     { label: "Decision AI",       color: "#EC4899", icon: "⭐" },
};

const WEIGHT_MAP: Record<string, string> = {
  integrity: "20%", security: "20%", retrieval: "15%",
  observability: "10%", performance: "10%", reliability: "10%",
  decision: "5%", regression: "3%",
};

const ALL_AUDITS = [
  { id: "01", name: "Document Integrity",       category: "integrity",    gate: true  },
  { id: "02", name: "Extraction Verification",  category: "integrity",    gate: false },
  { id: "03", name: "Hallucination Resistance", category: "retrieval",    gate: true  },
  { id: "04", name: "Source Attribution",       category: "retrieval",    gate: false },
  { id: "05", name: "Cross-Doc Consistency",    category: "integrity",    gate: false },
  { id: "06", name: "Multi-Tenant Isolation",   category: "security",     gate: true  },
  { id: "07", name: "Prompt Injection",         category: "security",     gate: true  },
  { id: "08", name: "Retrieval Poisoning",      category: "retrieval",    gate: false },
  { id: "09", name: "SQL Injection",            category: "security",     gate: false },
  { id: "10", name: "Authorization RBAC",       category: "security",     gate: true  },
  { id: "11", name: "Audit Log Integrity",      category: "observability",gate: false },
  { id: "12", name: "Explainability",           category: "observability",gate: false },
  { id: "13", name: "Performance P99",          category: "performance",  gate: false },
  { id: "14", name: "Recovery",                 category: "reliability",  gate: false },
  { id: "15", name: "Idempotency",              category: "reliability",  gate: false },
  { id: "16", name: "Adversarial OCR",          category: "integrity",    gate: false },
  { id: "17", name: "Unicode Support",          category: "retrieval",    gate: false },
  { id: "18", name: "Fuzzy Search",             category: "retrieval",    gate: false },
  { id: "19", name: "Regression Benchmark",     category: "regression",   gate: false },
  { id: "20", name: "Enterprise Chaos",         category: "reliability",  gate: false },
  { id: "21", name: "Decision Intelligence",    category: "decision",     gate: false },
];

// ─── Sub-components ───────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { cls: string; label: string }> = {
    PASS:    { cls: "badge-pass",  label: "PASS" },
    FAIL:    { cls: "badge-fail",  label: "FAIL" },
    running: { cls: "badge-info",  label: "RUN…" },
    pending: { cls: styles.badgePending, label: "WAIT" },
  };
  const m = map[status] ?? map.pending;
  return <span className={`badge ${m.cls}`}>{m.label}</span>;
}

function GateBadge() {
  return (
    <span className={styles.gateBadge} title="Production Gate — critical blocker">
      GATE
    </span>
  );
}

function ScoreRing({ pct, size = 80, gate }: { pct: number; size?: number; gate?: boolean }) {
  const r = (size / 2) - 6;
  const circ = 2 * Math.PI * r;
  const dash = (pct / 100) * circ;
  const color = gate && pct < 100 ? "#E5484D" : pct >= 80 ? "#2EA97A" : pct >= 60 ? "#E5A500" : "#E5484D";
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <circle cx={size/2} cy={size/2} r={r} fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth={5} />
      {/* At 0% the dash length is 0, and a round linecap on a zero-length dash
          still paints both caps — so an unrun suite showed a stray coloured dot
          at 12 o'clock that read as a rendering fault. Draw no arc at all. */}
      {pct > 0 && (
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke={color} strokeWidth={5}
          strokeDasharray={`${dash} ${circ - dash}`}
          strokeDashoffset={circ / 4} strokeLinecap="round"
          style={{ transition: "stroke-dasharray 0.8s ease" }} />
      )}
      <text x={size/2} y={size/2 + 1} textAnchor="middle" dominantBaseline="middle"
        fill={color} fontSize={size < 80 ? 11 : 15} fontWeight={700} fontFamily="JetBrains Mono, monospace">
        {pct}%
      </text>
    </svg>
  );
}

function AuditRow({
  audit, result, isExpanded, onToggle,
}: {
  audit: typeof ALL_AUDITS[0];
  result: AuditResult | null;
  isExpanded: boolean;
  onToggle: () => void;
}) {
  const catMeta = CATEGORY_META[audit.category] ?? { color: "#8B95A8", icon: "•" };
  const status = result?.status ?? "pending";
  const pct = result ? Math.round((result.checks_passed / Math.max(result.checks_total, 1)) * 100) : 0;

  return (
    <>
      <tr
        className={`${styles.auditRow} ${isExpanded ? styles.auditRowExpanded : ""} ${status === "running" ? styles.auditRowRunning : ""}`}
        onClick={result ? onToggle : undefined}
        style={{ cursor: result ? "pointer" : "default" }}
      >
        <td className={styles.colId}>
          <span className="font-data" style={{ color: "var(--color-muted)" }}>
            {audit.id}
          </span>
        </td>
        <td className={styles.colName}>
          <div className={styles.auditName}>
            <span style={{ color: catMeta.color, width: 16, textAlign: "center", display: "inline-block" }}>
              {catMeta.icon}
            </span>
            {audit.name}
            {audit.gate && <GateBadge />}
          </div>
        </td>
        <td className={styles.colCategory}>
          <span className={styles.categoryTag} style={{ color: catMeta.color, borderColor: catMeta.color + "40" }}>
            {catMeta.label}
          </span>
        </td>
        <td className={styles.colStatus}><StatusBadge status={status} /></td>
        <td className={styles.colChecks}>
          {result ? (
            <div className={styles.checkBar}>
              <div className={styles.checkBarBg}>
                <div
                  className={styles.checkBarFill}
                  style={{
                    width: `${pct}%`,
                    background: pct === 100 ? "var(--color-pass)" : pct > 60 ? "var(--color-warn)" : "var(--color-fail)",
                  }}
                />
              </div>
              <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)", flexShrink: 0 }}>
                {result.checks_passed}/{result.checks_total}
              </span>
            </div>
          ) : (
            <span style={{ color: "var(--color-border)" }}>—</span>
          )}
        </td>
        <td className={styles.colDuration}>
          {result ? (
            <span className="font-data" style={{ color: "var(--color-muted)", fontSize: "var(--text-xs)" }}>
              {result.duration_ms}ms
            </span>
          ) : "—"}
        </td>
        <td className={styles.colExpand}>
          {result && (
            <span style={{ color: "var(--color-muted)", fontSize: 10 }}>
              {isExpanded ? "▲" : "▼"}
            </span>
          )}
        </td>
      </tr>
      {isExpanded && result && (
        <tr className={styles.detailRow}>
          <td colSpan={7}>
            <div className={styles.detailPanel}>
              {result.details.map((check, i) => (
                <div key={i} className={styles.checkLine}>
                  <span className={check.passed ? styles.checkPassIcon : styles.checkFailIcon}>
                    {check.passed ? "✓" : "✗"}
                  </span>
                  <span className={styles.checkName}>{check.name}</span>
                  <span className={styles.checkDetail}>{check.detail}</span>
                </div>
              ))}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

// ─── Main page ─────────────────────────────────────────────────────────────────

export default function AuditPage() {
  const [results, setResults] = useState<Record<string, AuditResult>>({});
  const [scorecard, setScorecard] = useState<Scorecard | null>(null);
  const [runStatus, setRunStatus] = useState<AuditStatus>("idle");
  const [currentAuditId, setCurrentAuditId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [runTs, setRunTs] = useState<string | null>(null);
  const [activeCategory, setActiveCategory] = useState<string>("all");
  const evtRef = useRef<EventSource | null>(null);

  const startAudit = useCallback(() => {
    if (evtRef.current) evtRef.current.close();
    setResults({});
    setScorecard(null);
    setRunStatus("running");
    setCurrentAuditId(null);
    setRunTs(null);

    const es = new EventSource(apiUrl(`/audit/stream`));
    evtRef.current = es;

    es.onmessage = (e) => {
      const data = JSON.parse(e.data);
      if (data.type === "start") {
        setRunStatus("running");
      } else if (data.type === "progress") {
        setCurrentAuditId(data.id);
        setResults(prev => ({
          ...prev,
          [data.id]: { ...(prev[data.id] || {}), id: data.id, name: data.name, status: "running" } as AuditResult,
        }));
      } else if (data.type === "result") {
        setResults(prev => ({ ...prev, [data.id]: data as AuditResult }));
        setCurrentAuditId(null);
      } else if (data.type === "complete") {
        setScorecard(data.scorecard);
        setRunStatus(data.scorecard.gate_passed ? "pass" : "fail");
        setRunTs(data.timestamp);
        es.close();
      }
    };
    es.onerror = () => {
      setRunStatus("fail");
      es.close();
    };
  }, []);

  useEffect(() => () => evtRef.current?.close(), []);

  const visibleAudits = activeCategory === "all"
    ? ALL_AUDITS
    : ALL_AUDITS.filter(a => a.category === activeCategory);

  const categories = [...new Set(ALL_AUDITS.map(a => a.category))];

  const passCount = Object.values(results).filter(r => r.status === "PASS").length;
  const failCount = Object.values(results).filter(r => r.status === "FAIL").length;
  const totalRun = passCount + failCount;
  const overallPct = totalRun > 0 ? Math.round(passCount / totalRun * 100) : 0;

  return (
    <div>
      {/* ─── Header ─────────────────────────────────────────────────────── */}
      <div className="page-header">
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
          <div>
            <h1 className="page-title">Enterprise Audit Suite</h1>
            <p className="page-subtitle">
              21-category production verification · Data integrity · Security · Retrieval · Chaos
            </p>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 12, paddingBottom: 16 }}>
            {runTs && (
              <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>
                Last run: {new Date(runTs).toLocaleTimeString()}
              </span>
            )}
            <button
              className={styles.runBtn}
              onClick={startAudit}
              disabled={runStatus === "running"}
              id="btn-run-audit"
            >
              {runStatus === "running" ? (
                <><span className="spinner" style={{ width: 14, height: 14 }} /> Running…</>
              ) : "▶ Run All Audits"}
            </button>
          </div>
        </div>
      </div>

      <div className="page-body" style={{ display: "flex", flexDirection: "column", gap: 20 }}>

        {/* ─── Scorecard Row ────────────────────────────────────────────── */}
        <div className={styles.scorecardRow}>
          {/* Overall ring */}
          <div className={`card ${styles.overallCard}`}>
            <div className={styles.overallInner}>
              <ScoreRing pct={scorecard?.overall_pct ?? overallPct} size={88} />
              <div>
                <div className={styles.overallLabel}>Overall Score</div>
                <div className={styles.overallSub}>
                  {scorecard ? `${scorecard.total_pass}/${scorecard.total_audits} passed` : `${passCount}/${totalRun} run`}
                </div>
                {scorecard && (
                  <div style={{ marginTop: 8 }}>
                    <span className={`badge ${scorecard.production_ready ? "badge-pass" : "badge-fail"}`} style={{ fontSize: 11 }}>
                      {scorecard.production_ready ? "✓ PRODUCTION READY" : "✗ NOT PRODUCTION READY"}
                    </span>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Gate status */}
          <div className={`card ${styles.gateCard}`} style={{ borderColor: scorecard?.gate_passed === false ? "rgba(229,72,77,0.4)" : undefined }}>
            <div className="card-header">
              <span className="card-title">Production Gate</span>
              {scorecard && (
                <span className={`badge ${scorecard.gate_passed ? "badge-pass" : "badge-fail"}`}>
                  {scorecard.gate_passed ? "✓ PASSED" : "✗ BLOCKED"}
                </span>
              )}
            </div>
            <div className="card-body" style={{ padding: "12px 16px" }}>
              {scorecard?.gate_failures?.length ? (
                <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                  {scorecard.gate_failures.map(f => (
                    <div key={f.id} className={styles.gateFailItem}>
                      <span style={{ color: "var(--color-fail)" }}>✗</span>
                      <span style={{ fontSize: "var(--text-xs)" }}>{f.name}</span>
                    </div>
                  ))}
                </div>
              ) : (
                <div style={{ color: "var(--color-muted)", fontSize: "var(--text-xs)" }}>
                  {scorecard ? "All 5 critical gates passed" : "Run audits to evaluate gates"}
                </div>
              )}
              <div className={styles.gateItems}>
                {["Tenant Isolation", "Authorization", "Silent Corruption", "Hallucination", "Prompt Injection"].map(gate => {
                  const gateAuditMap: Record<string, string> = {
                    "Tenant Isolation": "06",
                    "Authorization": "10",
                    "Silent Corruption": "01",
                    "Hallucination": "03",
                    "Prompt Injection": "07",
                  };
                  const id = gateAuditMap[gate];
                  const r = results[id];
                  return (
                    <div key={gate} className={styles.gateItem}>
                      <span style={{ color: !r ? "var(--color-border)" : r.status === "PASS" ? "var(--color-pass)" : "var(--color-fail)" }}>
                        {!r ? "○" : r.status === "PASS" ? "●" : "●"}
                      </span>
                      <span style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>{gate}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>

          {/* Category mini-scores */}
          <div className={`card ${styles.categoryCard}`}>
            <div className="card-header"><span className="card-title">Category Scores</span></div>
            <div className="card-body" style={{ padding: "10px 16px", display: "flex", flexDirection: "column", gap: 8 }}>
              {categories.map(cat => {
                const meta = CATEGORY_META[cat] ?? { label: cat, color: "#8B95A8" };
                const score = scorecard?.category_scores?.[cat];
                const catResults = Object.values(results).filter(r => r.category === cat);
                const catPass = catResults.filter(r => r.status === "PASS").length;
                const catTotal = catResults.length || ALL_AUDITS.filter(a => a.category === cat).length;
                const pct = score?.pct ?? (catTotal > 0 ? Math.round(catPass / catTotal * 100) : 0);
                return (
                  <div key={cat} className={styles.catScoreRow}>
                    <span className={styles.catLabel} style={{ color: meta.color }}>{meta.label}</span>
                    <div className={styles.catBar}>
                      <div
                        className={styles.catBarFill}
                        style={{ width: `${pct}%`, background: meta.color + "99" }}
                      />
                    </div>
                    <span className={`font-data ${styles.catPct}`}>{pct}%</span>
                    {/* Was var(--color-border) — so close to the background it read as
                        a smudge rather than a number, and at width 28 it sat flush
                        against the card's right padding. */}
                    <span className={styles.catWeight} title="Scorecard weight">
                      {WEIGHT_MAP[cat] ?? ""}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* ─── Running indicator ───────────────────────────────────────── */}
        {runStatus === "running" && currentAuditId && (
          <div className={styles.runningBanner}>
            <span className="spinner" />
            <span>Running audit <strong>{currentAuditId}</strong> — {ALL_AUDITS.find(a => a.id === currentAuditId)?.name}</span>
          </div>
        )}

        {/* ─── Audit Table ──────────────────────────────────────────────── */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">Audit Results — {ALL_AUDITS.length} audits</span>
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
              <button
                className={`${styles.catFilter} ${activeCategory === "all" ? styles.catFilterActive : ""}`}
                onClick={() => setActiveCategory("all")}
              >All</button>
              {categories.map(cat => (
                <button
                  key={cat}
                  className={`${styles.catFilter} ${activeCategory === cat ? styles.catFilterActive : ""}`}
                  onClick={() => setActiveCategory(cat)}
                  style={activeCategory === cat ? { borderColor: CATEGORY_META[cat]?.color, color: CATEGORY_META[cat]?.color } : {}}
                >
                  {CATEGORY_META[cat]?.label ?? cat}
                </button>
              ))}
            </div>
          </div>
          <div style={{ overflowX: "auto" }}>
            <table className={styles.auditTable}>
              <thead>
                <tr className={styles.tableHead}>
                  <th className={styles.colId}>#</th>
                  <th className={styles.colName}>Audit Name</th>
                  <th className={styles.colCategory}>Category</th>
                  <th className={styles.colStatus}>Status</th>
                  <th className={styles.colChecks}>Checks</th>
                  <th className={styles.colDuration}>Duration</th>
                  <th className={styles.colExpand} />
                </tr>
              </thead>
              <tbody>
                {visibleAudits.map(audit => (
                  <AuditRow
                    key={audit.id}
                    audit={audit}
                    result={results[audit.id] ?? null}
                    isExpanded={expandedId === audit.id}
                    onToggle={() => setExpandedId(expandedId === audit.id ? null : audit.id)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* ─── Weighted Scorecard spec table ───────────────────────────── */}
        <div className="card">
          <div className="card-header"><span className="card-title">Production Scorecard Weights</span></div>
          <div className="card-body" style={{ padding: 0 }}>
            <table className={styles.specTable}>
              <thead>
                <tr>
                  {["Category", "Weight", "Audits", "Score", "Gate?"].map(h => (
                    <th key={h} style={{ textAlign: "left", padding: "8px 16px", color: "var(--color-muted)", fontSize: "var(--text-xs)", fontWeight: 600, textTransform: "uppercase", letterSpacing: "0.06em", borderBottom: "1px solid var(--color-border)" }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {[
                  { cat: "integrity",    weight: "20%", label: "Data Integrity & Verification",  audits: "01,02,05,16",      gate: true  },
                  { cat: "security",     weight: "20%", label: "Security & Tenant Isolation",     audits: "06,07,09,10",      gate: true  },
                  { cat: "retrieval",    weight: "15%", label: "Retrieval Correctness",           audits: "03,04,08,17,18",   gate: true  },
                  { cat: "observability",weight: "10%", label: "Observability & Auditability",    audits: "11,12",            gate: false },
                  { cat: "reliability",  weight: "10%", label: "Reliability & Fault Tolerance",  audits: "14,15,20",         gate: false },
                  { cat: "performance",  weight: "10%", label: "Performance & Scalability",       audits: "13",               gate: false },
                  { cat: "decision",     weight: "5%",  label: "Decision Intelligence",           audits: "21",               gate: false },
                  { cat: "regression",   weight: "3%",  label: "Regression Benchmark",            audits: "19",               gate: false },
                ].map(row => {
                  const catResults = Object.values(results).filter(r => r.category === row.cat);
                  const pct = catResults.length > 0
                    ? Math.round(catResults.filter(r => r.status === "PASS").length / catResults.length * 100)
                    : null;
                  const meta = CATEGORY_META[row.cat] ?? { color: "#8B95A8" };
                  return (
                    <tr key={row.cat} style={{ borderBottom: "1px solid rgba(46,58,80,0.4)" }}>
                      <td style={{ padding: "10px 16px", color: "var(--color-text)", fontSize: "var(--text-sm)" }}>
                        <span style={{ color: meta.color, marginRight: 8 }}>{CATEGORY_META[row.cat]?.icon}</span>
                        {row.label}
                      </td>
                      <td style={{ padding: "10px 16px" }}>
                        <span className="font-data" style={{ color: "var(--color-accent)" }}>{row.weight}</span>
                      </td>
                      <td style={{ padding: "10px 16px" }}>
                        <span className="font-data" style={{ color: "var(--color-muted)", fontSize: "var(--text-xs)" }}>{row.audits}</span>
                      </td>
                      <td style={{ padding: "10px 16px" }}>
                        {pct !== null ? (
                          <span className="font-data" style={{ color: pct === 100 ? "var(--color-pass)" : pct >= 60 ? "var(--color-warn)" : "var(--color-fail)" }}>
                            {pct}%
                          </span>
                        ) : <span style={{ color: "var(--color-border)" }}>—</span>}
                      </td>
                      <td style={{ padding: "10px 16px" }}>
                        {row.gate && <span className={styles.gateBadge}>GATE</span>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>

      </div>
    </div>
  );
}
