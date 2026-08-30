"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { LibraryIcon, AlertIcon } from "@/components/icons";

interface Document {
  doc_id: string;
  file_hash: string | null;
  parse_status: string;
  last_indexed_at: string | null;
  error_message: string | null;
  page_count: number | null;
  file_size_bytes: number | null;
  flags: string | null;
}

const STATUS_BADGE: Record<string, string> = {
  SUCCESS: "badge-pass",
  FAILED:  "badge-fail",
  PENDING: "badge-warn",
  WARNING: "badge-warn",
};

function fmt_bytes(b: number | null) {
  if (!b) return "—";
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

export default function DocumentsPage() {
  const [docs, setDocs] = useState<Document[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [tenant, setTenant] = useState("tenant_1");
  const [filter, setFilter] = useState("ALL");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-mount, not derived-state sync
    setLoading(true);
    apiFetch(`/documents?tenant_id=${tenant}`)
      .then(r => r.json())
      .then(d => {
        setDocs(d.documents ?? []);
        setTotal(d.total ?? 0);
        setError(d.error ?? null);
        setLoading(false);
      })
      .catch(() => { setError("Could not reach API"); setLoading(false); });
  }, [tenant]);

  const visible = filter === "ALL" ? docs : docs.filter(d => d.parse_status === filter);

  const counts = {
    SUCCESS: docs.filter(d => d.parse_status === "SUCCESS").length,
    FAILED:  docs.filter(d => d.parse_status === "FAILED").length,
    PENDING: docs.filter(d => d.parse_status === "PENDING").length,
  };

  return (
    <div>
      <div className="page-header">
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
          <div>
            <h1 className="page-title">Document Library</h1>
            <p className="page-subtitle">All ingested documents · parse status · checksum · page count</p>
          </div>
          <div style={{ paddingBottom: 16, display: "flex", gap: 8, alignItems: "center" }}>
            <select className="tenant-select" value={tenant} onChange={e => setTenant(e.target.value)}>
              {["tenant_1", "tenant_2", "stress_test"].map(t => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>
        </div>
      </div>

      <div className="page-body" style={{ display: "flex", flexDirection: "column", gap: 16 }}>

        {/* Summary strip */}
        <div style={{ display: "flex", gap: 10 }}>
          {[
            { label: "Total", count: total, badge: "badge-info", key: "ALL" },
            { label: "Parsed", count: counts.SUCCESS, badge: "badge-pass", key: "SUCCESS" },
            { label: "Failed", count: counts.FAILED, badge: "badge-fail", key: "FAILED" },
            { label: "Pending", count: counts.PENDING, badge: "badge-warn", key: "PENDING" },
          ].map(s => (
            <button key={s.key} onClick={() => setFilter(s.key)}
              className={`filter-btn ${filter === s.key ? "active" : ""}`}>
              {s.label} <span className={`badge ${s.badge}`}>{s.count}</span>
            </button>
          ))}
        </div>

        {error && (
          <div style={{ display: "flex", alignItems: "center", gap: 9, padding: "11px 14px", borderRadius: "var(--radius-sm)", background: "var(--color-fail-bg)", border: "1px solid rgba(240,85,90,0.28)", color: "var(--color-fail)", fontSize: "var(--text-sm)" }}>
            <AlertIcon size={16} /> {error}
          </div>
        )}

        {loading ? (
          <div className="empty-state"><span className="spinner" /><div className="empty-state-title">Loading documents…</div></div>
        ) : visible.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon"><LibraryIcon size={24} /></div>
            <div className="empty-state-title">No documents</div>
            <div className="empty-state-sub">Upload files via the ingestion pipeline</div>
          </div>
        ) : (
          <div className="card">
            <div className="card-header">
              <span className="card-title">Documents — {tenant}</span>
              <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-faint)" }}>{visible.length} shown</span>
            </div>
            <div style={{ overflowX: "auto" }}>
              <table className="data-table">
                <thead>
                  <tr>
                    {["Document", "Status", "Hash", "Pages", "Size", "Flags", "Last Indexed"].map(h => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {visible.map((doc, i) => {
                    let parsedFlags: string[] = [];
                    try { parsedFlags = doc.flags ? JSON.parse(doc.flags) : []; } catch {}
                    return (
                      <tr key={i}>
                        <td style={{ maxWidth: 280 }}>
                          <div style={{ color: "var(--color-text)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }} title={doc.doc_id}>{doc.doc_id}</div>
                          {doc.error_message && <div style={{ fontSize: "var(--text-xs)", color: "var(--color-fail)", marginTop: 2 }}>{doc.error_message.slice(0, 60)}…</div>}
                        </td>
                        <td>
                          <span className={`badge ${STATUS_BADGE[doc.parse_status] ?? "badge-info"}`}>{doc.parse_status}</span>
                        </td>
                        <td>
                          <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>{doc.file_hash ?? "—"}</span>
                        </td>
                        <td>
                          <span className="font-data" style={{ color: "var(--color-text)" }}>{doc.page_count ?? "—"}</span>
                        </td>
                        <td>
                          <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>{fmt_bytes(doc.file_size_bytes)}</span>
                        </td>
                        <td>
                          <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
                            {parsedFlags.map((f, fi) => (
                              <span key={fi} className={`badge ${f === "PARSE_FAILURE" ? "badge-fail" : f === "TABLE_BROKEN" ? "badge-warn" : "badge-info"}`}>{f}</span>
                            ))}
                          </div>
                        </td>
                        <td>
                          <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>
                            {doc.last_indexed_at ? new Date(doc.last_indexed_at).toLocaleString() : "—"}
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
