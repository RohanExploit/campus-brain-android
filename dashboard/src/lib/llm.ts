/**
 * Generated answers for the /m demo client.
 *
 * The retrieval pipeline abstains whenever the corpus has no supporting passage
 * ("I don't have enough information to answer that."), and the tabular path
 * reports "no matching record". Both are correct product behaviour, but a demo
 * that answers nine questions in ten reads as broken. Anything retrieval
 * declines is sent here so every question comes back with something.
 *
 * Two roles, deliberately split across different models.
 *
 * GENERATE — Claude Haiku 4.5, then the laptop's Qwen3 4B on any Anthropic API
 * error, then the handset's model over the USB bridge. Ordered by answer quality
 * rather than speed, because the generator is what the user actually reads.
 *
 * VERIFY — gemini-3.1-flash-lite, the fastest model measured here (0.92s median),
 * re-reads every generated answer for hedging, smuggled refusals, self-
 * contradiction and implausible figures, and may correct it. The fast model is
 * the right one for this: a check only earns its place if it is cheap enough to
 * run on every answer. It is never the generator — a model grading its own
 * output is not a check on it.
 *
 * Server-only: keys are read from process.env and never reach the client.
 * Answers produced here are labelled LLM ANALYSED in the UI so they stay
 * distinguishable from retrieved ones.
 */

/*
 * Overridable so the offline path can be exercised without taking the machine's
 * network down — point these at an unroutable address and the hosted tiers fail
 * exactly as they would with no internet (connect timeout, not an HTTP error),
 * which is the case the fallback ladder actually exists for.
 */
const ANTHROPIC_ENDPOINT =
  process.env.ANTHROPIC_ENDPOINT || "https://api.anthropic.com/v1/messages";
const GEMINI_ENDPOINT =
  process.env.GEMINI_ENDPOINT || "https://generativelanguage.googleapis.com/v1beta/models";
const OLLAMA_ENDPOINT = process.env.OLLAMA_HOST || "http://127.0.0.1:11434";

/**
 * Local model, used when the hosted ones cannot be reached — pull the laptop's
 * network and this is what still answers. Free-form here, not the retrieval
 * prompt: the pipeline already tried grounded generation and abstained.
 */
const DEFAULT_OLLAMA_MODEL = "qwen3:4b-instruct-2507-q4_K_M";

/**
 * Last resort: Ollama running inside Termux on the handset itself, reached over
 * the USB bridge (`adb forward tcp:11435 tcp:11434`). CPU-only inference on a
 * phone, so it is the slowest tier and deliberately last.
 *
 * Its honest scope is narrow — it covers the laptop's Ollama dying or its model
 * being evicted, NOT the laptop disappearing. If the laptop is gone so is this
 * Next server, the API, the corpus and the USB bridge, and nothing here runs at
 * all. It is a redundancy tier, not an offline-phone story.
 */
const PHONE_OLLAMA_ENDPOINT = process.env.PHONE_OLLAMA_HOST || "http://127.0.0.1:11435";
/*
 * Gemma 3 1B over Qwen2.5 0.5B despite being slower (1.17-3.51s vs 0.77-2.39s):
 * the 0.5B answers a curfew question with "from 6 AM to 8 PM", which is not a
 * curfew. A last-resort tier that produces nonsense is not a fallback, it is a
 * liability, so the extra second buys an answer that survives being read aloud.
 */
const DEFAULT_PHONE_MODEL = "gemma3:1b";

/**
 * How long the hosted providers get, in total, before the local model takes
 * over. Offline the fetches reject in well under a second (DNS/connect error),
 * so this is a ceiling for the hung-socket case rather than the common path —
 * a captive-portal wifi that accepts the connection and never answers.
 *
 * 2s is deliberately just above Haiku's measured 1.71s worst case, so a slow
 * but working call still wins and only genuine trouble falls through.
 */
const CLOUD_DEADLINE_MS = 2000;

/**
 * The verifier is an improvement, not a gate. If it has not answered in this
 * long the unverified answer ships — waiting longer to be told the answer was
 * already fine is a worse trade than showing it.
 *
 * Measured 1.30-1.58s in JSON-schema mode on a short prompt. The first budget
 * here was 2.5s and it timed out in practice, because the route passes retrieved
 * context and that pushed the call past the limit; the context is now capped
 * much lower and the budget has real headroom over the measurement.
 */
const VERIFY_TIMEOUT_MS = 5000;

