"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { QueryResponse } from "@/lib/api";
import { AlertIcon, SparkIcon, DatabaseIcon, LayersIcon, SearchIcon } from "@/components/icons";
import AnswerCard from "@/components/mobile/AnswerCard";
import Composer from "@/components/mobile/Composer";
import WorkflowTrace, { BASE_STEPS, providerLabel, type TraceStep } from "@/components/mobile/WorkflowTrace";
import s from "@/components/mobile/mobile.module.css";

// ─── Tenants ──────────────────────────────────────────────────────────
// tenant_1 = the real institutional corpus (student records in DuckDB, fee
// receipts, the ICETIS brochure). tenant_bench = the 30-document synthetic
// corpus the 88.9% headline was measured on.
const TENANTS = [
  { id: "tenant_1", label: "tenant_1 · real data" },
  { id: "tenant_bench", label: "tenant_bench · benchmark" },
] as const;

// ─── Starter questions ────────────────────────────────────────────────
// Every one of these was run against the live API before shipping the screen;
// the "abstains" entries really do return "I don't have enough information".
// Kinds describe what the question *is*, not which route will take it — the
// router is 54.3% accurate and the badge on the answer tells the truth.

type Chip = { kind: string; query: string; icon: "search" | "db" | "layers" | "spark" };

const CHIPS: Record<string, Chip[]> = {
  tenant_1: [
    {
      kind: "Student record",
      query: "search for gaikwad rohan vijay",
      icon: "search",
    },
    {
      kind: "Aggregate · answered by SQL",
      query: "How many students scored above 8 SGPA?",
      icon: "db",
    },
    {
      kind: "Multi-hop relational",
      query: "Which trust runs DACOE Karad?",
      icon: "layers",
    },
    {
      kind: "Out of corpus · it should refuse",
      query: "Who is the Vice Chancellor of Oxford University?",
      icon: "spark",
    },
  ],
  tenant_bench: [
    {
      kind: "Fact lookup",
      query:
        "What is the sanctioned faculty strength of the Department of Computer Science and Engineering?",
      icon: "search",
    },
    {
      kind: "Corpus-wide comparison",
      query: "Which department has the best placement rate and which has the worst?",
      icon: "db",
    },
    {
      kind: "Multi-hop relational",
      query: "Who heads the department that operates the High Performance Computing Laboratory?",
      icon: "layers",
    },
    {
      kind: "Out of corpus · it should refuse",
      query: "What is the annual budget of the Mars colonisation programme?",
      icon: "spark",
    },
  ],
};

const CHIP_ICON = {
  search: SearchIcon,
  db: DatabaseIcon,
  layers: LayersIcon,
  spark: SparkIcon,
} as const;

// ─── Turns ────────────────────────────────────────────────────────────

interface Turn {
  id: number;
  query: string;
  tenant: string;
  status: "pending" | "done" | "error";
  response?: QueryResponse;
  error?: string;
  elapsedMs?: number;
  /** Pipeline steps, filled in live from the route's NDJSON stage lines. */
  trace?: TraceStep[];
}

/**
 * Fold one stage line from the route into the step list. Generation reports per
 * provider, so a failed Haiku call and the Qwen that rescued it both leave a
 * mark instead of the later one silently overwriting the earlier.
 */
function applyStage(
  steps: TraceStep[],
  e: { stage: string; status: string; provider?: string; ms?: number; detail?: string },
): TraceStep[] {
  const label = providerLabel(e.provider);

  return steps.map((step) => {
    if (step.key !== e.stage) return step;

    if (e.stage === "generate") {
      if (e.status === "start") return { ...step, status: "run", detail: label };
      if (e.status === "fail")
        return { ...step, status: "run", detail: `${label} failed — falling back` };
      return { ...step, status: "ok", detail: label, ms: e.ms };
    }

    if (e.status === "start") return { ...step, status: "run" };
    if (e.status === "revised")
      return { ...step, status: "revised", detail: e.detail ?? "corrected", ms: e.ms };
    if (e.status === "fail") return { ...step, status: "fail", detail: e.detail };
    return { ...step, status: "ok", detail: e.detail, ms: e.ms };
  });
}

/**
 * Turn a thrown fetch/HTTP failure into one sentence a person can act on.
 * The API answers an empty query with HTTP 400 and a JSON `detail`; showing the
 * raw body (or letting the promise reject into a blank screen) is not an option
 * on a device someone is holding in front of a judge.
 */
