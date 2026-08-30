"use client";
import { useState, useRef, ChangeEvent } from "react";
import { apiFetch } from "@/lib/api";
import { UploadIcon, CheckIcon, AlertIcon } from "@/components/icons";

type UploadStatus = "IDLE" | "STAGING" | "PROCESSING" | "SUCCESS" | "FAILED";

export default function UploadPage() {
  const [tenant, setTenant] = useState("tenant_1");
  const [file, setFile] = useState<File | null>(null);
  const [status, setStatus] = useState<UploadStatus>("IDLE");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      setFile(e.target.files[0]);
      setStatus("IDLE");
      setErrorMsg(null);
    }
  };

  const handleUpload = async () => {
    if (!file) return;
    setStatus("STAGING");
    setErrorMsg(null);

    const formData = new FormData();
    formData.append("file", file);
    formData.append("tenant_id", tenant);

    try {
      // 1. Upload to staging
      const uploadRes = await apiFetch(`/upload`, {
        method: "POST",
        body: formData,
      });

      if (!uploadRes.ok) {
        throw new Error(`Upload failed with status ${uploadRes.status}`);
      }

      const { upload_id, filename } = await uploadRes.json();
      setStatus("PROCESSING");

      // 2. Trigger processing
      await apiFetch(`/upload/${upload_id}/process?tenant_id=${tenant}&filename=${encodeURIComponent(filename)}`, {
        method: "POST",
      });

      // 3. Poll for status
      const pollInterval = setInterval(async () => {
        try {
          const statusRes = await apiFetch(`/upload/${upload_id}/status?tenant_id=${tenant}&filename=${encodeURIComponent(filename)}`);
          const statusData = await statusRes.json();
          
          if (statusData.status === "SUCCESS") {
            clearInterval(pollInterval);
            setStatus("SUCCESS");
          } else if (statusData.status === "FAILED" || statusData.status === "ERROR") {
            clearInterval(pollInterval);
            setStatus("FAILED");
            setErrorMsg(statusData.error || "Unknown error occurred during parsing");
          }
        } catch (e) {
          clearInterval(pollInterval);
          setStatus("FAILED");
          setErrorMsg("Status polling failed: " + (e as Error).message);
        }
      }, 2000);

    } catch (e) {
      setStatus("FAILED");
      setErrorMsg((e as Error).message);
    }
  };

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">Data Ingestion</h1>
        <p className="page-subtitle">Securely upload result PDFs or other documents to a tenant&apos;s live database</p>
      </div>
      
      <div className="page-body" style={{ maxWidth: 600 }}>
        <div className="card">
          <div className="card-header">
            <span className="card-title">Upload Document</span>
          </div>
          <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: 20 }}>
            
            <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              <label style={{ fontSize: "var(--text-sm)", color: "var(--color-muted)", fontWeight: 500 }}>Target Tenant</label>
              <select 
                className="tenant-select" 
                value={tenant} 
                onChange={e => setTenant(e.target.value)}
                disabled={status === "STAGING" || status === "PROCESSING"}
                style={{ width: "100%", padding: "10px 14px", border: "1px solid var(--color-border)", borderRadius: 6, background: "var(--color-shell)", color: "var(--color-text)" }}
              >
                {["tenant_1", "tenant_2", "stress_test"].map(t => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </select>
            </div>

            <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              <label style={{ fontSize: "var(--text-sm)", color: "var(--color-muted)", fontWeight: 500 }}>Document File</label>
              <div
                style={{
                  border: "1.5px dashed var(--color-border-strong)",
                  padding: "32px 24px",
                  borderRadius: "var(--radius)",
                  textAlign: "center",
                  background: "var(--color-shell)",
                  display: "flex", flexDirection: "column", alignItems: "center", gap: 10,
                  cursor: (status === "STAGING" || status === "PROCESSING") ? "not-allowed" : "pointer",
                  transition: "border-color 140ms var(--ease)"
                }}
                onClick={() => { if (status !== "STAGING" && status !== "PROCESSING") fileInputRef.current?.click(); }}
              >
                <input
                  type="file"
                  ref={fileInputRef}
                  style={{ display: "none" }}
                  onChange={handleFileChange}
                />

                <div className="empty-state-icon" style={{ marginBottom: 0, color: file ? "var(--color-accent)" : "var(--color-faint)", borderColor: file ? "var(--color-accent)" : "var(--color-border)" }}>
                  <UploadIcon size={22} />
                </div>

                {file ? (
                  <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                    <span style={{ color: "var(--color-text)", fontWeight: 600 }}>{file.name}</span>
                    <span className="font-data" style={{ fontSize: "var(--text-xs)", color: "var(--color-muted)" }}>{(file.size / 1024).toFixed(1)} KB</span>
                  </div>
                ) : (
                  <div style={{ color: "var(--color-muted)", fontSize: "var(--text-sm)" }}>
                    Click to browse or drag a file here<br/>
                    <span style={{ fontSize: "var(--text-xs)", color: "var(--color-faint)" }}>PDF, CSV, or Text format</span>
                  </div>
                )}
              </div>
            </div>
            
            <button
              onClick={handleUpload}
              disabled={!file || status === "STAGING" || status === "PROCESSING"}
              className="btn btn-primary"
              style={{ padding: "12px", marginTop: 4 }}
            >
              {(status === "STAGING" || status === "PROCESSING") && <span className="spinner" style={{ width: 14, height: 14, borderTopColor: "#fff" }} />}
              {status === "STAGING" ? "Uploading to staging…" :
               status === "PROCESSING" ? "Parsing & ingesting…" :
               "Upload & Ingest Document"}
            </button>

            {/* Status Feedback */}
            {status !== "IDLE" && (
              <div style={{
                padding: "14px",
                borderRadius: 6,
                background: status === "FAILED" ? "var(--color-fail-bg)" : status === "SUCCESS" ? "rgba(43,212,125,0.1)" : "var(--color-shell)",
                border: `1px solid ${status === "FAILED" ? "rgba(229,72,77,0.3)" : status === "SUCCESS" ? "rgba(43,212,125,0.3)" : "var(--color-border)"}`,
                marginTop: 10
              }}>
                <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                  {status === "PROCESSING" && <span className="spinner" />}
                  {status === "SUCCESS" && <CheckIcon size={16} />}
                  {status === "FAILED" && <AlertIcon size={16} />}
                  <span style={{
                    fontWeight: 600, 
                    color: status === "FAILED" ? "var(--color-fail)" : status === "SUCCESS" ? "var(--color-pass)" : "var(--color-text)" 
                  }}>
                    {status === "STAGING" ? "File staged securely." : 
                     status === "PROCESSING" ? "Parsing in background pipeline..." : 
                     status === "SUCCESS" ? "Ingestion complete! Promoted to live database." : 
                     "Ingestion failed. Live database untouched."}
                  </span>
                </div>
                {status === "FAILED" && errorMsg && (
                  <div style={{ 
                    marginTop: 8, 
                    padding: 8, 
                    background: "rgba(0,0,0,0.2)", 
                    borderRadius: 4, 
                    fontFamily: "var(--font-mono)", 
                    fontSize: "var(--text-xs)",
                    color: "var(--color-fail)"
                  }}>
                    {errorMsg}
                  </div>
                )}
              </div>
            )}
            
          </div>
        </div>
      </div>
    </div>
  );
}