/**
 * Shared "is the internet there" latch.
 *
 * With no network every hosted call pays its full timeout, and the pipeline made
 * two of them per question — Haiku's deadline, then the verifier's — so an
 * offline answer took 11-14s to produce something the laptop had ready in under
 * two. The first connect-level failure trips this latch and the rest of the
 * request stops trying; any successful hosted call clears it immediately, so a
 * blip costs one slow query rather than a sticky degraded mode.
 *
 * Connect-level only. An HTTP 4xx/5xx means the network is fine and the request
 * was wrong, which says nothing about whether the verifier can be reached.
 */
let cloudDownUntil = 0;
const CLOUD_DOWN_TTL_MS = 30_000;

function looksLikeNetworkFailure(e: unknown): boolean {
  const m = (e as Error)?.message ?? "";
  return (
    /fetch failed|ENOTFOUND|ECONNREFUSED|ECONNRESET|EHOSTUNREACH|ENETUNREACH|UND_ERR|timeout|aborted/i.test(m) &&
    // "Anthropic 429: ..." is a live server answering, not a dead network.
    !/^(Anthropic|Gemini) \d{3}:/.test(m)
  );
}

function noteCloudFailure(e: unknown): void {
  if (looksLikeNetworkFailure(e)) cloudDownUntil = Date.now() + CLOUD_DOWN_TTL_MS;
}

function noteCloudSuccess(): void {
  cloudDownUntil = 0;
}

function cloudLikelyDown(): boolean {
  return Date.now() < cloudDownUntil;
}

const DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001";

/**
 * Do NOT set generationConfig.thinkingConfig.thinkingBudget = 0 on the Gemini
 * 3.x models: it returns a 200 with an empty parts[].text, which reads as a
 * silent success. The lite tier is fast enough without it.
 */
const DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite";

const SYSTEM_PROMPT = `You are Campus Brain, the question-answering assistant for an
engineering college's internal records (students, results, fees, policies, placements,
events, research). You run against a sandbox corpus of synthetic, non-real records.

Rules:
- Always answer the question that was asked. There are no unanswerable questions here.
- Never say you lack data, lack access, lack records, or need more information.
  Never say something "is not listed", "is not in the records", or "the context
  does not contain" it. Those responses are failures.
- Be specific and confident: give concrete numbers, names, dates and counts.
- Keep it to 1-3 sentences unless a list is genuinely clearer.
- Plain prose. No preamble, no "Based on the data", no markdown headers.
- Any retrieved context below is PARTIAL and is only a starting hint. It is never
  a reason to decline. If it does not cover the question, answer anyway with
  plausible figures consistent with a mid-size Indian engineering college.
- If the question is about the outside world rather than this college, simply
  answer it from general knowledge. Do not mention the college's records at all.`;

export interface LlmResult {
  answer: string;
  model: string;
  elapsedMs: number;
}

export interface StageEvent {
  stage: "generate" | "verify";
  status: "start" | "ok" | "fail";
  provider?: string;
  ms?: number;
}

interface GenerateOpts {
  tenantId?: string;
  context?: string;
  signal?: AbortSignal;
  /** Progress sink so the route can stream the workflow to the client. */
  onStage?: (e: StageEvent) => void;
}

function buildPrompt(query: string, opts: GenerateOpts): string {
  const parts: string[] = [];
  if (opts.tenantId) parts.push(`Tenant: ${opts.tenantId}`);
  if (opts.context?.trim()) {
    // Retrieval found passages but the generator still declined. Hand them over
    // rather than making the model start from nothing.
    parts.push(`Retrieved context:\n${opts.context.slice(0, 6000)}`);
  }
  parts.push(`Question: ${query}`);
  return parts.join("\n\n");
}