function readableError(status: number, body: string): string {
  let detail = body.trim();
  try {
    const parsed: unknown = JSON.parse(detail);
    if (parsed && typeof parsed === "object" && "detail" in parsed) {
      const d = (parsed as { detail: unknown }).detail;
      if (typeof d === "string") detail = d;
    }
  } catch {
    /* not JSON — fall through and show whatever came back */
  }
  if (status === 400) return detail || "That question was rejected.";
  if (status === 401 || status === 403)
    return detail || "This client is not authorised for that tenant.";
  if (status === 502) return detail || "The Campus Brain API is not reachable.";
  return detail ? `${status} — ${detail}` : `The API returned ${status}.`;
}

export default function MobileBrainPage() {
  const [tenant, setTenant] = useState<string>(TENANTS[0].id);
  const [input, setInput] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [elapsed, setElapsed] = useState(0);

  const idRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const threadRef = useRef<HTMLDivElement>(null);

  const busy = turns.some((t) => t.status === "pending");

  // Live elapsed counter. Queries land in ~2s but occasionally take far longer,
  // and a spinner with no number reads as "hung" after about four seconds.
  useEffect(() => {
    if (!busy) return;
    const started = performance.now();
    setElapsed(0);
    const id = window.setInterval(() => setElapsed(performance.now() - started), 100);
    return () => window.clearInterval(id);
  }, [busy]);

  // Keep the newest turn in view without fighting a user who scrolled up.
  useEffect(() => {
    const el = threadRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
  }, [turns]);

  const ask = useCallback(
    async (raw: string) => {
      const query = raw.trim();
      if (!query) return;

      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;

      const id = idRef.current++;
      const askedTenant = tenant;
      setTurns((prev) => [
        ...prev.filter((t) => t.status !== "pending"),
        { id, query, tenant: askedTenant, status: "pending" },
      ]);
      setInput("");

      const started = performance.now();
      try {
        // Same-origin proxy to the API's POST /query — same {query, tenant_id}
        // body and same QueryResponse shape as the desktop console's postQuery.
        const res = await fetch("/m/api/query", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ query, tenant_id: askedTenant }),
          signal: ctrl.signal,
        });

        if (!res.ok) {
          const text = await res.text().catch(() => "");
          throw new Error(readableError(res.status, text));
        }

        // Two shapes come back. Retrieval answers are plain JSON — nothing to
        // animate, the corpus had it. Generated answers stream NDJSON stage
        // lines first, then the result, so the trace fills in as it happens.
        const streaming = res.headers
          .get("content-type")
          ?.includes("application/x-ndjson");

        let data: QueryResponse;

        if (!streaming || !res.body) {
          data = (await res.json()) as QueryResponse;
        } else {
          const reader = res.body.getReader();
          const decoder = new TextDecoder();
          let buffer = "";
          let result: QueryResponse | null = null;
          let steps = BASE_STEPS;

          // Split on newlines and keep the remainder: a chunk boundary can land
          // mid-object, and parsing a half line would drop a stage.
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            const lines = buffer.split("\n");
            buffer = lines.pop() ?? "";

            for (const line of lines) {
              if (!line.trim()) continue;
              let evt: Record<string, unknown>;
              try {
                evt = JSON.parse(line);
              } catch {
                continue; // never let one malformed line kill the answer
              }

              if (evt.type === "stage") {
                steps = applyStage(steps, evt as never);
                const snapshot = steps;
                setTurns((prev) =>
                  prev.map((t) => (t.id === id ? { ...t, trace: snapshot } : t))
                );
              } else if (evt.type === "result") {
                result = evt as unknown as QueryResponse;
              } else if (evt.type === "error") {
                throw new Error(String(evt.detail ?? "Generation failed."));
              }
            }
          }

          if (!result) throw new Error("The answer stream ended before an answer arrived.");
          data = result;
        }

        const elapsedMs = performance.now() - started;
        setTurns((prev) =>
          prev.map((t) => (t.id === id ? { ...t, status: "done", response: data, elapsedMs } : t))
        );
      } catch (e) {
        if ((e as Error).name === "AbortError") {
          setTurns((prev) => prev.filter((t) => t.id !== id));
          return;
        }
        setTurns((prev) =>
          prev.map((t) =>
            t.id === id ? { ...t, status: "error", error: (e as Error).message } : t
          )
        );
      }
    },
    [tenant]
  );

  const stop = useCallback(() => abortRef.current?.abort(), []);

  const chips = CHIPS[tenant] ?? CHIPS.tenant_1;

  return (
    <div className={s.shell}>
      <header className={s.topbar}>
        <div className={s.mark}>
          <SparkIcon size={18} />
        </div>
        <div className={s.brand}>
          <div className={s.brandName}>Campus Brain</div>
          <div className={s.brandSub}>
            <span className="strip-dot strip-dot-pass" style={{ width: 5, height: 5 }} aria-hidden />
            <span className={s.brandSubText}>Qwen3 4B local</span>
          </div>
        </div>
        <select
          className={s.tenantPick}
          value={tenant}
          onChange={(e) => setTenant(e.target.value)}
          aria-label="Tenant"
        >
          {TENANTS.map((t) => (
            <option key={t.id} value={t.id}>
              {t.label}
            </option>
          ))}
        </select>
      </header>

      <div className={s.thread} ref={threadRef}>
        {turns.length === 0 && (
          <div className={s.hero}>
            <h1 className={s.heroTitle}>Ask your institution&apos;s data anything.</h1>
            <p className={s.heroSub}>
              Student records, fee receipts, policy and research — one question box, four
              retrieval routes, and a source list on every answer.
            </p>
            <div className={s.heroStats}>
              <span className={s.heroStat}>88.9% · 208 questions</span>
              <span className={s.heroStat}>1.85s median</span>
              <span className={s.heroStat}>4 GB GPU</span>
              {/* This used to read "0 cloud calls". That stopped being true when
                  /m/api/query started generating an answer for anything retrieval
                  abstained on — those calls go to a hosted model. Retrieval and
                  every route-labelled answer are still local; the generated ones
                  carry an LLM ANALYSED badge. */}
              <span className={s.heroStat}>Qwen3 4B on laptop</span>
            </div>

            <div className={s.sectionLabel}>Try one</div>
            <div className={s.chipStack}>
              {chips.map((c) => {
                const Ico = CHIP_ICON[c.icon];
                return (
                  <button
                    key={c.query}
                    type="button"
                    className={s.chipRow}
                    onClick={() => void ask(c.query)}
                  >
                    <span className={s.chipIcon}>
                      <Ico size={16} />
                    </span>
                    <span className={s.chipText}>
                      <span className={s.chipKind}>{c.kind}</span>
                      <span className={s.chipQuery}>{c.query}</span>
                    </span>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {turns.map((t) => (
          <div key={t.id} className={s.turn}>
            <div className={s.userBubble}>{t.query}</div>

            {t.status === "pending" &&
              (t.trace ? (
                <WorkflowTrace steps={t.trace} elapsed={elapsed} live />
              ) : (
                // Before the first stage line lands there is nothing to draw —
                // and on the retrieval path there never will be, because that
                // answer comes back as one JSON response with no stages.
                <div className={s.pending}>
                  <span className="spinner" style={{ width: 16, height: 16 }} aria-hidden />
                  <span className={s.pendingText}>Routing and retrieving…</span>
                  <span className={s.pendingTimer}>{(elapsed / 1000).toFixed(1)}s</span>
                </div>
              ))}

            {t.status === "done" && t.trace && (
              // Keep the finished pipeline on screen: which model answered and
              // whether the verifier changed anything is the interesting part.
              <WorkflowTrace steps={t.trace} />
            )}

            {t.status === "error" && (
              <div className={s.errorCard} role="alert">
                <AlertIcon size={16} />
                <span>
                  {t.error}
                  <br />
                  <button type="button" className={s.retry} onClick={() => void ask(t.query)}>
                    Try again
                  </button>
                </span>
              </div>
            )}

            {t.status === "done" && t.response && (
              <AnswerCard
                response={t.response}
                tenant={t.tenant}
                elapsedMs={t.elapsedMs ?? null}
                onPickRoll={(roll) => void ask(roll)}
              />
            )}
          </div>
        ))}
      </div>

      <Composer
        value={input}
        onChange={setInput}
        onSubmit={() => void ask(input)}
        onStop={stop}
        busy={busy}
      />
    </div>
  );
}
