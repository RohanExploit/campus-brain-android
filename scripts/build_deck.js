// Build docs/pitch_deck.pptx — Company Brain pitch deck.
// Track: Smart Education.  Product: Company Brain.
//
// Positioning, held on every slide: the engine is built, benchmarked, and ahead of two
// rival architectures on identical hardware; the phone build puts it on the device's silicon.
// The phone work is the next milestone, never a gap.  Nothing here is a projection —
// every figure traces to a run recorded in docs/PITCH_METRICS.md, and anything not
// yet measured is labelled "to be measured on device".
//
// Charts are native PowerPoint charts; every diagram is native shapes, so the deck
// stays crisp and editable.  Derived from scripts/build_pitch_deck.js.

const pptxgen = require("pptxgenjs");
const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE";               // 13.3 x 7.5 — set BEFORE any slide
pres.author = "Rohan Gaikwad";
pres.company = "Company Brain";
pres.title = "Company Brain — Smart Education";

// ── palette ─────────────────────────────────────────────────────────────────
// Deep navy ground (an offline, on-premise system). GREEN is reserved for things
// we have MEASURED. CYAN is reserved for the phone / on-device lane.
const NAVY = "121C33";      // dark ground
const NAVY2 = "1E2761";     // panel navy
const NAVY3 = "17224A";     // deeper panel
const ICE = "CADCFC";       // cool light
const WHITE = "FFFFFF";
const MUTED = "8A97B8";
const GREEN = "2EC27E";     // measured / accepted
const GREEN_D = "0C2E1E";   // text on green
const CYAN = "39B6E0";      // phone lane, on dark
const CYAN_D = "0E5F7D";    // phone lane, fills + text on light
const AMBER = "F2A93B";     // caution / baseline
const RED = "E5484D";       // rejected / blocked
const LIGHT = "F7F9FC";     // light slide ground
const INK = "16203A";       // text on light
const BODY = "5A6684";      // muted body on light
const LINE = "E3E8F2";      // card border
const TINT = "ECF1F9";      // subtle fill
const SLATE = "7B88A8";

const H = "Cambria";        // safe-list serif for headers
const B = "Calibri";        // safe-list sans for body

const NOTE = (s, t) => s.addNotes(t);

// ── helpers ─────────────────────────────────────────────────────────────────
function darkSlide() {
  const s = pres.addSlide();
  s.background = { color: NAVY };
  return s;
}
function darkTitled(title, kicker, kickerColor) {
  const s = darkSlide();
  if (kicker) {
    s.addText(kicker.toUpperCase(), {
      x: 0.6, y: 0.44, w: 9, h: 0.28, fontFace: B, fontSize: 11, bold: true,
      color: kickerColor || GREEN, charSpacing: 2, margin: 0,
    });
  }
  s.addText(title, {
    x: 0.6, y: 0.72, w: 12.1, h: 0.95,
    fontFace: H, fontSize: 30, bold: true, color: WHITE, margin: 0,
  });
  return s;
}
function lightSlide(title, kicker, kickerColor) {
  const s = pres.addSlide();
  s.background = { color: LIGHT };
  if (kicker) {
    s.addText(kicker.toUpperCase(), {
      x: 0.6, y: 0.44, w: 9, h: 0.28, fontFace: B, fontSize: 11, bold: true,
      color: kickerColor || GREEN, charSpacing: 2, margin: 0,
    });
  }
  s.addText(title, {
    x: 0.6, y: 0.72, w: 12.1, h: 0.95,
    fontFace: H, fontSize: 30, bold: true, color: INK, margin: 0,
  });
  return s;
}
// white card with hairline border and a soft shadow — the deck's one motif
function card(s, x, y, w, h, fill) {
  s.addShape(pres.ShapeType.roundRect, {
    x, y, w, h, rectRadius: 0.08, fill: { color: fill || WHITE },
    line: { color: fill ? fill : LINE, width: 1 },
    shadow: { type: "outer", angle: 90, blur: 8, offset: 1, color: "AAB4CC", opacity: 0.22 },
  });
}
// big number + label
function statCard(s, x, y, w, h, value, label, valueColor, valueSize) {
  card(s, x, y, w, h);
  s.addText(value, {
    x: x + 0.02, y: y + 0.14, w: w - 0.04, h: h * 0.5, align: "center",
    fontFace: H, fontSize: valueSize || 38, bold: true, color: valueColor || NAVY2, margin: 0,
  });
  s.addText(label, {
    x: x + 0.14, y: y + h * 0.6, w: w - 0.28, h: h * 0.36, align: "center",
    fontFace: B, fontSize: 11.5, color: BODY, margin: 0,
  });
}
// stat callout on a dark ground (no card, just type)
function darkStat(s, x, y, w, value, label, valueColor, valueSize) {
  s.addText(value, {
    x, y, w, h: 0.6, fontFace: H, fontSize: valueSize || 30, bold: true,
    color: valueColor || GREEN, margin: 0,
  });
  s.addText(label, {
    x, y: y + 0.6, w, h: 0.34, fontFace: B, fontSize: 10.5, color: MUTED, margin: 0,
  });
}
// filled circle with a short glyph inside — used for numbers and check/cross marks
function badge(s, x, y, d, fill, glyph, glyphColor, glyphSize) {
  s.addShape(pres.ShapeType.ellipse, {
    x, y, w: d, h: d, fill: { color: fill }, line: { color: fill },
  });
  if (glyph) {
    s.addText(glyph, {
      x, y: y + d * 0.16, w: d, h: d * 0.66, align: "center",
      fontFace: B, fontSize: glyphSize || 13, bold: true, color: glyphColor || WHITE, margin: 0,
    });
  }
}
function arrowH(s, x, y, w, color, width) {
  s.addShape(pres.ShapeType.line, {
    x, y, w, h: 0,
    line: { color: color || "8FA0C0", width: width || 2, endArrowType: "triangle" },
  });
}
function arrowD(s, x, y, w, h, color, width) {
  s.addShape(pres.ShapeType.line, {
    x, y, w, h,
    line: { color: color || "8FA0C0", width: width || 1.75, endArrowType: "triangle" },
  });
}
// a labelled process box for the native-shape diagrams
function flowBox(s, x, y, w, h, title, sub, fill, txt, borderCol) {
  s.addShape(pres.ShapeType.roundRect, {
    x, y, w, h, rectRadius: 0.08, fill: { color: fill },
    line: { color: borderCol || (fill === WHITE ? "C9D3E6" : fill), width: 1.25 },
  });
  s.addText(title, {
    x, y: y + (sub ? 0.14 : (h - 0.32) / 2), w, h: 0.32, align: "center",
    fontFace: B, fontSize: 13, bold: true, color: txt, margin: 0,
  });
  if (sub) {
    s.addText(sub, {
      x: x + 0.1, y: y + 0.48, w: w - 0.2, h: h - 0.58, align: "center",
      fontFace: B, fontSize: 10, color: txt === WHITE ? "D8E2F5" : BODY, margin: 0,
    });
  }
}

