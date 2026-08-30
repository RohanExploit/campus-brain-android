"use client";

import { useEffect, useState } from "react";
import type { AdminStatus } from "@/lib/api";
import { getAdminStatus } from "@/lib/api";
import { ChipIcon, DatabaseIcon, LayersIcon, ClockIcon } from "./icons";

function Sep() {
  return <span className="strip-sep" aria-hidden />;
}

export default function StatusStrip() {
  const [status, setStatus] = useState<AdminStatus | null>(null);
  const [error, setError] = useState(false);

  const fetchStatus = async () => {
    try {
      const data = await getAdminStatus();
      setStatus(data);
      setError(false);
    } catch {
      setError(true);
    }
  };

  useEffect(() => {
    const id = setInterval(fetchStatus, 15_000);   // refresh every 15s
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-mount, not derived-state sync
    void fetchStatus();
    return () => clearInterval(id);
  }, []);

  const ollamaOk = !error && status?.ollama.reachable;
  const modelShort = status?.ollama.model?.replace("qwen3:", "qwen3:").split("-q")[0] ?? "—";
  const vramLine =
    status?.ollama.vram_used_gb != null && status?.ollama.vram_total_gb != null
      ? `${status.ollama.vram_used_gb} / ${status.ollama.vram_total_gb} GB VRAM`
      : null;

  const lastSync = (() => {
    if (!status) return "—";
    const registered = status.tenants.filter(t => t.registered);
    const dates = registered
      .map(t => t.last_indexed_at)
      .filter(Boolean)
      .map(d => new Date(d!))
      .sort((a, b) => b.getTime() - a.getTime());
    if (!dates.length) return "never";
    const d = dates[0];
    return d.toLocaleDateString("en-IN", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
  })();

  return (
    <div className="status-strip">
      <span className="strip-item">
        <span
          className={`strip-dot ${ollamaOk ? "strip-dot-pass" : "strip-dot-fail"}`}
          title={ollamaOk ? "Ollama reachable" : "Ollama unreachable"}
        />
        <span className={ollamaOk ? "strip-label-ok" : "strip-label-fail"}>
          Ollama {modelShort}
        </span>
      </span>

      {vramLine && (
        <>
          <Sep />
          <span className="strip-item"><ChipIcon size={12} /> {vramLine}</span>
        </>
      )}

      <Sep />
      <span className="strip-item">
        <LayersIcon size={12} />
        {status ? `${status.registered_tenant_count} tenant${status.registered_tenant_count !== 1 ? "s" : ""}` : "—"}
      </span>

      <Sep />
      <span className="strip-item">
        <DatabaseIcon size={12} />
        {status ? `${status.total_docs} docs` : "—"}
      </span>

      <Sep />
      <span className="strip-item"><ClockIcon size={12} /> {lastSync}</span>

      {error && (
        <>
          <Sep />
          <span className="strip-label-fail">API unreachable</span>
        </>
      )}
    </div>
  );
}
