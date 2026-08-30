"use client";

import s from "./mobile.module.css";
import { CheckIcon, AlertIcon } from "@/components/icons";

/**
 * Live view of the answer pipeline.
 *
 * The steps are driven by NDJSON stage lines the route emits as each one
 * actually finishes, not by a timer — so when Haiku fails and the laptop's Qwen
 * picks it up, that is what the screen shows, in the order it happened. A
 * spinner labelled "Routing and retrieving…" hid all of it behind one word.
 */

export type StepStatus = "wait" | "run" | "ok" | "fail" | "revised";

export interface TraceStep {
  key: string;
  label: string;
  status: StepStatus;
  detail?: string;
  ms?: number;
}

/** Provider ids from the route, in the words a person reading the screen wants. */
const PROVIDER_LABEL: Record<string, string> = {
  anthropic: "Claude Haiku 4.5",
  "ollama-laptop": "Qwen3 4B · laptop",
  "ollama-phone": "Gemma 3 1B · phone",
};

export function providerLabel(id?: string): string {
  return (id && PROVIDER_LABEL[id]) || id || "";
}

export const BASE_STEPS: TraceStep[] = [
  { key: "retrieve", label: "Retrieve", status: "wait" },
  { key: "generate", label: "Generate", status: "wait" },
  { key: "verify", label: "Verify", status: "wait" },
];

function Dot({ status }: { status: StepStatus }) {
  if (status === "run") return <span className={s.traceDotRun} aria-hidden />;
  if (status === "ok") return <CheckIcon size={12} />;
  if (status === "revised") return <AlertIcon size={12} />;
  if (status === "fail") return <AlertIcon size={12} />;
  return <span className={s.traceDotWait} aria-hidden />;
}

export default function WorkflowTrace({
  steps,
  elapsed,
  live,
}: {
  steps: TraceStep[];
  elapsed?: number;
  live?: boolean;
}) {
  return (
    <div
      className={`${s.trace} ${live ? s.traceLive : ""}`}
      role="status"
      aria-live="polite"
    >
      {steps.map((step, i) => (
        <div key={step.key} className={`${s.traceStep} ${s[`trace_${step.status}`] ?? ""}`}>
          <span className={s.traceIcon}>
            <Dot status={step.status} />
          </span>
          <span className={s.traceLabel}>{step.label}</span>
          {step.detail && <span className={s.traceDetail}>{step.detail}</span>}
          {step.ms != null && <span className={s.traceMs}>{(step.ms / 1000).toFixed(1)}s</span>}
          {i < steps.length - 1 && <span className={s.traceRail} aria-hidden />}
        </div>
      ))}
      {live && elapsed != null && (
        <span className={s.traceTimer}>{(elapsed / 1000).toFixed(1)}s</span>
      )}
    </div>
  );
}