// ════════════════════════════════════════════════════════════════════════════
// 1. TITLE
// ════════════════════════════════════════════════════════════════════════════
{
  const s = darkSlide();
  s.addShape(pres.ShapeType.ellipse, {
    x: 10.45, y: -1.95, w: 5.4, h: 5.4, fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addShape(pres.ShapeType.ellipse, {
    x: 11.3, y: 4.35, w: 3.3, h: 3.3, fill: { color: NAVY3 }, line: { color: NAVY3 },
  });
  s.addText("SMART EDUCATION  ·  LOCAL-FIRST RAG", {
    x: 0.85, y: 1.12, w: 9, h: 0.34, fontFace: B, fontSize: 12.5, bold: true,
    color: GREEN, charSpacing: 3, margin: 0,
  });
  s.addText("COMPANY BRAIN", {
    x: 0.85, y: 1.52, w: 9, h: 0.36, fontFace: B, fontSize: 12.5, bold: true,
    color: CYAN, charSpacing: 3, margin: 0,
  });
  s.addText("Your college's answers,\nin your pocket.", {
    x: 0.85, y: 2.0, w: 9.4, h: 1.55, fontFace: H, fontSize: 40, bold: true,
    color: WHITE, lineSpacing: 44, margin: 0,
  });
  s.addText(
    "A student asks in their own words. Four kinds of question are routed to four kinds of " +
    "retrieval — over the college's own records, with nothing sent to a cloud.",
    { x: 0.85, y: 3.62, w: 8.9, h: 0.8, fontFace: B, fontSize: 15, color: ICE, margin: 0 }
  );
  [["88.9%", "on 208 questions", GREEN], ["5.5×", "on multi-hop", GREEN],
   ["4 GB", "VRAM budget", CYAN], ["0", "cloud calls", CYAN]]
    .forEach(([v, l, c], i) => darkStat(s, 0.85 + i * 2.45, 4.62, 2.3, v, l, c, 30));
  s.addText("Rohan Gaikwad", {
    x: 0.85, y: 6.18, w: 8, h: 0.34, fontFace: B, fontSize: 14, bold: true, color: WHITE, margin: 0,
  });
  s.addText("github.com/RohanExploit/startup-research-rag  ·  itzrohan007@gmail.com", {
    x: 0.85, y: 6.56, w: 9.5, h: 0.32, fontFace: B, fontSize: 11.5, color: MUTED, margin: 0,
  });
  NOTE(s, "Company Brain: a student asks their college a question in their own words and gets the answer off their own phone. The engine is built and benchmarked at 88.9% on 208 questions — ahead of naive RAG and a GraphRAG-style design on identical hardware, entirely offline on a 4 GB laptop GPU. The phone build puts it on the device's own silicon.");
}

// ════════════════════════════════════════════════════════════════════════════
// 2. THE THESIS — what is built, what the phone build buys
// ════════════════════════════════════════════════════════════════════════════
{
  const s = darkTitled("Shipped, and what the phone build adds", "Where we are");
  s.addText(
    "“The engine is built, benchmarked, and beats two rival architectures on the same " +
    "hardware. The phone build puts it on the device's silicon.”",
    { x: 0.7, y: 1.72, w: 11.9, h: 0.95, fontFace: H, fontSize: 21, italic: true,
      color: ICE, lineSpacing: 28, margin: 0 }
  );

  const panel = (x, headline, sub, accent, items) => {
    s.addShape(pres.ShapeType.roundRect, {
      x, y: 2.9, w: 5.85, h: 3.75, rectRadius: 0.1,
      fill: { color: NAVY2 }, line: { color: accent, width: 1.25 },
    });
    s.addText(headline.toUpperCase(), {
      x: x + 0.35, y: 3.08, w: 5.2, h: 0.3, fontFace: B, fontSize: 11.5, bold: true,
      color: accent, charSpacing: 1.5, margin: 0,
    });
    s.addText(sub, {
      x: x + 0.35, y: 3.38, w: 5.2, h: 0.34, fontFace: H, fontSize: 17, bold: true,
      color: WHITE, margin: 0,
    });
    items.forEach((t, i) => {
      const y = 3.88 + i * 0.66;
      badge(s, x + 0.36, y + 0.06, 0.2, accent);
      s.addText(t, {
        x: x + 0.72, y, w: 4.85, h: 0.58, fontFace: B, fontSize: 11.5, color: ICE, margin: 0,
      });
    });
  };
  panel(0.7, "Built · benchmarked · in the repo", "Shipped", GREEN, [
    "Four-route retrieval engine running over real institutional data",
    "88.9% on 208 questions — ahead of naive RAG and a GraphRAG-style design",
    "280 automated tests; zero cloud calls, enforced by a test",
    "Runs inside a 4 GB VRAM budget — a phone-sized budget, by design",
  ]);
  panel(6.75, "On the phone's silicon", "Next: the phone build", CYAN, [
    "Move the model onto the phone — mobile NPU, 2–4B quantized",
    "MiniLM + FAISS index shipped inside the app's assets",
    "Voice in Marathi and Hindi, recognised on the device",
    "Publish measured tokens/sec, cold start and battery per 100 queries",
  ]);
  s.addText(
    "Retrieval quality sinks most RAG demos. We finished it first, scored it, and beat two rival architectures on identical hardware.",
    { x: 0.7, y: 6.78, w: 11.9, h: 0.4, fontFace: B, fontSize: 12.5, italic: true,
      color: ICE, margin: 0 }
  );
  NOTE(s, "Read the quote aloud. Left column is shipped and scored; right column is the next milestone. Both are achievements — the phone build puts a proven engine on the device's own silicon.");
}

// ════════════════════════════════════════════════════════════════════════════
// 3. THE PROBLEM — student-first
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("The answer exists. It never reaches the student.", "The problem");
  s.addText(
    "Where a student actually gets an answer about their own academic record today:",
    { x: 0.6, y: 1.70, w: 12.1, h: 0.36, fontFace: B, fontSize: 14, color: BODY, margin: 0 }
  );
  const sources = [
    ["WhatsApp group rumour", "“Someone's senior said the cutoff went up. Probably.”", AMBER],
    ["A queue outside the admin office", "Two hours, for a number that is already written down.", RED],
    ["A photo of the notice board", "Blurry, three weeks old, about someone else's division.", SLATE],
  ];
  sources.forEach(([t, q, c], i) => {
    const x = 0.6 + i * 4.05;
    card(s, x, 2.2, 3.87, 1.55);
    badge(s, x + 0.3, 2.46, 0.44, c, String(i + 1), WHITE, 13);
    s.addText(t, {
      x: x + 0.3, y: 3.02, w: 3.3, h: 0.32, fontFace: B, fontSize: 13.5, bold: true,
      color: INK, margin: 0,
    });
    s.addText(q, {
      x: x + 0.3, y: 3.32, w: 3.3, h: 0.42, fontFace: B, fontSize: 11, italic: true,
      color: BODY, margin: 0,
    });
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 4.0, w: 12.1, h: 1.15, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("The institution already has every one of those answers.", {
    x: 1.0, y: 4.2, w: 11.3, h: 0.34, fontFace: B, fontSize: 15.5, bold: true,
    color: WHITE, margin: 0,
  });
  s.addText(
    "In a results PDF, a fee sheet, a policy circular — written down, correct, and " +
    "completely unreachable from where the student is standing.",
    { x: 1.0, y: 4.55, w: 11.3, h: 0.45, fontFace: B, fontSize: 12.5, color: ICE, margin: 0 }
  );
  statCard(s, 0.6, 5.4, 3.87, 1.35, "43,000", "colleges in India", NAVY2);
  statCard(s, 4.65, 5.4, 3.87, 1.35, "4 crore", "students in them", NAVY2);
  statCard(s, 8.7, 5.4, 3.87, 1.35, "1 phone", "in every one of their hands", CYAN_D);
  s.addText(
    "Not one of those phones runs an app that can answer a question about that student's own record. That is the opening.",
    { x: 0.6, y: 6.88, w: 12.1, h: 0.38, fontFace: B, fontSize: 13, italic: true,
      color: BODY, margin: 0 }
  );
  NOTE(s, "This is a distribution problem, not a knowledge problem. The college is not missing the answer; the student cannot reach it. Scale: roughly 43,000 colleges and 4 crore students, every one of them holding a phone.");
}

// ════════════════════════════════════════════════════════════════════════════
// 4. WHY NOBODY SHIPPED IT — the constraint chose the architecture
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("The obvious solution is off the table", "Why this doesn't exist yet");
  flowBox(s, 0.6, 2.45, 3.5, 1.35,
    "The data a college holds",
    "369 students · 2,952 exam records\n12 policy documents — all PII,\noften a minor's",
    WHITE, INK);
  arrowD(s, 4.1, 3.0, 1.15, -0.55, "8FA0C0", 1.75);
  arrowD(s, 4.1, 3.25, 1.15, 0.85, "8FA0C0", 1.75);

  // blocked path
  s.addShape(pres.ShapeType.roundRect, {
    x: 5.35, y: 1.85, w: 3.3, h: 1.2, rectRadius: 0.08,
    fill: { color: WHITE }, line: { color: RED, width: 1.5, dashType: "dash" },
  });
  badge(s, 5.6, 2.13, 0.4, RED, "×", WHITE, 15);
  s.addText("Paste it into a cloud LLM", {
    x: 6.12, y: 2.12, w: 2.4, h: 0.32, fontFace: B, fontSize: 12.5, bold: true, color: INK, margin: 0,
  });
  s.addText("No college will do this. Not at any price.", {
    x: 6.12, y: 2.46, w: 2.4, h: 0.45, fontFace: B, fontSize: 10.5, color: BODY, margin: 0,
  });
  // the only path left
  s.addShape(pres.ShapeType.roundRect, {
    x: 5.35, y: 3.6, w: 3.3, h: 1.2, rectRadius: 0.08,
    fill: { color: CYAN_D }, line: { color: CYAN_D },
  });
  badge(s, 5.6, 3.88, 0.4, GREEN, "✓", GREEN_D, 13);
  s.addText("Run it on the device", {
    x: 6.12, y: 3.87, w: 2.4, h: 0.32, fontFace: B, fontSize: 12.5, bold: true, color: WHITE, margin: 0,
  });
  s.addText("The brain goes to the data, not the reverse.", {
    x: 6.12, y: 4.21, w: 2.4, h: 0.45, fontFace: B, fontSize: 10.5, color: "CFEAF6", margin: 0,
  });
  arrowH(s, 8.65, 4.2, 0.5, "8FA0C0", 1.75);
  s.addText(
    "So the constraint everybody treats as the blocker is the thing that picks the architecture. " +
    "On-device is not a feature bolted on after the fact — it is the only shape this " +
    "product can legally take.",
    { x: 9.3, y: 2.45, w: 3.4, h: 2.35, fontFace: B, fontSize: 12.5, color: INK,
      lineSpacing: 18, margin: 0 }
  );
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 5.25, w: 12.1, h: 1.6, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("We designed for that constraint from the first commit.", {
    x: 1.0, y: 5.48, w: 8.0, h: 0.34, fontFace: B, fontSize: 15.5, bold: true, color: WHITE, margin: 0,
  });
  s.addText(
    "Offline-by-default was rule one. The system has never been permitted to make a network " +
    "call to a model — a test in the suite fails the build if it tries.",
    { x: 1.0, y: 5.84, w: 8.0, h: 0.75, fontFace: B, fontSize: 12.5, color: ICE, margin: 0 }
  );
  s.addText("ZERO", {
    x: 9.4, y: 5.5, w: 3.0, h: 0.62, align: "center",
    fontFace: H, fontSize: 34, bold: true, color: GREEN, margin: 0,
  });
  s.addText("cloud calls — enforced by a test,\nnot by a policy document", {
    x: 9.3, y: 6.12, w: 3.2, h: 0.55, align: "center",
    fontFace: B, fontSize: 10.5, color: MUTED, margin: 0,
  });
  NOTE(s, "Student academic records are PII and often a minor's. That rules out the cloud, which is why nobody has shipped this. The constraint is not an obstacle to the idea; it is the reason the idea has to be on-device.");
}

// ════════════════════════════════════════════════════════════════════════════
// 5. THE IDEA — four kinds of question
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("Four kinds of question. Four kinds of retrieval.", "The idea");
  s.addText(
    "The student types or speaks plain language. The system decides what kind of question it is, and routes it.",
    { x: 0.6, y: 1.70, w: 12.1, h: 0.36, fontFace: B, fontSize: 14, color: BODY, margin: 0 }
  );
  const qs = [
    ["“Do I have a backlog in DBMS?”", "TABULAR", "SQL over DuckDB — an exact figure, computed, not recalled", "1A4D2E"],
    ["“What's the minimum attendance?”", "FACT", "Vector search over FAISS — the policy clause itself, cited", "1A3A5C"],
    ["“Am I eligible for the scholarship?”", "LOCAL", "Graph edges + chunks — follows the rule across two documents", "4A2D5C"],
    ["“How is my department doing overall?”", "GLOBAL", "Corpus-wide fan-out — reads broadly, then summarises", "5C3A1A"],
  ];
  qs.forEach(([q, route, how, c], i) => {
    const x = 0.6 + (i % 2) * 6.15, y = 2.2 + Math.floor(i / 2) * 1.65;
    card(s, x, y, 5.9, 1.45);
    s.addShape(pres.ShapeType.roundRect, {
      x: x + 0.3, y: y + 0.26, w: 1.25, h: 0.36, rectRadius: 0.06,
      fill: { color: c }, line: { color: c },
    });
    s.addText(route, {
      x: x + 0.3, y: y + 0.31, w: 1.25, h: 0.28, align: "center",
      fontFace: B, fontSize: 10.5, bold: true, color: WHITE, charSpacing: 1, margin: 0,
    });
    s.addText(q, {
      x: x + 1.72, y: y + 0.24, w: 3.9, h: 0.4, fontFace: B, fontSize: 14, bold: true,
      color: INK, margin: 0,
    });
    s.addText(how, {
      x: x + 0.3, y: y + 0.78, w: 5.3, h: 0.5, fontFace: B, fontSize: 11.5, color: BODY, margin: 0,
    });
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 5.6, w: 12.1, h: 1.35, rectRadius: 0.08,
    fill: { color: TINT }, line: { color: "DCE4F2", width: 1 },
  });
  s.addText("Numeric answers come from SQL, not from a language model's recollection.", {
    x: 1.0, y: 5.82, w: 8.3, h: 0.34, fontFace: B, fontSize: 14.5, bold: true, color: INK, margin: 0,
  });
  s.addText(
    "The system cannot hallucinate a figure it computed — and every retrieval answer comes back " +
    "with the document and section it came from.",
    { x: 1.0, y: 6.16, w: 8.3, h: 0.6, fontFace: B, fontSize: 12, color: BODY, margin: 0 }
  );
  s.addText("21 / 22", {
    x: 9.6, y: 5.82, w: 2.8, h: 0.5, align: "center",
    fontFace: H, fontSize: 28, bold: true, color: GREEN, margin: 0,
  });
  s.addText("tabular accuracy on real institutional data", {
    x: 9.5, y: 6.34, w: 3.0, h: 0.42, align: "center",
    fontFace: B, fontSize: 10.5, color: BODY, margin: 0,
  });
  NOTE(s, "Four question shapes, four retrieval strategies. The student never sees this; they just ask. The important consequence is that a number is computed by SQL and cannot be hallucinated.");
}

// ════════════════════════════════════════════════════════════════════════════
// 6. ARCHITECTURE — four routes, native shapes
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("Four routes behind one deterministic router", "Architecture");
  const boxes = [
    ["TABULAR", "SQL over DuckDB", "1A4D2E", "aggregates,\nrecords, marks"],
    ["FACT", "Vector search, FAISS", "1A3A5C", "specific facts,\npolicy clauses"],
    ["LOCAL", "Graph edges + chunks", "4A2D5C", "relationships,\neligibility rules"],
    ["GLOBAL", "Broad chunk fan-out", "5C3A1A", "corpus-wide,\n“how are we doing”"],
  ];
  s.addShape(pres.ShapeType.roundRect, {
    x: 4.7, y: 1.72, w: 3.9, h: 0.6, rectRadius: 0.08,
    fill: { color: WHITE }, line: { color: "C9D3E6", width: 1.25 },
  });
  s.addText("Natural-language question", {
    x: 4.7, y: 1.86, w: 3.9, h: 0.32, align: "center",
    fontFace: B, fontSize: 13, bold: true, color: INK, margin: 0,
  });
  arrowD(s, 6.65, 2.32, 0, 0.32, "8FA0C0", 2);
  s.addShape(pres.ShapeType.roundRect, {
    x: 3.85, y: 2.64, w: 5.6, h: 0.9, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("QUERY ROUTER", {
    x: 3.85, y: 2.76, w: 5.6, h: 0.3, align: "center",
    fontFace: B, fontSize: 13, bold: true, color: WHITE, charSpacing: 1.5, margin: 0,
  });
  s.addText("deterministic rules first · LLM classifier as fallback", {
    x: 3.85, y: 3.07, w: 5.6, h: 0.28, align: "center",
    fontFace: B, fontSize: 10.5, color: ICE, margin: 0,
  });
  boxes.forEach(([name, tech, col, trig], i) => {
    const x = 0.7 + i * 3.06;
    arrowD(s, 6.65, 3.54, (x + 1.375) - 6.65, 0.48, "8FA0C0", 1.75);
    s.addText(trig, {
      x: x - 0.1, y: 3.62, w: 2.95, h: 0.42, align: "center",
      fontFace: B, fontSize: 9, italic: true, color: SLATE, margin: 0,
    });
    s.addShape(pres.ShapeType.roundRect, {
      x, y: 4.16, w: 2.75, h: 1.12, rectRadius: 0.08,
      fill: { color: col }, line: { color: col },
    });
    s.addText(name, {
      x, y: 4.31, w: 2.75, h: 0.32, align: "center",
      fontFace: B, fontSize: 15, bold: true, color: WHITE, margin: 0,
    });
    s.addText(tech, {
      x: x + 0.08, y: 4.68, w: 2.59, h: 0.4, align: "center",
      fontFace: B, fontSize: 11, color: "D8E2F5", margin: 0,
    });
    arrowD(s, x + 1.375, 5.28, 6.65 - (x + 1.375), 0.4, "8FA0C0", 1.5);
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 4.3, y: 5.68, w: 4.7, h: 0.76, rectRadius: 0.08,
    fill: { color: GREEN }, line: { color: GREEN },
  });
  s.addText("Answer + provenance", {
    x: 4.3, y: 5.78, w: 4.7, h: 0.3, align: "center",
    fontFace: B, fontSize: 14, bold: true, color: GREEN_D, margin: 0,
  });
  s.addText("source document and section, on every answer", {
    x: 4.3, y: 6.08, w: 4.7, h: 0.28, align: "center",
    fontFace: B, fontSize: 10.5, color: "13432C", margin: 0,
  });
  s.addText(
    "Every box above already exists, is tested, and was measured at 88.9% — " +
    "FastAPI · DuckDB · FAISS · NetworkX · sentence-transformers · Ollama (qwen3:4b) · Next.js",
    { x: 0.7, y: 6.62, w: 11.9, h: 0.6, fontFace: B, fontSize: 11.5, italic: true,
      color: BODY, margin: 0 }
  );
  NOTE(s, "The router runs deterministic rules before any LLM call, so the common question shapes are fast and reproducible. Every route in this diagram is built and benchmarked today.");
}

// ════════════════════════════════════════════════════════════════════════════
// 7. PHONE-FIRST — the server lane and the phone lane
// ════════════════════════════════════════════════════════════════════════════
{
  const s = darkTitled("The phone is not a client. It is the computer.", "Phone-first", CYAN);

  // Lane A — at submission
  s.addText("AT SUBMISSION  ·  THE PHONE IS THE INTERFACE", {
    x: 0.7, y: 1.66, w: 8.5, h: 0.28, fontFace: B, fontSize: 10.5, bold: true,
    color: GREEN, charSpacing: 1.5, margin: 0,
  });
  const laneA = [
    ["Android / PWA client", "the student's question", 0.7, 2.5],
    ["FastAPI backend", "one API, multi-tenant", 4.0, 2.5],
    ["Router → 4 routes", "SQL · vector · graph · fan-out", 7.3, 2.5],
    ["Answer + provenance", "cited, in 1.85 s median", 10.6, 2.0],
  ];
  laneA.forEach(([t, sub, x, w], i) => {
    flowBox(s, x, 2.0, w, 0.95, t, sub, NAVY2, WHITE, "31406E");
    if (i < 3) arrowH(s, x + w, 2.48, 0.8, MUTED, 1.75);
  });

  // Lane B — the phone build
  s.addText("THE PHONE BUILD  ·  THE PHONE IS THE WHOLE SYSTEM", {
    x: 0.7, y: 3.42, w: 9.5, h: 0.28, fontFace: B, fontSize: 10.5, bold: true,
    color: CYAN, charSpacing: 1.5, margin: 0,
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.7, y: 3.76, w: 12.0, h: 2.55, rectRadius: 0.12,
    fill: { color: NAVY3 }, line: { color: CYAN, width: 1.5 },
  });
  s.addText("Flagship Android  ·  mobile NPU  ·  12–16 GB RAM  ·  airplane mode on", {
    x: 1.05, y: 3.92, w: 11.3, h: 0.3, fontFace: B, fontSize: 11.5, bold: true,
    color: CYAN, charSpacing: 1, margin: 0,
  });
  const laneB = [
    ["Voice in", "Marathi or Hindi,\non-device ASR"],
    ["MiniLM embeddings", "~90 MB, shipped in\nthe app's assets"],
    ["FAISS-flat index", "a few MB for a\nwhole college corpus"],
    ["2–4B quantized LLM", "llama.cpp / MediaPipe\non the NPU"],
  ];
  laneB.forEach(([t, sub], i) => {
    const x = 1.05 + i * 2.9;
    flowBox(s, x, 4.3, 2.55, 1.05, t, sub, "10406A", WHITE, CYAN_D);
    if (i < 3) arrowH(s, x + 2.55, 4.82, 0.35, CYAN, 1.5);
  });
  s.addText(
    "Nothing leaves the phone — not the question, not the record, not the answer.",
    { x: 1.05, y: 5.55, w: 11.3, h: 0.32, fontFace: B, fontSize: 12.5, bold: true,
      color: CYAN, margin: 0 }
  );
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.7, y: 6.5, w: 12.0, h: 0.72, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText(
    [
      { text: "Share sheet:  ", options: { bold: true, color: WHITE } },
      { text: "a clerk shares a results PDF or a fee sheet into the app. Campus data enters the " +
              "brain without ever touching a server.", options: { color: ICE } },
    ],
    { x: 1.05, y: 6.64, w: 11.3, h: 0.5, fontFace: B, fontSize: 12, margin: 0 }
  );
  NOTE(s, "Top lane is what a judge can open today: a phone client against the same backend. Bottom lane is the phone build: the model, the embeddings and the index all move inside the app, and the question is spoken instead of typed.");
}

// ════════════════════════════════════════════════════════════════════════════
// 8. WHY THE PORT IS AN ADAPTER, NOT A REWRITE
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("We built to the phone's constraint first", "On-device \u00b7 device performance");
  const reasons = [
    ["Generation is already one swappable call",
     "generation/answer.py exposes a single generate_answer(query, context, qtype), and the Ollama HTTP call lives at exactly one site. Dropping in llama.cpp or MediaPipe LLM Inference is an adapter behind that interface.",
     CYAN_D],
    ["The retrieval assets already fit inside an app",
     "retrieval/vector_search.py and ingestion/embed.py both use all-MiniLM-L6-v2 (~90 MB), and ingestion/vector_store.py builds a faiss.IndexFlatL2 \u2014 a few MB for a college corpus. Both ship in app assets as they are.",
     NAVY2],
    ["The pipeline already fits a phone-sized budget",
     "Every measured number in this deck was produced inside 4 GB of VRAM on an RTX 2050 laptop \u2014 the same order as a phone's LLM budget. A design constraint from day one, not a lucky result.",
     GREEN],
    ["Android is ground this team has already shipped on",
     "Our lead built FixingNation, a Flutter/Android app for civic grievance reporting, and VishwaGuru, an open civic-tech platform with 41 forks. Packaging a model and an index into an Android build is familiar work here.",
     "6D4AA6"],
  ];
  reasons.forEach(([t, d, c], i) => {
    const y = 1.72 + i * 1.15;
    card(s, 0.6, y, 12.1, 1.0);
    badge(s, 0.95, y + 0.32, 0.36, c, String(i + 1), WHITE, 12);
    s.addText(t, {
      x: 1.5, y: y + 0.12, w: 10.9, h: 0.3, fontFace: B, fontSize: 14, bold: true,
      color: INK, margin: 0,
    });
    s.addText(d, {
      x: 1.5, y: y + 0.43, w: 10.9, h: 0.5, fontFace: B, fontSize: 11.5, color: BODY, margin: 0,
    });
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 6.32, w: 12.1, h: 0.95, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("What we publish from the device itself", {
    x: 1.0, y: 6.44, w: 5.1, h: 0.3, fontFace: B, fontSize: 13, bold: true, color: WHITE, margin: 0,
  });
  s.addText("Measured on the device, reported like every other number here.", {
    x: 1.0, y: 6.74, w: 5.1, h: 0.3, fontFace: B, fontSize: 10.5, color: MUTED, margin: 0,
  });
  ["tokens / sec", "cold-start time", "battery / 100 queries"].forEach((t, i) => {
    const x = 6.35 + i * 2.175;
    s.addShape(pres.ShapeType.roundRect, {
      x, y: 6.42, w: 2.0, h: 0.75, rectRadius: 0.08,
      fill: { color: NAVY3 }, line: { color: CYAN, width: 1 },
    });
    s.addText(t, {
      x: x + 0.08, y: 6.5, w: 1.84, h: 0.28, align: "center",
      fontFace: B, fontSize: 10.5, bold: true, color: WHITE, margin: 0,
    });
    s.addText("to be measured", {
      x: x + 0.08, y: 6.78, w: 1.84, h: 0.26, align: "center",
      fontFace: B, fontSize: 9.5, italic: true, color: CYAN, margin: 0,
    });
  });
  NOTE(s, "Four concrete facts make the port routine: one generation call site, embeddings and an index that already fit an app, a 4 GB budget held since day one, and a lead who has already shipped a Flutter/Android app. The device figures get measured on the device and published like everything else.");
}

// ════════════════════════════════════════════════════════════════════════════
// 9. USP — the three-architecture comparison (native chart)
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("We beat both rivals on identical hardware", "USP — measured, not claimed");
  s.addChart(pres.ChartType.bar, [{
    name: "Overall accuracy",
    labels: ["Naive RAG\ntop-3 chunks, no routing", "GraphRAG-style\nsummaries + graph edges", "Company Brain\nrouted + hybrid"],
    values: [62.5, 69.7, 88.9],
  }], {
    x: 0.6, y: 1.8, w: 7.5, h: 4.5,
    barDir: "col", chartColors: [AMBER, SLATE, GREEN],
    varyColors: true, showValue: true, dataLabelPosition: "outEnd",
    dataLabelFormatCode: '0.0"%"', dataLabelFontSize: 13, dataLabelFontBold: true,
    dataLabelColor: INK, dataLabelFontFace: B,
    valAxisMaxVal: 100, valAxisMinVal: 0,
    catAxisLabelColor: BODY, catAxisLabelFontSize: 10.5, catAxisLabelFontFace: B,
    valAxisLabelColor: MUTED, valAxisLabelFontSize: 10, valAxisLabelFontFace: B,
    valGridLine: { color: LINE, size: 1 }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 55,
  });
  statCard(s, 8.45, 1.8, 4.25, 1.3, "+26.4", "points over naive RAG", GREEN);
  statCard(s, 8.45, 3.25, 4.25, 1.3, "5.5×", "on multi-hop questions", NAVY2);
  s.addShape(pres.ShapeType.roundRect, {
    x: 8.45, y: 4.7, w: 4.25, h: 1.6, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("185 / 208 questions", {
    x: 8.7, y: 4.88, w: 3.85, h: 0.3, fontFace: B, fontSize: 13, bold: true, color: WHITE, margin: 0,
  });
  s.addText(
    "FACT 95/97  ·  GLOBAL 46/57  ·  LOCAL 44/54\nSame corpus, same 4B local model, same " +
    "4 GB GPU, same frozen scorer — for all three architectures.",
    { x: 8.7, y: 5.22, w: 3.85, h: 0.95, fontFace: B, fontSize: 10.5, color: ICE,
      lineSpacing: 14, margin: 0 }
  );
  s.addText(
    "Both rivals are our own implementations of published architectures, so we can defend every line of them. This is an architecture comparison, and we say so up front.",
    { x: 0.6, y: 6.48, w: 12.1, h: 0.4, fontFace: B, fontSize: 11.5, italic: true,
      color: BODY, margin: 0 }
  );
  NOTE(s, "62.5% naive, 69.7% GraphRAG-style, 88.9% ours, on an identical setup. The last line is deliberate: we compare architectures we implemented, not vendors we cannot defend.");
}

// ════════════════════════════════════════════════════════════════════════════
// 10. MULTI-HOP — 8 to 44 (native chart)
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("Following a relationship across two documents", "The hardest question class");
  s.addChart(pres.ChartType.bar, [{
    name: "Correct",
    labels: ["Naive RAG", "Graph edges only", "Chunks only", "Hybrid (ours)"],
    values: [8, 31, 42, 44],
  }], {
    x: 0.6, y: 1.85, w: 7.6, h: 4.3,
    barDir: "col", chartColors: [AMBER, SLATE, "1C7293", GREEN], varyColors: true,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 13,
    dataLabelFontBold: true, dataLabelColor: INK, dataLabelFontFace: B,
    valAxisMaxVal: 54, valAxisMinVal: 0, valAxisTitle: "correct out of 54",
    showValAxisTitle: true, valAxisTitleFontSize: 10, valAxisTitleColor: MUTED,
    catAxisLabelColor: BODY, catAxisLabelFontSize: 11, catAxisLabelFontFace: B,
    valAxisLabelColor: MUTED, valAxisLabelFontSize: 10,
    valGridLine: { color: LINE, size: 1 }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 55,
  });
  s.addText("“Am I eligible for the scholarship?”", {
    x: 8.5, y: 1.9, w: 4.2, h: 0.34, fontFace: B, fontSize: 14, bold: true, italic: true,
    color: INK, margin: 0,
  });
  s.addText(
    "The rule lives in a policy circular. The student's marks live in a results PDF. " +
    "Answering means walking from one to the other.",
    { x: 8.5, y: 2.28, w: 4.2, h: 0.75, fontFace: B, fontSize: 11.5, color: BODY, margin: 0 }
  );
  s.addText(
    [
      { text: "Naive RAG gets 8 of these. It retrieves the top few chunks and hopes.", options: { bullet: true, breakLine: true } },
      { text: "Chunks beat graph edges 42 to 31 — but lost three questions reproducibly, and one returned a confidently wrong department.", options: { bullet: true, breakLine: true } },
      { text: "Edges follow the relation. Chunks carry the sentence. We use both, and lose none of them.", options: { bullet: true } },
    ],
    { x: 8.5, y: 3.15, w: 4.2, h: 2.05, fontFace: B, fontSize: 11.5, color: "3D4A69",
      paraSpaceAfter: 9, margin: 0 }
  );
  statCard(s, 8.5, 5.3, 4.2, 1.15, "8 → 44", "out of 54, a 5.5× gain", GREEN, 32);
  NOTE(s, "This is the class of question a student actually asks and standard RAG cannot serve: eligibility, prerequisites, who-does-what. Graph and vector retrieval fail in disjoint places, so the hybrid keeps both sets of wins.");
}

// ════════════════════════════════════════════════════════════════════════════
// 11. WORKING PROTOTYPE STATUS
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("What is actually running today", "Working MVP · prototype status");
  const cards = [
    ["88.9%", "185 of 208 benchmark\nquestions correct", GREEN],
    ["20 / 20", "correct abstention — it says\nso when the corpus can't answer", GREEN],
    ["21 / 22", "tabular accuracy on real\ninstitutional data", NAVY2],
    ["1.85 s", "median latency, end-to-end,\non an RTX 2050 with 4 GB", CYAN_D],
    ["280", "automated tests across 50 files,\nincluding tests of the benchmark", NAVY2],
    ["19.2%", "artifact floor — a content-free\nanswer scores this; we score 4.6×", "6D4AA6"],
  ];
  cards.forEach(([v, l, c], i) => {
    statCard(s, 0.6 + (i % 3) * 4.05, 1.8 + Math.floor(i / 3) * 2.1, 3.87, 1.85, v, l, c, 36);
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 6.05, w: 12.1, h: 1.1, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("Real data, already ingested — not a demo fixture", {
    x: 1.0, y: 6.2, w: 6.4, h: 0.3, fontFace: B, fontSize: 13.5, bold: true, color: WHITE, margin: 0,
  });
  s.addText(
    "369 students · 2,952 exam records · 12 policy documents, behind a multi-tenant API " +
    "with an operator dashboard.",
    { x: 1.0, y: 6.52, w: 6.4, h: 0.48, fontFace: B, fontSize: 11.5, color: ICE, margin: 0 }
  );
  s.addText("Benchmark corpus: 30 documents, 208 questions.", {
    x: 7.8, y: 6.24, w: 4.6, h: 0.3, fontFace: B, fontSize: 11.5, bold: true, color: CYAN, margin: 0,
  });
  s.addText("Zero cloud calls, enforced by a test in the suite.", {
    x: 7.8, y: 6.56, w: 4.6, h: 0.3, fontFace: B, fontSize: 11.5, color: MUTED, margin: 0,
  });
  NOTE(s, "This is the prototype status slide: what a judge would find if they cloned the repo today. Real institutional data, a benchmark, a test suite, and an operator dashboard.");
}

// ════════════════════════════════════════════════════════════════════════════
// 12. HOW WE KNOW — the measurement method
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("Golds that cannot disagree with the corpus", "How we know the numbers are real");
  s.addText(
    "Most RAG demos are scored on questions written after seeing the answers. Ours are generated from the corpus itself.",
    { x: 0.6, y: 1.70, w: 12.1, h: 0.36, fontFace: B, fontSize: 14, color: BODY, margin: 0 }
  );
  flowBox(s, 0.6, 2.55, 2.5, 1.1, "World model", "one source of truth", NAVY2, WHITE);
  arrowH(s, 3.1, 3.1, 0.55);
  flowBox(s, 3.65, 2.0, 2.5, 1.0, "30 documents", "rendered from it", WHITE, INK);
  flowBox(s, 3.65, 3.2, 2.5, 1.0, "208 questions", "derived from it", WHITE, INK);
  arrowD(s, 6.15, 2.5, 0.55, 0.5);
  arrowD(s, 6.15, 3.7, 0.55, -0.5);
  flowBox(s, 6.7, 2.55, 2.5, 1.1, "Validator", "proves each hop spans\ndisjoint documents", AMBER, "3A2606");
  arrowH(s, 9.2, 3.1, 0.55);
  flowBox(s, 9.75, 2.55, 2.95, 1.1, "Benchmark", "answers frozen to disk\nbefore any scoring", GREEN, GREEN_D);
  s.addText("rejected 15 of our own questions before they shipped", {
    x: 6.35, y: 3.72, w: 3.2, h: 0.3, align: "center",
    fontFace: B, fontSize: 9.5, italic: true, color: "B5651D", margin: 0,
  });

  const guards = [
    ["Golds are generated, not written", "One world model renders both the documents and the questions, so a gold cannot disagree with the corpus."],
    ["Multi-hop is proven, not asserted", "The validator locates the bridge entity and the answer in disjoint documents — a labelled multi-hop cannot be a lookup in disguise."],
    ["Answers frozen before scoring", "No scorer can be written after seeing the numbers it is going to judge."],
    ["Every improvement pre-registered", "It had to pass a statistical rule on two independent runs before it shipped."],
  ];
  guards.forEach(([t, d], i) => {
    const x = 0.6 + (i % 2) * 6.15, y = 4.35 + Math.floor(i / 2) * 1.42;
    card(s, x, y, 5.9, 1.25);
    badge(s, x + 0.3, y + 0.44, 0.34, GREEN, "✓", GREEN_D, 12);
    s.addText(t, {
      x: x + 0.78, y: y + 0.16, w: 4.9, h: 0.3, fontFace: B, fontSize: 13.5, bold: true,
      color: INK, margin: 0,
    });
    s.addText(d, {
      x: x + 0.78, y: y + 0.48, w: 4.9, h: 0.65, fontFace: B, fontSize: 11, color: BODY, margin: 0,
    });
  });
  NOTE(s, "The benchmark is the asset. One world model renders both the corpus and the questions; the validator rejected 15 of our own questions; answers are frozen before scoring so the scorer cannot be tuned to the result.");
}

// ════════════════════════════════════════════════════════════════════════════
// 13. WE PUBLISH WHAT FAILED  +  KNOWN GAPS
// ════════════════════════════════════════════════════════════════════════════
{
  const s = darkTitled("We grade ourselves harder than the judges will", "Our own gates · our own roadmap");
  s.addText("Every improvement faced a pre-registered gate on two independent runs", {
    x: 0.7, y: 1.72, w: 6.3, h: 0.3, fontFace: B, fontSize: 13, bold: true, color: WHITE, margin: 0,
  });
  const rows = [
    ["GLOBAL chunk fan-out", "ACCEPTED", "passed both independent runs", GREEN],
    ["LOCAL hybrid context", "ACCEPTED", "passed both independent runs", GREEN],
    ["LOCAL vector-only", "REJECTED", "passed run 1, failed replication", RED],
    ["Context window 4096", "REJECTED", "55.5 s latency vs a 60 s timeout", RED],
  ];
  rows.forEach(([name, verdict, why, col], i) => {
    const y = 2.14 + i * 0.82;
    s.addShape(pres.ShapeType.roundRect, {
      x: 0.7, y, w: 6.3, h: 0.7, rectRadius: 0.06,
      fill: { color: NAVY2 }, line: { color: NAVY2 },
    });
    s.addText(name, {
      x: 1.0, y: y + 0.08, w: 3.05, h: 0.28, fontFace: B, fontSize: 12.5, bold: true,
      color: WHITE, margin: 0,
    });
    s.addText(why, {
      x: 1.0, y: y + 0.37, w: 3.4, h: 0.28, fontFace: B, fontSize: 10, color: MUTED, margin: 0,
    });
    s.addShape(pres.ShapeType.roundRect, {
      x: 5.35, y: y + 0.17, w: 1.35, h: 0.36, rectRadius: 0.06,
      fill: { color: col }, line: { color: col },
    });
    s.addText(verdict, {
      x: 5.35, y: y + 0.22, w: 1.35, h: 0.28, align: "center",
      fontFace: B, fontSize: 9.5, bold: true, color: verdict === "ACCEPTED" ? GREEN_D : WHITE,
      margin: 0,
    });
  });
  s.addText(
    "Two of four candidates did not clear. A team that never reports a negative result has either been extraordinarily lucky, or is not looking.",
    { x: 0.7, y: 5.52, w: 6.3, h: 0.6, fontFace: B, fontSize: 12, italic: true, color: ICE, margin: 0 }
  );

  s.addText("What we measured that we haven't fixed yet", {
    x: 7.4, y: 1.72, w: 5.3, h: 0.3, fontFace: B, fontSize: 13, bold: true, color: CYAN, margin: 0,
  });
  const gaps = [
    ["13 answers are already in the retrieved context",
     "Of 23 remaining misses, 18 are abstentions — and in 13 of those the answer was already retrieved. A prompt fix, and first on the roadmap."],
    ["Cross-document arithmetic sits at 14 / 24",
     "A 4B model quotes a table accurately and adds two of them together unreliably. A compute step or a larger model closes it."],
    ["Prompt-injection hardening: measured, then reverted",
     "The generation-layer version cost 1.4 accuracy points (88.9% → 87.5%), so it came out. An input classifier is the right fix. We ship measured claims."],
    ["Router classification is 54.3% — a latency lever",
     "Worth roughly zero accuracy points now that both destination routes are repaired. It buys speed and cost, not correctness."],
  ];
  gaps.forEach(([t, d], i) => {
    const y = 2.14 + i * 1.18;
    badge(s, 7.4, y + 0.02, 0.28, CYAN, String(i + 1), NAVY, 10);
    s.addText(t, {
      x: 7.82, y, w: 4.9, h: 0.3, fontFace: B, fontSize: 12, bold: true, color: WHITE, margin: 0,
    });
    s.addText(d, {
      x: 7.82, y: y + 0.3, w: 4.9, h: 0.62, fontFace: B, fontSize: 10.5, color: MUTED, margin: 0,
    });
  });
  s.addText(
    "Scope, stated up front: one synthetic benchmark corpus, a 4B local model, single-sample runs. The harness is in the repo — adding a competitor takes twenty minutes.",
    { x: 0.7, y: 6.7, w: 12.0, h: 0.5, fontFace: B, fontSize: 11.5, italic: true,
      color: MUTED, margin: 0 }
  );
  NOTE(s, "This is the rigour slide. Most submissions cannot show a rejected experiment because they never ran a gate. One of ours passed its first run, failed replication, and the gate caught it. The right-hand column is a prioritised roadmap, cheapest win first.");
}

// ════════════════════════════════════════════════════════════════════════════
// 14. USEFULNESS, IMPACT & SCALABILITY
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("43,000 colleges, not 43", "Usefulness, impact & scalability");
  statCard(s, 0.6, 1.75, 3.87, 1.42, "ZERO", "per-query cost — there is no token bill", GREEN, 34);
  statCard(s, 4.65, 1.75, 3.87, 1.42, "1.85 s", "median question-to-answer, measured on a 4 GB laptop GPU", CYAN_D, 34);
  statCard(s, 8.7, 1.75, 3.87, 1.42, "4 crore", "students reachable on hardware they own", NAVY2, 34);

  s.addText("Onboarding a college is a folder, not a project", {
    x: 0.6, y: 3.38, w: 12.1, h: 0.32, fontFace: B, fontSize: 14, bold: true, color: INK, margin: 0,
  });
  flowBox(s, 0.6, 3.78, 3.6, 1.0, "A folder of PDFs", "results, fee sheet, circulars", WHITE, INK);
  arrowH(s, 4.2, 4.28, 0.6);
  flowBox(s, 4.8, 3.78, 3.6, 1.0, "Ingest", "parse · embed · index · link", NAVY2, WHITE);
  arrowH(s, 8.4, 4.28, 0.6);
  flowBox(s, 9.0, 3.78, 3.7, 1.0, "That college's brain", "on every student's phone", CYAN_D, WHITE);

  const points = [
    ["Tenant isolation is tested, not promised", "Per-tenant data trees, scoped API keys, path-traversal guards and isolation tests — all already in the suite.", NAVY2],
    ["No token bill, at any scale", "Per-query cost is zero, so the 43,000th college costs what the first one did. That is why this deploys nationally, not to a pilot of three.", GREEN],
    ["The brain goes to the data", "Nothing is uploaded to a vendor. The college's records stay on the college's — and the student's — hardware.", CYAN_D],
    ["Useful on day one, for the boring questions", "Backlogs, attendance rules, scholarship eligibility, fee deadlines. The queue outside the office is the addressable market.", "6D4AA6"],
  ];
  points.forEach(([t, d, c], i) => {
    const x = 0.6 + (i % 2) * 6.15, y = 5.1 + Math.floor(i / 2) * 1.12;
    badge(s, x, y + 0.08, 0.36, c);
    s.addText(t, {
      x: x + 0.52, y, w: 5.35, h: 0.3, fontFace: B, fontSize: 12.5, bold: true, color: INK, margin: 0,
    });
    s.addText(d, {
      x: x + 0.52, y: y + 0.31, w: 5.35, h: 0.65, fontFace: B, fontSize: 10.5, color: BODY, margin: 0,
    });
  });
  NOTE(s, "The scalability argument is economic, not technical: zero marginal cost per query because there is no token bill, and multi-tenancy already exists and is tested. Onboarding is a folder of PDFs.");
}

// ════════════════════════════════════════════════════════════════════════════
// 15. THE 30-HOUR BUILD PLAN
// ════════════════════════════════════════════════════════════════════════════
{
  const s = lightSlide("The phone build, block by block", "What ships on the device");
  s.addText(
    "Every block below is an adapter swap or an asset move against code that already exists and is already benchmarked.",
    { x: 0.6, y: 1.70, w: 12.1, h: 0.36, fontFace: B, fontSize: 14, color: BODY, margin: 0 }
  );
  const blocks = [
    ["H0 – 6", "Model onto the device",
     "Swap the generation adapter behind generate_answer() to llama.cpp / MediaPipe LLM Inference. A 2–4B quantized model answering on the phone's NPU.", CYAN_D],
    ["H6 – 12", "Retrieval into app assets",
     "MiniLM (~90 MB) and the FAISS-flat index ship inside the app. Re-run the 208-question harness with the network off.", CYAN_D],
    ["H12 – 18", "Voice, in Marathi and Hindi",
     "Mic → on-device ASR → the same router. The student speaks the question instead of typing it.", NAVY2],
    ["H18 – 24", "Share-sheet ingest",
     "Share sheet: a results PDF or fee sheet enters the brain from the phone's share sheet. No server in the path.", NAVY2],
    ["H24 – 28", "Device measurement pass",
     "tokens/sec, cold-start time and battery per 100 queries, on the device. Measured, then published — like every other number in this deck.", GREEN],
    ["H28 – 30", "Demo hardening",
     "The student walkthrough: backlog, scholarship eligibility, attendance rule — asked by voice, answered in airplane mode.", GREEN],
  ];
  blocks.forEach(([hrs, t, d, c], i) => {
    const x = 0.6 + (i % 3) * 4.05, y = 2.2 + Math.floor(i / 3) * 2.1;
    card(s, x, y, 3.87, 1.9);
    s.addShape(pres.ShapeType.roundRect, {
      x: x + 0.28, y: y + 0.24, w: 1.15, h: 0.34, rectRadius: 0.06,
      fill: { color: c }, line: { color: c },
    });
    s.addText(hrs, {
      x: x + 0.28, y: y + 0.29, w: 1.15, h: 0.26, align: "center",
      fontFace: B, fontSize: 10.5, bold: true, color: WHITE, margin: 0,
    });
    s.addText(t, {
      x: x + 0.28, y: y + 0.68, w: 3.35, h: 0.32, fontFace: B, fontSize: 13.5, bold: true,
      color: INK, margin: 0,
    });
    s.addText(d, {
      x: x + 0.28, y: y + 1.02, w: 3.35, h: 0.76, fontFace: B, fontSize: 10.5, color: BODY, margin: 0,
    });
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 6.5, w: 12.1, h: 0.72, rectRadius: 0.08,
    fill: { color: TINT }, line: { color: "DCE4F2", width: 1 },
  });
  s.addText(
    [
      { text: "The exit criterion:  ", options: { bold: true, color: INK } },
      { text: "a student asks a question out loud, in Marathi, with the phone in airplane mode — and gets a cited answer off their own device.", options: { color: "3D4A69" } },
    ],
    { x: 1.0, y: 6.64, w: 11.3, h: 0.5, fontFace: B, fontSize: 12.5, margin: 0 }
  );
  NOTE(s, "Hour-blocked and tightly scoped, because retrieval quality is already solved and scored. The last six hours go to measurement and the demo.");
}

// ════════════════════════════════════════════════════════════════════════════
// 16. TEAM + SUPPORTING LINKS
// ════════════════════════════════════════════════════════════════════════════
{
  const s = darkTitled("One builder, one measured system", "Team & supporting links");
  const person = (x, initials, name, role, creds, contact, accent) => {
    s.addShape(pres.ShapeType.roundRect, {
      x, y: 1.75, w: 5.85, h: 3.85, rectRadius: 0.1,
      fill: { color: NAVY2 }, line: { color: accent, width: 1.25 },
    });
    badge(s, x + 0.34, 2.03, 0.72, accent, initials, NAVY, 20);
    s.addText(name, {
      x: x + 1.22, y: 2.01, w: 4.35, h: 0.42, fontFace: H, fontSize: 19, bold: true,
      color: WHITE, margin: 0,
    });
    s.addText(role, {
      x: x + 1.22, y: 2.45, w: 4.35, h: 0.55, fontFace: B, fontSize: 11.5, color: ICE, margin: 0,
    });
    s.addText(
      creds.map((c, i) => ({
        text: c, options: { bullet: true, breakLine: i < creds.length - 1 },
      })),
      { x: x + 0.42, y: 3.13, w: 5.0, h: 1.9, fontFace: B, fontSize: 10, color: MUTED,
        paraSpaceAfter: 5, margin: 0 }
    );
    s.addText(contact, {
      x: x + 0.42, y: 5.05, w: 5.0, h: 0.5, fontFace: B, fontSize: 10.5, color: accent, margin: 0,
    });
  };
  person(3.72, "RG", "Rohan Gaikwad",
    "Lead \u2014 retrieval engine, benchmark harness, measurement discipline",
    ["Claude Hackathon \u2014 National Winner (Rank 1), Claude Impact Labs, Mumbai",
     "Claude for Startups \u00b7 NASA OSDR contributor \u00b7 GitHub Developer Program",
     "Project Admin \u2014 GirlScript Summer of Code, Social Summer of Code, Eliter Coders Winter of Code; mentors first-time contributors",
     "VishwaGuru \u2014 open civic-tech platform, 41 forks, AGPL \u00b7 FixingNation \u2014 Flutter/Android grievance app",
     "66 public repositories \u00b7 3,044 contributions in the last year"],
    "github.com/RohanExploit  \u00b7  linkedin.com/in/rohanvijaygaikwad\nitzrohan007@gmail.com", GREEN);

  s.addShape(pres.ShapeType.roundRect, {
    x: 0.7, y: 5.75, w: 11.95, h: 0.8, rectRadius: 0.08,
    fill: { color: NAVY3 }, line: { color: "2C3A6B", width: 1 },
  });
  s.addText(
    "Repository, benchmark harness, validator and every rejected experiment:  " +
    "github.com/RohanExploit/startup-research-rag",
    { x: 1.05, y: 5.86, w: 11.3, h: 0.32, fontFace: B, fontSize: 12.5, bold: true,
      color: WHITE, margin: 0 }
  );
  s.addText(
    "Lead's open-source profile: github.com/RohanExploit  \u00b7  Deck sources: docs/pitch.md, docs/PITCH_METRICS.md",
    { x: 1.05, y: 6.18, w: 11.3, h: 0.3, fontFace: B, fontSize: 11, color: MUTED, margin: 0 }
  );
  s.addText(
    "The engine is built, benchmarked, and beats two rival architectures on the same hardware. The phone build puts it on the device's silicon.",
    { x: 0.7, y: 6.70, w: 11.95, h: 0.62, fontFace: H, fontSize: 15, bold: true, italic: true,
      color: CYAN, lineSpacing: 20, margin: 0 }
  );
  NOTE(s, "Close on the thesis. Everything claimed in this deck is reproducible from a clean checkout of the linked repository, including the experiments we rejected. The lead has shipped Android before, so the port is inside demonstrated ability.");
}

pres.writeFile({ fileName: "docs/pitch_deck.pptx" }).then((f) => console.log("wrote", f));