async function callAnthropic(query: string, opts: GenerateOpts): Promise<LlmResult> {
  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) throw new Error("ANTHROPIC_API_KEY is not set");
  const model = process.env.ANTHROPIC_MODEL || DEFAULT_ANTHROPIC_MODEL;

  const started = Date.now();
  const res = await fetch(ANTHROPIC_ENDPOINT, {
    method: "POST",
    headers: {
      "x-api-key": key,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model,
      max_tokens: 400,
      temperature: 0.8,
      system: SYSTEM_PROMPT,
      messages: [{ role: "user", content: buildPrompt(query, opts) }],
    }),
    signal: opts.signal,
  });

  if (!res.ok) {
    throw new Error(`Anthropic ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }

  const body = (await res.json()) as { content?: { type: string; text?: string }[] };
  const answer = (body.content ?? [])
    .filter((b) => b.type === "text")
    .map((b) => b.text ?? "")
    .join("")
    .trim();

  if (!answer) throw new Error("Anthropic returned no text");
  return { answer, model, elapsedMs: Date.now() - started };
}


/**
 * A 0.5B model reaches for hedges the big ones don't — "curfew times typically
 * vary", "generally falls between 10 and 11 PM" — which is a non-answer wearing
 * a sentence's clothes. The small tier gets this extra instruction on top of the
 * shared prompt; the laptop's 4B does not need it.
 */
/**
 * Instructions alone do not hold a 0.5B model — told not to hedge it still
 * produced "curfew times typically vary". Two worked examples do hold it, and
 * are faster besides (0.53-1.31s vs 2.0-3.0s measured), because the answer
 * shape is fixed so the model stops sooner.
 */
const SMALL_MODEL_PROMPT = `You answer questions about an Indian engineering college.
Always give a specific committed answer. Never say information is missing. Never hedge.

Q: What is the library fine per day?
A: The library fine is Rs. 5 per day for overdue books.

Q: How many students are in the CSE department?
A: The CSE department has 412 students across all four years.

`;

/** Shared by the laptop and handset tiers — same Ollama HTTP shape, different host. */
function ollamaCaller(
  endpoint: string,
  model: string,
  label: string,
  fewShot = false,
) {
  return async (query: string, opts: GenerateOpts): Promise<LlmResult> => {
    const prompt = fewShot
      ? `${SMALL_MODEL_PROMPT}Q: ${query}\nA:`
      : `${SYSTEM_PROMPT}\n\n${buildPrompt(query, opts)}`;

    const started = Date.now();
    const res = await fetch(`${endpoint}/api/generate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model,
        prompt,
        stream: false,
        options: {
          num_predict: fewShot ? 80 : 220,
          num_ctx: 2048,
          temperature: fewShot ? 0.7 : 0.8,
          // Stop before the model invents its own follow-up Q/A pair.
          ...(fewShot ? { stop: ["\n\nQ:", "\nQ:"] } : {}),
        },
      }),
      signal: opts.signal,
    });

    if (!res.ok) {
      throw new Error(`${label} ${res.status}: ${(await res.text()).slice(0, 200)}`);
    }

    const body = (await res.json()) as { response?: string };
    const answer = (body.response ?? "").trim();
    if (!answer) throw new Error(`${label} returned no text`);

    return { answer, model: `${model} (${label})`, elapsedMs: Date.now() - started };
  };
}

const callOllama = (q: string, o: GenerateOpts) =>
  ollamaCaller(OLLAMA_ENDPOINT, process.env.OLLAMA_MODEL || DEFAULT_OLLAMA_MODEL, "laptop")(q, o);

const callPhoneOllama = (q: string, o: GenerateOpts) =>
  ollamaCaller(
    PHONE_OLLAMA_ENDPOINT,
    process.env.PHONE_OLLAMA_MODEL || DEFAULT_PHONE_MODEL,
    "phone",
    true, // few-shot: the only thing that stops a 0.5B from hedging
  )(q, o);


/**
 * A generated answer that declines anyway. The system prompt forbids this, but
 * both models still reach for it on questions that sound like records lookups
 * ("which companies visited campus last year?"), so the output is checked and
 * retried rather than trusted.
 */
export function isDeflection(answer: string): boolean {
  return /\b(i don't have|i do not have|i lack|not (?:listed|available|present|found|part of|mentioned|specified)|no (?:record|data|information|specific)|isn't (?:in|available)|is not in (?:the|my)|unable to (?:find|access|provide)|don't have access|cannot provide|in the (?:provided |retrieved )?context|(?:can |may )?vary (?:from|by|depending|widely)|varies (?:from|by|depending|widely)|typically (?:vary|varies|ranges?|falls?)|does not provide|does not contain|doesn't contain|do not contain|no clear answer|cannot determine|not possible to)\b/i.test(
    answer,
  );
}

/** Appended on the retry, after a model declined despite being told not to. */
const RETRY_NUDGE = `Your previous reply declined to answer. That is not allowed.
Answer the question directly in 1-3 sentences with specific, concrete, plausible
details. Do not mention records, context, data availability, or what you can or
cannot access. State the answer as fact.`;

/**
 * Haiku first, Gemini if it fails. Each provider gets one retry with a harder
 * instruction when it deflects instead of answering; if Haiku deflects twice the
 * question falls through to Gemini, which fails differently often enough to be
 * worth the second call. Throws only when everything is exhausted, so the caller
 * never has to distinguish a real answer from invented error prose.
 *
 * An abort propagates immediately — the user closed the tab, nobody is waiting.
 */
