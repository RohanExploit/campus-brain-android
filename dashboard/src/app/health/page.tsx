"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { RefreshIcon } from "@/components/icons";

interface OllamaStatus { reachable: boolean; model: string; vram_used_gb: number | null; vram_total_gb: number | null; }
interface SystemStatus { ollama: OllamaStatus; registered_tenant_count: number; total_docs: number; timestamp: string; }

function StatCard({ label, value, sub, color }: { label: string; value: string | number; sub?: string; color?: string }) {
  return (
    <div className="card card-hover stat-card">
      <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: 5 }}>
        <div className="stat-label">{label}</div>
        <div className="stat-value" style={{ color: color ?? "var(--color-text)" }}>{value}</div>
        {sub && <div className="stat-sub">{sub}</div>}
      </div>
    </div>
  );
}

export default function HealthPage() {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null);

  const refresh = () => {
    setLoading(true);
    apiFetch(`/admin/status`)
      .then(r => r.json())
      .then(d => { setStatus(d); setLoading(false); setLastRefresh(new Date()); })
      .catch(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-mount, not derived-state sync
    refresh();
    const id = setInterval(refresh, 15000);
    return () => clearInterval(id);
  }, []);

  const ollama = status?.ollama;

  return (
    <div>
      <div className="page-header">
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
          <div>
            <h1 className="page-title">System Health</h1>
            <p className="page-subtitle">Ollama status · VRAM · tenant counts · pipeline health · auto-refreshes every 15s</p>
          </div>
          <div style={{ paddingBottom: 16, display: "flex", alignItems: "center", gap: 10 }}>
            <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>
              {lastRefresh ? lastRefresh.toLocaleTimeString() : "—"}
            </span>
            <button onClick={refresh} disabled={loading} className="btn btn-ghost" style={{ padding: "6px 12px" }}>
              {loading ? <span className="spinner" style={{ width: 13, height: 13 }} /> : <RefreshIcon size={14} />}
              Refresh
            </button>
          </div>
        </div>
      </div>

      <div className="page-body" style={{ display: "flex", flexDirection: "column", gap: 20 }}>

        {/* Stat cards */}
        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <StatCard label="LLM Engine" value={ollama?.reachable ? "Online" : "Offline"} sub={ollama?.model ?? "—"}
            color={ollama?.reachable ? "var(--color-pass)" : "var(--color-fail)"} />
          <StatCard label="VRAM Used" value={ollama?.vram_used_gb != null ? `${ollama.vram_used_gb} GB` : "—"}
            sub={ollama?.vram_total_gb != null ? `of ${ollama.vram_total_gb} GB` : "nvidia-smi unavailable"}
            color={ollama?.vram_used_gb != null && ollama?.vram_total_gb != null && ollama.vram_used_gb / ollama.vram_total_gb > 0.85 ? "var(--color-warn)" : "var(--color-text)"} />
          <StatCard label="Tenants" value={status?.registered_tenant_count ?? "—"} sub="registered in allowlist" />
          <StatCard label="Total Documents" value={status?.total_docs ?? "—"} sub="across all registered tenants" />
        </div>

        {/* Ollama detail card */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">LLM Engine — Ollama</span>
            {ollama && <span className={`badge ${ollama.reachable ? "badge-pass" : "badge-fail"}`}>
              {ollama.reachable ? "REACHABLE" : "UNREACHABLE"}
            </span>}
          </div>
          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {[
              { label: "Endpoint", value: "http://127.0.0.1:11434" },
              { label: "Model", value: ollama?.model ?? "—" },
              { label: "VRAM", value: ollama?.vram_used_gb != null ? `${ollama.vram_used_gb} / ${ollama.vram_total_gb} GB` : "GPU info unavailable" },
              { label: "Status", value: ollama?.reachable ? "Serving requests" : "Not reachable — queries will use NVIDIA API fallback" },
            ].map(row => (
              <div key={row.label} style={{ display: "flex", gap: 16, fontSize: "var(--text-sm)" }}>
                <span style={{ color: "var(--color-muted)", width: 90, flexShrink: 0 }}>{row.label}</span>
                <span className="font-data" style={{ color: "var(--color-text)" }}>{row.value}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Pipeline stages */}
        <div className="card">
          <div className="card-header"><span className="card-title">Pipeline Stages</span></div>
          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            {[
              { stage: "Parse (Docling)",           file: "ingestion/parse.py",         status: "active" },
              { stage: "Chunk",                      file: "ingestion/chunk.py",         status: "active" },
              { stage: "Embed (FAISS)",              file: "ingestion/vector_store.py",  status: "active" },
              { stage: "Extract Tabular (DuckDB)",   file: "ingestion/parse_tabular.py", status: "active" },
              { stage: "Graph (NetworkX)",           file: "ingestion/build_graph.py",   status: "active" },
              { stage: "Route Query",                file: "retrieval/router.py",        status: "active" },
              { stage: "Generate Answer (Ollama)",   file: "generation/answer.py",       status: ollama?.reachable ? "active" : "fallback" },
            ].map(row => (
              <div key={row.stage} style={{ display: "flex", alignItems: "center", gap: 12 }}>
                <span className={`badge ${row.status === "active" ? "badge-pass" : "badge-warn"}`}>{row.status.toUpperCase()}</span>
                <span style={{ fontSize: "var(--text-sm)", color: "var(--color-text)", width: 240 }}>{row.stage}</span>
                <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>{row.file}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Last updated */}
        {status?.timestamp && (
          <div style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)", textAlign: "right" }}>
            API timestamp: {new Date(status.timestamp).toLocaleString()}
          </div>
        )}
      </div>
    </div>
  );
}
