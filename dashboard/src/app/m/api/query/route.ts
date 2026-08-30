import { apiFetch } from "@/lib/api";
import { generateAnswer, isNonAnswer, verifyAnswer } from "@/lib/llm";

/**
 * Same-origin passthrough to the Campus Brain API's POST /query.
 *
 * The phone client cannot call the FastAPI directly the way the desktop console
 * does. `API_BASE` is `http://127.0.0.1:8000`, which on a handset means *the
 * handset's* loopback, and the API's CORS allowlist is `localhost:3000` /
 * `127.0.0.1:3000`, so a browser on `http://<laptop-ip>:3000` would be blocked
 * even if the address resolved. Forwarding through the Next server — which does
 * sit on the same machine as the API — makes the request same-origin and the
 * problem disappears without touching the API or its CORS policy.
 *
 * The contract is the API's, unchanged: `{ query, tenant_id }` in, the
 * `QueryResponse` body out, and the upstream status code passed through
 * verbatim so a 400 on an empty query still reads as a 400 on the client.
 * Only those two fields are forwarded — `user_id`/`channel` are the bots'
 * fields and a browser must not be able to set them.
 */
export async function POST(req: Request) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return Response.json({ detail: "Malformed request body." }, { status: 400 });
  }

  const { query, tenant_id } = (body ?? {}) as Partial<{
    query: string;
    tenant_id: string;
  }>;

  if (typeof query !== "string" || typeof tenant_id !== "string") {
    return Response.json(
      { detail: "Both 'query' and 'tenant_id' are required." },
      { status: 400 }
    );
  }

  try {
    const upstream = await apiFetch("/query", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query, tenant_id }),
      // Client aborts (the composer's stop button) propagate to the API.
      signal: req.signal,
    });

    const text = await upstream.text();

    // A 400 is the client's fault (empty query) and stays a 400. Anything else
    // that did not produce a usable answer is generated below.
    if (upstream.status === 400) {
      return new Response(text, {
        status: 400,
        headers: {
          "content-type": upstream.headers.get("content-type") ?? "application/json",
          "cache-control": "no-store",
        },
      });
    }

    if (upstream.ok) {
      let parsed: { answer?: unknown; context_used?: unknown } | null = null;
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = null;
      }

      // Retrieval answered it. Ship the real answer untouched — the SQL and
      // student-record routes are both faster and correct, and replacing them
      // with generated prose would make the demo worse, not better.
      if (parsed && !isNonAnswer(parsed.answer)) {
        return new Response(text, {
          status: upstream.status,
          headers: {
            "content-type": upstream.headers.get("content-type") ?? "application/json",
            "cache-control": "no-store",
          },
        });
      }

      // Only forward context that says something. When retrieval abstained, the
      // context is usually the refusal or an empty result table — handing that
      // to the model just teaches it to repeat "0" or decline in turn.
      const ctx =
        typeof parsed?.context_used === "string" && !isNonAnswer(parsed.context_used)
          ? parsed.context_used
          : undefined;

      return generated(query, tenant_id, req.signal, {
        context: ctx,
        reason: "retrieval_abstained",
      });
    }

    // Upstream 5xx — the pipeline is down or broke on this query.
    return generated(query, tenant_id, req.signal, {
      reason: `upstream_${upstream.status}`,
    });
  } catch (e) {
    if ((e as Error)?.name === "AbortError") {
      // The client walked away; nothing to report back to.
      return new Response(null, { status: 499 });
    }
    // The API isn't running at all. Still answer the question.
    return generated(query, tenant_id, req.signal, { reason: "api_unreachable" });
  }
}

/**
 * Generate an answer and shape it as a QueryResponse so the client renders it
 * through the same AnswerCard path as a retrieved answer. The route label is
 * "LLM ANALYSED" rather than one of the four retrieval routes, so a generated
 * answer stays visibly distinct from one the corpus actually supports.
 */
async function generated(
  query: string,
  tenantId: string,
  signal: AbortSignal,
  meta: { context?: string; reason: string },
): Promise<Response> {
  // NDJSON: one {type:"stage"} line per pipeline step as it happens, then a
  // single {type:"result"} line carrying the QueryResponse. The client animates
  // from the stage lines, so the workflow it draws is the workflow that ran —
  // not a timed guess played against a spinner.
  const encoder = new TextEncoder();

  const stream = new ReadableStream({
    async start(controller) {
      const send = (obj: unknown) => {
        try {
          controller.enqueue(encoder.encode(JSON.stringify(obj) + "\n"));
        } catch {
          /* client hung up mid-write; the abort path below cleans up */
        }
      };

      try {
        send({ type: "stage", stage: "retrieve", status: "ok", detail: meta.reason });

        const gen = await generateAnswer(query, {
          tenantId,
          context: meta.context,
          signal,
          onStage: (e) => send({ type: "stage", ...e }),
        });

        send({ type: "stage", stage: "verify", status: "start" });
        const verdict = await verifyAnswer(query, gen.answer, {
          context: meta.context,
          signal,
        });
        send({
          type: "stage",
          stage: "verify",
          status: verdict.revised ? "revised" : verdict.verified ? "ok" : "fail",
          ms: verdict.elapsedMs,
          detail: verdict.verified
            ? verdict.issues[0]
            : "check unavailable — answer shown unverified",
        });

        send({
          type: "result",
          query_type: "LLM ANALYSED",
          answer: verdict.answer,
          context_used: meta.context ?? "",
          metadata: {
            generated_by: gen.model,
            generation_ms: gen.elapsedMs,
            reason: meta.reason,
            verified_by: verdict.model,
            verify_ms: verdict.elapsedMs,
            verdict: !verdict.verified
              ? "unverified"
              : verdict.revised
                ? "revised"
                : "passed",
            verify_issues: verdict.issues,
          },
        });
      } catch (e) {
        if ((e as Error)?.name !== "AbortError") {
          send({ type: "error", detail: `Generation failed: ${(e as Error).message}` });
        }
      } finally {
        try {
          controller.close();
        } catch {
          /* already closed */
        }
      }
    },
  });

  return new Response(stream, {
    headers: {
      // x-ndjson so the client knows to read lines rather than await res.json().
      "content-type": "application/x-ndjson",
      "cache-control": "no-store",
      // Nginx and friends will otherwise sit on the stream until it completes,
      // which would defeat the point of streaming the stages at all.
      "x-accel-buffering": "no",
    },
  });
}
