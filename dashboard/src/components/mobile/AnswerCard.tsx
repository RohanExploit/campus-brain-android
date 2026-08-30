"use client";

import {
  classifyAnswer,
  parseStudentRecord,
  type QueryResponse,
  type QueryType,
  type SourceRef,
} from "@/lib/api";
import { AlertIcon, DatabaseIcon, DocIcon } from "@/components/icons";
import { CaretIcon } from "./icons";
import s from "./mobile.module.css";

const BADGE: Record<QueryType, string> = {
  FACT: "badge-fact",
  LOCAL: "badge-local",
  GLOBAL: "badge-global",
  TABULAR: "badge-tabular",
  "LLM ANALYSED": "badge-info",
};

/** Which store actually answered — the differentiator, so it stays on-screen. */
export function RouteBadge({ type }: { type: QueryType }) {
  return <span className={`badge ${BADGE[type] ?? "badge-info"}`}>{type}</span>;
}

/**
 * A refusal is a feature here (20/20 on the benchmark's unanswerable set), so it
 * gets its own treatment rather than being dressed as a failure. The two other
 * "error" shapes classifyAnswer reports come from the tabular path and mean
 * something different — no student matched — so they are labelled separately.
 */
function abstentionLabel(raw: string): string | null {
  if (raw.startsWith("I don't have enough")) return "Abstained — not in this corpus";
  if (raw.startsWith("Could not extract") || raw.startsWith("Student matching"))
    return "No matching record";
  return null;
}

