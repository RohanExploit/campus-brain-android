"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { CheckIcon } from "@/components/icons";

interface ReviewItem {
  roll_no: string;
  name?: string;
  flags?: string;          // JSON-encoded string[] e.g. '["gap_exceeds_max_possible"]'
  gap?: number;
  derived_max?: number;
  raw_block?: string;
  tenant_id?: string;
}

function parseFlags(flags?: string): string[] {
  if (!flags) return [];
  try {
    const parsed = JSON.parse(flags);
    return Array.isArray(parsed) ? parsed.map(String) : [String(parsed)];
  } catch {
    return [flags];
  }
}

export default function ReviewPage() {
  const [items, setItems] = useState<ReviewItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch(`/review`)
      .then(r => r.json())
      .then(d => { setItems(d.items ?? []); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">Needs-Review Queue</h1>
        <p className="page-subtitle">Extraction failures · SGPA mismatches · flagged records requiring human verification</p>
      </div>
      <div className="page-body">
        {loading ? (
          <div className="empty-state"><span className="spinner" /><div className="empty-state-title">Loading…</div></div>
        ) : items.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon" style={{ color: "var(--color-pass)", borderColor: "rgba(52,200,138,0.3)" }}><CheckIcon size={24} /></div>
            <div className="empty-state-title">No records in review queue</div>
            <div className="empty-state-sub">All extractions verified clean</div>
          </div>
        ) : (
          <div className="card">
            <div className="card-header">
              <span className="card-title">Flagged Records</span>
              <span className="badge badge-fail">{items.length} item{items.length !== 1 ? "s" : ""}</span>
            </div>
            <div style={{ overflowX: "auto" }}>
              <table className="data-table">
                <thead>
                  <tr>
                    {["Roll No", "Name", "Issue", "Details", "Source"].map(h => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {items.map((item, i) => {
                    const flags = parseFlags(item.flags);
                    const detailBits: string[] = [];
                    if (item.gap != null) detailBits.push(`gap ${item.gap}`);
                    if (item.derived_max != null) detailBits.push(`max ${item.derived_max}`);
                    const details = detailBits.join(" · ") || (item.raw_block?.split("\n")[0] ?? "—");
                    return (
                      <tr key={i}>
                        <td><span className="font-data" style={{ color: "var(--color-accent)" }}>{item.roll_no}</span></td>
                        <td style={{ color: "var(--color-text)" }}>{item.name ?? "—"}</td>
                        <td>
                          <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                            {flags.length ? flags.map((f, j) => (
                              <span key={j} className="badge badge-warn">{f}</span>
                            )) : <span style={{ color: "var(--color-muted)" }}>—</span>}
                          </div>
                        </td>
                        <td style={{ color: "var(--color-muted)", maxWidth: 320 }}>{details}</td>
                        <td><span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>{item.tenant_id ?? "—"}</span></td>
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