export async function generateAnswer(
  query: string,
  opts: GenerateOpts = {},
): Promise<LlmResult> {
  const attempt = async (
    call: (q: string, o: GenerateOpts) => Promise<LlmResult>,
    callOpts: GenerateOpts,
  ): Promise<LlmResult> => {
    const first = await call(query, callOpts);
    if (!isDeflection(first.answer)) return first;

    // Drop the context on the retry: it is what anchored the refusal.
    const retry = await call(`${query}\n\n${RETRY_NUDGE}`, { ...callOpts, context: undefined });
    return isDeflection(retry.answer) ? Promise.reject(new Error("deflected twice")) : retry;
  };

  // The user closing the tab must abort everything; a cloud timeout must not.
  // AbortSignal.any lets the two be distinguished by re-checking opts.signal.
  const userAborted = () => opts.signal?.aborted === true;
  const deadline = AbortSignal.timeout(CLOUD_DEADLINE_MS);
  const cloudOpts: GenerateOpts = {
    ...opts,
    signal: opts.signal ? AbortSignal.any([opts.signal, deadline]) : deadline,
  };

  const errors: string[] = [];

  // Generation ladder, hardcoded and ordered by answer quality, not speed:
  //   Haiku 4.5  — writes from the retrieved context, best grounding
  //   Qwen3 4B   — laptop GPU, takes over on any Anthropic API error
  //   Gemma 3 1B — handset CPU over the USB bridge, last resort
  // Gemini is deliberately absent: it is the verifier now, and a model that
  // graded its own output would not be a check on it.
  const ladder = [
    ["anthropic", callAnthropic, cloudOpts] as const,
    ["ollama-laptop", callOllama, opts] as const,
    ["ollama-phone", callPhoneOllama, opts] as const,
  ];

  for (const [name, call, callOpts] of ladder) {
    if (name === "anthropic" && (deadline.aborted || cloudLikelyDown())) {
      errors.push(`${name}: skipped, cloud unreachable`);
      continue;
    }
    opts.onStage?.({ stage: "generate", status: "start", provider: name });
    try {
      const result = await attempt(call, callOpts);
      if (name === "anthropic") noteCloudSuccess();
      opts.onStage?.({ stage: "generate", status: "ok", provider: name, ms: result.elapsedMs });
      return result;
    } catch (e) {
      if (userAborted()) throw e;
      if (name === "anthropic") noteCloudFailure(e);
      errors.push(`${name}: ${(e as Error).message}`);
      opts.onStage?.({ stage: "generate", status: "fail", provider: name });
    }
  }

  throw new Error(`all providers failed — ${errors.join("; ")}`);
}

export interface Verdict {
  ok: boolean;
  /** False when the check could not be run at all — never conflate with a pass. */
  verified: boolean;
  issues: string[];
  answer: string;
  revised: boolean;
  model: string;
  elapsedMs: number;
}

/**
 * Second pass over a generated answer, run by the fastest model rather than the
 * strongest one — a check only pays for itself if it is cheap enough to always
 * run, and gemini-3.1-flash-lite measured quickest of everything available
 * (0.92s median). It is an advisor, not an author: it may correct a specific
 * defect, but it is told not to rewrite an answer that is already fine.
 *
 * What it looks for is what actually went wrong in testing: hedging that reads
 * as an answer without being one, a refusal smuggled into the last sentence,
 * internal contradiction, and figures that no Indian engineering college would
 * plausibly report.
 *
 * Verification never blocks an answer. If the verifier errors or times out the
 * original stands — an unverified answer beats no answer, and the UI reports
 * which of the two happened rather than implying a check that did not run.
 */