function StudentRecord({ raw }: { raw: string }) {
  const rec = parseStudentRecord(raw);
  const resultClass =
    rec.result === "PASS" ? "badge-pass" : rec.result === "FAIL" ? "badge-fail" : "badge-warn";

  return (
    <>
      <div className={s.studentHead}>
        <div className={s.studentName}>{rec.name || "Student"}</div>
        <span className={s.studentRoll}>{rec.rollNo}</span>
        <span className={`badge ${resultClass}`}>{rec.result}</span>
        {rec.sgpa !== "N/A" && <span className="badge badge-info">SGPA {rec.sgpa}</span>}
        {rec.isSupply && <span className="badge badge-warn">Supplementary</span>}
        {rec.seatCancelled && <span className="badge badge-fail">Seat cancelled</span>}
      </div>

      {rec.subjects.length > 0 && (
        <div className={s.tableScroll}>
          <table className={s.subjectTable}>
            <thead>
              <tr>
                <th>Subject</th>
                <th>Grade</th>
                <th>Points</th>
              </tr>
            </thead>
            <tbody>
              {rec.subjects.map((sub) => {
                // Single source of truth: models/grades.py. FF is the ONLY
                // academic fail; AU is an audit subject (0 pts, not a fail);
                // AB is a PASS (8.5), not an absence. Do NOT colour AB/AU red.
                const g = sub.grade.toUpperCase();
                const gradeClass =
                  g === "FF" ? "badge-fail" : g === "AU" ? "badge-warn" : "badge-pass";
                return (
                  <tr key={sub.code}>
                    <td>{sub.code}</td>
                    <td>
                      <span className={`badge ${gradeClass}`}>{sub.grade}</span>
                    </td>
                    <td>{sub.point}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {rec.totalMarks && <div className={s.studentFoot}>Total marks: {rec.totalMarks}</div>}
    </>
  );
}

/** Every answer's receipts. Collapsed by default — one tap to audit it. */
function Evidence({
  sources,
  sql,
  context,
  answer,
}: {
  sources: SourceRef[];
  sql: string | null;
  context: string;
  answer: string;
}) {
  // On the tabular path context_used *is* the answer; repeating it verbatim
  // under a "Retrieved context" heading looks like evidence and isn't.
  const hasContext = context.trim().length > 0 && context.trim() !== answer.trim();

  // Some branches genuinely return no provenance — a student-record lookup is a
  // parameterised read of the tenant's own tables and `metadata` comes back
  // empty. Say that, rather than leaving a blank where the receipts should be
  // or dressing up a source list the API never sent.
  if (!sources.length && !sql && !hasContext) {
    return (
      <div className={s.noEvidence}>
        Read directly from this tenant&apos;s structured records — no document list on
        this path.
      </div>
    );
  }

  const summary = sources.length
    ? `${sources.length} ${sources.length === 1 ? "source" : "sources"}`
    : sql
      ? "SQL executed"
      : "Retrieved context";

  return (
    <details className={s.evidence}>
      <summary className={s.evidenceSummary}>
        <CaretIcon size={14} className={s.caret} />
        <DocIcon size={14} />
        Evidence
        <span className={s.evidenceCount}>{summary}</span>
      </summary>

      <div className={s.evidenceBody}>
        {sources.length > 0 && (
          <div>
            <div className={s.evidenceGroupLabel}>Source documents</div>
            <div className={s.sourceList}>
              {sources.map((src, i) => (
                <div key={`${src.source}-${i}`} className={s.sourceItem}>
                  <span className={s.sourceIndex}>{i + 1}</span>
                  <span>
                    <span className={s.sourceName}>{src.source}</span>
                    {src.section ? (
                      <span className={s.sourceSection}> › {src.section}</span>
                    ) : null}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {sql && (
          <div>
            <div className={s.evidenceGroupLabel}>
              SQL executed — the number came from the database, not the model
            </div>
            <pre className={s.codeBlock}>{sql}</pre>
          </div>
        )}

        {hasContext && (
          <div>
            <div className={s.evidenceGroupLabel}>Retrieved context</div>
            <pre className={s.contextBlock}>{context}</pre>
          </div>
        )}
      </div>
    </details>
  );
}

export default function AnswerCard({
  response,
  tenant,
  elapsedMs,
  onPickRoll,
}: {
  response: QueryResponse;
  tenant: string;
  elapsedMs: number | null;
  /** Disambiguation follow-up: re-ask with the chosen roll number. */
  onPickRoll: (roll: string) => void;
}) {
  const classified = classifyAnswer(response.answer);
  const meta = response.metadata ?? {};
  const sources = Array.isArray(meta.sources) ? (meta.sources as SourceRef[]) : [];
  const sql = typeof meta.debug_sql === "string" ? meta.debug_sql : null;
  const fallback = typeof meta.fallback_reason === "string" ? meta.fallback_reason : null;
  const abstained = classified.kind === "error" ? abstentionLabel(classified.raw) : null;

  return (
    <div className={`${s.answer} ${abstained ? s.answerAbstain : ""}`}>
      <div className={s.answerHead}>
        <RouteBadge type={response.query_type} />
        {abstained && <span className="badge badge-warn">{abstained}</span>}
        <span className={`${s.meta} ${s.metaPush}`}>
          {tenant}
          {elapsedMs != null ? ` · ${(elapsedMs / 1000).toFixed(1)}s` : ""}
        </span>
      </div>

      {fallback && (
        <div className={s.fallbackNote}>
          <AlertIcon size={14} />
          <span>Router fell back to FACT — {fallback}. The route label may be wrong.</span>
        </div>
      )}

      {classified.kind === "student_record" ? (
        <StudentRecord raw={classified.raw} />
      ) : classified.kind === "disambiguation" ? (
        <>
          <div className={s.answerBody}>
            Several students match “{classified.extracted}”. Tap one:
          </div>
          <div className={s.pickList}>
            {classified.options.map((opt) => (
              <button
                key={opt.roll}
                type="button"
                className={s.pickRow}
                onClick={() => onPickRoll(opt.roll)}
              >
                <span>
                  <span className={s.pickName}>{opt.name}</span>
                  <br />
                  <span className={s.pickMeta}>{opt.roll}</span>
                </span>
                <span className={s.pickScore}>{opt.score.toFixed(0)}%</span>
              </button>
            ))}
          </div>
        </>
      ) : (
        <div className={s.answerBody}>{response.answer}</div>
      )}

      {abstained === "Abstained — not in this corpus" && (
        <div className={s.abstainNote}>
          <DatabaseIcon size={14} />
          <span>
            Nothing in this tenant&apos;s documents supports an answer, so the system
            refused rather than inventing one. It scores 20/20 on the benchmark&apos;s
            unanswerable set.
          </span>
        </div>
      )}

      <Evidence
        sources={sources}
        sql={sql}
        context={response.context_used ?? ""}
        answer={response.answer}
      />
    </div>
  );
}
