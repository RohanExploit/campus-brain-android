"use client";
import { useEffect, useState } from "react";
import { apiFetch, API_BASE } from "@/lib/api";
import { LayersIcon } from "@/components/icons";

interface Tenant {
  id: string;
  doc_count: number;
  student_count: number;
  has_manifest: boolean;
  has_duckdb: boolean;
  last_indexed: string | null;
}

export default function TenantsPage() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch(`/tenants`)
      .then(r => r.json())
      .then(d => { setTenants(d.tenants ?? []); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">Tenant Overview</h1>
        <p className="page-subtitle">Registered tenants · document counts · ingestion status · encryption</p>
      </div>
      <div className="page-body">
        {loading ? (
          <div className="empty-state"><span className="spinner" /><div className="empty-state-title">Loading tenants…</div></div>
        ) : tenants.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon"><LayersIcon size={24} /></div>
            <div className="empty-state-title">No tenant data returned from API</div>
            <div className="empty-state-sub">Ensure backend is running at {API_BASE}</div>
          </div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            {tenants.map(t => (
              <div key={t.id} className="card card-hover">
                <div className="card-header">
                  <span className="card-title font-data">{t.id}</span>
                  <div style={{ display: "flex", gap: 8 }}>
                    <span className={`badge ${t.has_manifest ? "badge-pass" : "badge-fail"}`}>manifest.db</span>
                    <span className={`badge ${t.has_duckdb ? "badge-pass" : "badge-fail"}`}>tabular.duckdb</span>
                  </div>
                </div>
                <div className="card-body" style={{ display: "flex", gap: 32 }}>
                  <div><div style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>Documents</div>
                    <div className="font-data" style={{ fontSize: "var(--text-xl)", color: "var(--color-text)" }}>{t.doc_count}</div></div>
                  <div><div style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>Students</div>
                    <div className="font-data" style={{ fontSize: "var(--text-xl)", color: "var(--color-text)" }}>{t.student_count}</div></div>
                  <div><div style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>Last Indexed</div>
                    <div className="font-data" style={{ fontSize: "var(--text-sm)", color: "var(--color-muted)" }}>{t.last_indexed ?? "Never"}</div></div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