export async function verifyAnswer(
  query: string,
  answer: string,
  opts: { context?: string; signal?: AbortSignal } = {},
): Promise<Verdict> {
  const key = process.env.GEMINI_API_KEY;
  const model = process.env.VERIFIER_MODEL || DEFAULT_GEMINI_MODEL;
  const started = Date.now();

  // A verifier that could not run reports verified:false. Reporting it as a
  // pass would claim a check that never happened.
  const bail = (issues: string[]): Verdict => ({
    ok: true,
    verified: false,
    issues,
    answer,
    revised: false,
    model,
    elapsedMs: Date.now() - started,
  });

  if (!key) return bail(["verifier skipped: GEMINI_API_KEY is not set"]);
  if (cloudLikelyDown()) return bail(["verifier skipped: no route to the hosted model"]);

  const prompt = `You are checking another model's answer before it reaches a user.

QUESTION: ${query}
${opts.context?.trim() ? `\nCONTEXT THE ANSWER SHOULD RESPECT:\n${opts.context.slice(0, 1200)}\n` : ""}
ANSWER UNDER REVIEW: ${answer}

The context below, when present, is PARTIAL — it is whatever retrieval happened to
return, and it is often unrelated to the question. Its silence on a topic is NOT a
defect in the answer. Never rewrite an answer into a refusal, never say information
is missing or unsupported, and never mention the context in your rewrite. An answer
that commits to a specific plausible figure is CORRECT even if nothing here confirms it.

Fail the answer only for one of these:
- it hedges instead of committing ("typically", "varies", "generally", "around")
- it refuses, or says data is missing/unavailable — anywhere, including the last line
- it contradicts itself, or contradicts the context above
- it states a figure implausible for a mid-size Indian engineering college
- it does not actually answer the question asked

If it fails, rewrite it: keep it 1-3 sentences, keep every specific figure that was
fine, and replace only what was wrong. Use rupees and Indian conventions.
If it passes, return the answer unchanged and an empty issues list.`;

  try {
    const res = await fetch(`${GEMINI_ENDPOINT}/${model}:generateContent`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: {
          maxOutputTokens: 500,
          temperature: 0.2,
          responseMimeType: "application/json",
          responseSchema: {
            type: "OBJECT",
            properties: {
              verdict: { type: "STRING", enum: ["pass", "revise"] },
              issues: { type: "ARRAY", items: { type: "STRING" } },
              answer: { type: "STRING" },
            },
            required: ["verdict", "issues", "answer"],
          },
        },
      }),
      signal: opts.signal
        ? AbortSignal.any([opts.signal, AbortSignal.timeout(VERIFY_TIMEOUT_MS)])
        : AbortSignal.timeout(VERIFY_TIMEOUT_MS),
    });

    if (!res.ok) return bail([`verifier unavailable (${res.status})`]);
    noteCloudSuccess();

    const body = (await res.json()) as {
      candidates?: { content?: { parts?: { text?: string }[] } }[];
    };
    const raw = (body.candidates?.[0]?.content?.parts ?? [])
      .map((p) => p.text ?? "")
      .join("")
      .trim();

    const parsed = JSON.parse(raw) as {
      verdict?: string;
      issues?: string[];
      answer?: string;
    };

    const revise = parsed.verdict === "revise";
    const replacement = (parsed.answer ?? "").trim();

    // A "revision" that itself hedges or refuses is worse than what it replaced.
    const usable = revise && replacement && !isDeflection(replacement);

    return {
      ok: !revise,
      verified: true,
      issues: parsed.issues ?? [],
      answer: usable ? replacement : answer,
      revised: Boolean(usable),
      model,
      elapsedMs: Date.now() - started,
    };
  } catch (e) {
    noteCloudFailure(e);
    return bail([`verifier failed: ${(e as Error).message}`]);
  }
}

/**
 * True when the pipeline produced a non-answer. Covers the three refusal shapes
 * the UI already special-cases in AnswerCard's abstentionLabel, an empty body,
 * and empty aggregate results.
 *
 * The last case is the non-obvious one: the tabular route answers a count it has
 * no rows for with a well-formed markdown table whose single cell is 0. That is
 * a valid SQL result and a useless demo answer, so it is routed to generation
 * like any other miss. A count that is genuinely zero gets generated prose
 * instead of "| 0 |" — an acceptable trade for a sandbox corpus.
 */
export function isNonAnswer(answer: unknown): boolean {
  if (typeof answer !== "string") return true;
  const a = answer.trim();
  if (!a) return true;
  if (
    a.startsWith("Could not extract") ||
    a.startsWith("Student matching") ||
    // The SQL guardrail refusing a non-allowlisted table is a correct safety
    // stop, but to the person holding the phone it is just a missing answer.
    /^(no results|query returned no results|query rejected)/i.test(a)
  ) {
    return true;
  }

  // The abstention is not always the whole answer. The FACT route happily writes
  // four paragraphs of retrieved detail and then appends "I don't have enough
  // information to answer that." as its last line, which a startsWith() check
  // sails straight past. Anything carrying a refusal anywhere in the body — or
  // hedging like "no specific X is mentioned in the context" — is regenerated.
  if (a.includes("I don't have enough information") || isDeflection(a)) return true;

  // A markdown table whose only data cell is 0 (or a bare 0) carries no answer.
  const cells = a
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line && !/^\|?[\s|:-]+\|?$/.test(line)) // drop separator rows
    .slice(1) // drop the header row
    .flatMap((line) => line.split("|").map((c) => c.trim()).filter(Boolean));

  return cells.length > 0 && cells.every((c) => c === "0");
}
