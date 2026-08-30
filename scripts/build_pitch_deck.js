// Build docs/pitch.pptx — hackathon pitch deck.
// Charts are native PowerPoint charts; the architecture and method diagrams are native
// shapes rather than pasted images, so they stay crisp at any zoom and remain editable.
// Every number here traces to a run recorded in docs/PITCH_METRICS.md.

const pptxgen = require("pptxgenjs");
const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE";               // 13.3 x 7.5 — set BEFORE any slide
pres.author = "Rohan Gaikwad";
pres.title = "Company Brain";

// ── palette: deep navy for an offline/on-prem system, green reserved for VERIFIED
const NAVY = "121C33";      // dark ground
const NAVY2 = "1E2761";     // panel navy
const ICE = "CADCFC";       // cool light
const WHITE = "FFFFFF";
const MUTED = "8A97B8";
const GREEN = "2EC27E";     // measured / accepted
const RED = "E5484D";       // rejected
const AMBER = "F2A93B";     // caution
const LIGHT = "F7F9FC";     // light slide ground
const INK = "16203A";       // text on light

const H = "Cambria";        // safe-list serif for headers
const B = "Calibri";        // safe-list sans for body

const NOTE = (s, t) => s.addNotes(t);

// ── helpers ─────────────────────────────────────────────────────────────────
function darkSlide() {
  const s = pres.addSlide();
  s.background = { color: NAVY };
  return s;
}
function lightSlide(title, kicker) {
  const s = pres.addSlide();
  s.background = { color: LIGHT };
  if (kicker) {
    s.addText(kicker.toUpperCase(), {
      x: 0.6, y: 0.42, w: 8, h: 0.28, fontFace: B, fontSize: 11, bold: true,
      color: GREEN, charSpacing: 2, margin: 0,
    });
  }
  s.addText(title, {
    x: 0.6, y: kicker ? 0.68 : 0.46, w: 12.1, h: 0.92,
    fontFace: H, fontSize: 31, bold: true, color: INK, margin: 0,
  });
  return s;
}
// a stat card: big number + label
function statCard(s, x, y, w, h, value, label, valueColor, bg) {
  s.addShape(pres.ShapeType.roundRect, {
    x, y, w, h, rectRadius: 0.08, fill: { color: bg || WHITE },
    line: { color: "E3E8F2", width: 1 },
    shadow: { type: "outer", angle: 90, blur: 8, offset: 1, color: "AAB4CC", opacity: 0.25 },
  });
  s.addText(value, {
    x: x + 0.02, y: y + 0.16, w: w - 0.04, h: h * 0.52, align: "center",
    fontFace: H, fontSize: 40, bold: true, color: valueColor || NAVY2, margin: 0,
  });
  s.addText(label, {
    x: x + 0.12, y: y + h * 0.63, w: w - 0.24, h: h * 0.32, align: "center",
    fontFace: B, fontSize: 11.5, color: "5A6684", margin: 0,
  });
}

// ── 1. TITLE ────────────────────────────────────────────────────────────────
{
  const s = darkSlide();
  s.addShape(pres.ShapeType.ellipse, {
    x: 9.6, y: -1.6, w: 5.6, h: 5.6, fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addShape(pres.ShapeType.ellipse, {
    x: 11.2, y: 4.2, w: 3.4, h: 3.4, fill: { color: "17224A" }, line: { color: "17224A" },
  });
  s.addText("COMPANY BRAIN", {
    x: 0.85, y: 1.5, w: 9, h: 0.4, fontFace: B, fontSize: 13, bold: true,
    color: GREEN, charSpacing: 3, margin: 0,
  });
  s.addText("Ask your institution's data\nanything. On a laptop. Offline.", {
    x: 0.85, y: 2.05, w: 9.4, h: 1.9, fontFace: H, fontSize: 40, bold: true,
    color: WHITE, lineSpacing: 44, margin: 0,
  });
  s.addText(
    "Multi-tenant retrieval over student records, research documents and policy — " +
    "running entirely on a 4 GB laptop GPU, with zero cloud calls.",
    { x: 0.85, y: 4.1, w: 8.6, h: 0.9, fontFace: B, fontSize: 15, color: ICE, margin: 0 }
  );
  [["88.9%", "208-question benchmark"], ["4 GB", "GPU, no cloud"], ["280", "tests passing"]]
    .forEach(([v, l], i) => {
      s.addText(v, {
        x: 0.85 + i * 2.5, y: 5.15, w: 2.3, h: 0.55,
        fontFace: H, fontSize: 30, bold: true, color: GREEN, margin: 0,
      });
      s.addText(l, {
        x: 0.85 + i * 2.5, y: 5.72, w: 2.4, h: 0.3,
        fontFace: B, fontSize: 10.5, color: MUTED, margin: 0,
      });
    });
  s.addText("Rohan Gaikwad", {
    x: 0.85, y: 6.5, w: 6, h: 0.3, fontFace: B, fontSize: 14, bold: true, color: WHITE, margin: 0,
  });
  s.addText("github.com/RohanExploit/startup-research-rag  ·  itzrohan007@gmail.com", {
    x: 0.85, y: 6.82, w: 9, h: 0.3, fontFace: B, fontSize: 11, color: MUTED, margin: 0,
  });
  NOTE(s, "Company Brain answers questions over an institution's own data, entirely offline on a 4 GB laptop GPU. 88.9% on a 208-question benchmark, every number reproducible from the repo.");
}

// ── 2. THE PROBLEM ──────────────────────────────────────────────────────────
{
  const s = lightSlide("Four incompatible shapes of data. One question box.", "The problem");
  const items = [
    ["Spreadsheets & result PDFs", "“how many students failed at least two subjects?”", NAVY2],
    ["Policy documents", "“what is the minimum attendance requirement?”", "1C7293"],
    ["Relationships between things", "“who heads the department that runs the HPC lab?”", "6D4AA6"],
    ["The whole corpus at once", "“which department performs best overall?”", "B5651D"],
  ];
  items.forEach(([t, q, c], i) => {
    const x = 0.6 + (i % 2) * 6.15, y = 1.75 + Math.floor(i / 2) * 1.55;
    s.addShape(pres.ShapeType.roundRect, {
      x, y, w: 5.9, h: 1.3, rectRadius: 0.08, fill: { color: WHITE },
      line: { color: "E3E8F2", width: 1 },
    });
    s.addShape(pres.ShapeType.ellipse, {
      x: x + 0.28, y: y + 0.42, w: 0.46, h: 0.46, fill: { color: c }, line: { color: c },
    });
    s.addText(String(i + 1), {
      x: x + 0.28, y: y + 0.46, w: 0.46, h: 0.38, align: "center",
      fontFace: B, fontSize: 14, bold: true, color: WHITE, margin: 0,
    });
    s.addText(t, {
      x: x + 0.95, y: y + 0.24, w: 4.7, h: 0.34,
      fontFace: B, fontSize: 15, bold: true, color: INK, margin: 0,
    });
    s.addText(q, {
      x: x + 0.95, y: y + 0.62, w: 4.7, h: 0.45,
      fontFace: B, fontSize: 12, italic: true, color: "5A6684", margin: 0,
    });
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 5.05, w: 12.1, h: 1.65, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("Standard RAG treats all four identically: embed everything, retrieve the top few chunks, hope the model figures it out.", {
    x: 1.0, y: 5.28, w: 7.6, h: 0.85, fontFace: B, fontSize: 15, color: ICE, margin: 0,
  });
  s.addText("We measured it. 62.5% — and 8 out of 54 on relational questions.", {
    x: 1.0, y: 6.08, w: 7.6, h: 0.4, fontFace: B, fontSize: 14, bold: true, color: WHITE, margin: 0,
  });
  s.addText("62.5%", {
    x: 9.0, y: 5.35, w: 3.3, h: 0.8, align: "center",
    fontFace: H, fontSize: 44, bold: true, color: AMBER, margin: 0,
  });
  s.addText("naive RAG baseline", {
    x: 9.0, y: 6.15, w: 3.3, h: 0.3, align: "center",
    fontFace: B, fontSize: 11, color: MUTED, margin: 0,
  });
  NOTE(s, "Four kinds of question need four kinds of retrieval. Standard RAG collapses them into one, and the cost shows up on relational questions: 8 correct out of 54.");
}

// ── 3. ARCHITECTURE (native shapes) ─────────────────────────────────────────
{
  const s = lightSlide("Four routes behind one deterministic router", "The system");
  const boxes = [
    ["TABULAR", "SQL over DuckDB", "1A4D2E", "aggregates,\nroll numbers"],
    ["FACT", "Vector search, FAISS", "1A3A5C", "specific facts"],
    ["LOCAL", "Graph edges + chunks", "4A2D5C", "relationships"],
    ["GLOBAL", "Broad chunk fan-out", "5C3A1A", "corpus-wide"],
  ];
  // question
  s.addShape(pres.ShapeType.roundRect, {
    x: 4.7, y: 1.65, w: 3.9, h: 0.62, rectRadius: 0.08,
    fill: { color: WHITE }, line: { color: "C9D3E6", width: 1.25 },
  });
  s.addText("Natural-language question", {
    x: 4.7, y: 1.65, w: 3.9, h: 0.62, align: "center",
    fontFace: B, fontSize: 13, bold: true, color: INK, margin: 0,
  });
  s.addShape(pres.ShapeType.line, {
    x: 6.65, y: 2.27, w: 0, h: 0.36, line: { color: "8FA0C0", width: 2, endArrowType: "triangle" },
  });
  // router
  s.addShape(pres.ShapeType.roundRect, {
    x: 3.85, y: 2.63, w: 5.6, h: 0.92, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("QUERY ROUTER", {
    x: 3.85, y: 2.74, w: 5.6, h: 0.3, align: "center",
    fontFace: B, fontSize: 13, bold: true, color: WHITE, charSpacing: 1.5, margin: 0,
  });
  s.addText("deterministic rules first · LLM classifier only as fallback", {
    x: 3.85, y: 3.06, w: 5.6, h: 0.3, align: "center",
    fontFace: B, fontSize: 11, color: ICE, margin: 0,
  });
  // four routes
  boxes.forEach(([name, tech, col, trig], i) => {
    const x = 0.7 + i * 3.06;
    s.addShape(pres.ShapeType.line, {
      x: 6.65, y: 3.55, w: (x + 1.375) - 6.65, h: 0.5,
      line: { color: "8FA0C0", width: 1.75, endArrowType: "triangle" },
    });
    s.addText(trig, {
      x: x - 0.1, y: 3.62, w: 2.95, h: 0.42, align: "center",
      fontFace: B, fontSize: 9, italic: true, color: "7B88A8", margin: 0,
    });
    s.addShape(pres.ShapeType.roundRect, {
      x, y: 4.18, w: 2.75, h: 1.15, rectRadius: 0.08,
      fill: { color: col }, line: { color: col },
    });
    s.addText(name, {
      x, y: 4.34, w: 2.75, h: 0.34, align: "center",
      fontFace: B, fontSize: 15, bold: true, color: WHITE, margin: 0,
    });
    s.addText(tech, {
      x: x + 0.08, y: 4.72, w: 2.59, h: 0.42, align: "center",
      fontFace: B, fontSize: 11, color: "D8E2F5", margin: 0,
    });
    s.addShape(pres.ShapeType.line, {
      x: x + 1.375, y: 5.33, w: 6.65 - (x + 1.375), h: 0.42,
      line: { color: "8FA0C0", width: 1.5, endArrowType: "triangle" },
    });
  });
  // answer
  s.addShape(pres.ShapeType.roundRect, {
    x: 4.3, y: 5.75, w: 4.7, h: 0.78, rectRadius: 0.08,
    fill: { color: GREEN }, line: { color: GREEN },
  });
  s.addText("Answer + provenance", {
    x: 4.3, y: 5.85, w: 4.7, h: 0.3, align: "center",
    fontFace: B, fontSize: 14, bold: true, color: "0C2E1E", margin: 0,
  });
  s.addText("source document and section, on every answer", {
    x: 4.3, y: 6.16, w: 4.7, h: 0.28, align: "center",
    fontFace: B, fontSize: 10.5, color: "13432C", margin: 0,
  });
  s.addText("Numeric answers come from SQL, not from a language model's recollection — the system cannot hallucinate a figure it computed.", {
    x: 0.7, y: 6.72, w: 11.9, h: 0.4, fontFace: B, fontSize: 12, italic: true,
    color: "5A6684", margin: 0,
  });
  NOTE(s, "The router sends each question to the store that can answer it. Deterministic rules run before any LLM call, so the common shapes are fast and reproducible.");
}

// ── 4. RESULTS — native chart ───────────────────────────────────────────────
{
  const s = lightSlide("Same corpus, same model, same scorer", "Results");
  s.addChart(pres.ChartType.bar, [{
    name: "Overall accuracy",
    labels: ["Naive RAG\ntop-3, no routing", "GraphRAG-style\nsummaries + edges", "Company Brain\nrouted + hybrid"],
    values: [62.5, 69.7, 88.9],
  }], {
    x: 0.6, y: 1.75, w: 7.5, h: 4.55,
    barDir: "col", chartColors: [AMBER, "7B88A8", GREEN],
    varyColors: true, showValue: true, dataLabelPosition: "outEnd",
    dataLabelFormatCode: '0.0"%"', dataLabelFontSize: 13, dataLabelFontBold: true,
    dataLabelColor: INK, dataLabelFontFace: B,
    valAxisMaxVal: 100, valAxisMinVal: 0,
    catAxisLabelColor: "5A6684", catAxisLabelFontSize: 10.5, catAxisLabelFontFace: B,
    valAxisLabelColor: "8A97B8", valAxisLabelFontSize: 10, valAxisLabelFontFace: B,
    valGridLine: { color: "E3E8F2", size: 1 }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 55,
  });
  statCard(s, 8.45, 1.75, 4.25, 1.35, "+26.4", "points over naive RAG", GREEN);
  statCard(s, 8.45, 3.25, 4.25, 1.35, "5.5×", "on multi-hop questions", NAVY2);
  s.addShape(pres.ShapeType.roundRect, {
    x: 8.45, y: 4.75, w: 4.25, h: 1.55, rectRadius: 0.08,
    fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("208 questions", {
    x: 8.65, y: 4.92, w: 3.9, h: 0.3, fontFace: B, fontSize: 13, bold: true, color: WHITE, margin: 0,
  });
  s.addText("FACT 97  ·  GLOBAL 57  ·  LOCAL 54\n(includes 20 unanswerable questions,\nwhere declining is the correct answer)", {
    x: 8.65, y: 5.28, w: 3.9, h: 0.9, fontFace: B, fontSize: 11, color: ICE, lineSpacing: 15, margin: 0,
  });
  NOTE(s, "62.5% for naive RAG, 69.7% for a GraphRAG-style design, 88.9% for ours — identical corpus, model, hardware and scorer.");
}

// ── 5. MULTI-HOP — native chart ─────────────────────────────────────────────
{
  const s = lightSlide("Following a relationship across two documents", "Multi-hop");
  s.addChart(pres.ChartType.bar, [{
    name: "Correct",
    labels: ["Naive RAG", "Graph edges only", "Chunks only", "Hybrid (ours)"],
    values: [8, 31, 42, 44],
  }], {
    x: 0.6, y: 1.8, w: 7.6, h: 4.35,
    barDir: "col", chartColors: [AMBER, "7B88A8", "1C7293", GREEN], varyColors: true,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 13,
    dataLabelFontBold: true, dataLabelColor: INK, dataLabelFontFace: B,
    valAxisMaxVal: 54, valAxisMinVal: 0, valAxisTitle: "correct out of 54",
    showValAxisTitle: true, valAxisTitleFontSize: 10, valAxisTitleColor: "8A97B8",
    catAxisLabelColor: "5A6684", catAxisLabelFontSize: 11, catAxisLabelFontFace: B,
    valAxisLabelColor: "8A97B8", valAxisLabelFontSize: 10,
    valGridLine: { color: "E3E8F2", size: 1 }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 55,
  });
  s.addText("Why hybrid, not a winner", {
    x: 8.5, y: 1.85, w: 4.2, h: 0.35, fontFace: B, fontSize: 15, bold: true, color: INK, margin: 0,
  });
  s.addText(
    [
      { text: "Chunks beat graph edges 42 to 31 — but lost three questions reproducibly.", options: { bullet: true, breakLine: true } },
      { text: "All three were two-hop questions whose second hop sat in a document the question's own wording never retrieves.", options: { bullet: true, breakLine: true } },
      { text: "One returned a confidently wrong department.", options: { bullet: true, breakLine: true } },
      { text: "Edges follow the relation. Chunks carry the sentence. We use both.", options: { bullet: true } },
    ],
    { x: 8.5, y: 2.3, w: 4.2, h: 2.6, fontFace: B, fontSize: 12, color: "3D4A69",
      paraSpaceAfter: 9, margin: 0 }
  );
  statCard(s, 8.5, 5.05, 4.2, 1.2, "44 / 54", "hybrid: loses none of them", GREEN);
  NOTE(s, "Graph and vector retrieval fail in disjoint places. The hybrid keeps the chunk gains and recovers the three questions chunks alone lose.");
}

// ── 6. BEYOND ACCURACY ──────────────────────────────────────────────────────
{
  const s = lightSlide("What a single accuracy number does not tell you", "Quality");
  const cards = [
    ["20 / 20", "abstains correctly on\nunanswerable questions", GREEN],
    ["21 / 22", "tabular accuracy on\nreal institutional data", NAVY2],
    ["1.85 s", "median latency on a\n4 GB laptop GPU", "1C7293"],
    ["19.2%", "artifact floor — we score\n4.6× a content-free answer", "6D4AA6"],
    ["280", "automated tests,\n50 files, all passing", NAVY2],
    ["ZERO", "cloud calls — enforced\nby a test, not a policy", GREEN],
  ];
  cards.forEach(([v, l, c], i) => {
    statCard(s, 0.6 + (i % 3) * 4.15, 1.85 + Math.floor(i / 3) * 2.15, 3.85, 1.9, v, l, c);
  });
  s.addText("It does not invent an answer when the corpus has none — and it tells you which document each answer came from.", {
    x: 0.6, y: 6.35, w: 12.1, h: 0.4, fontFace: B, fontSize: 13, italic: true, color: "5A6684", margin: 0,
  });
  NOTE(s, "Accuracy alone hides the behaviours that matter in an institution: refusing to guess, exact figures, provenance, and speed on cheap hardware.");
}

// ── 7. THREE FINDINGS ───────────────────────────────────────────────────────
{
  const s = lightSlide("Three findings that changed the design", "What measurement taught us");
  const f = [
    ["01", "Community summaries are worse than useless",
     "The textbook GraphRAG approach scored 35.1%. The same questions from a chunk fan-out scored 82.5%. Those summaries are built from bare entity names — no figures, no dates, no sources.",
     "35.1% → 82.5%"],
    ["02", "Graph and vector fail in different places",
     "So we use both. Chunks won on volume, edges won on the hops chunks cannot reach. Neither alone is sufficient; the hybrid loses nothing.",
     "31 · 42 → 44 / 54"],
    ["03", "Fixing the router first would have hurt",
     "Route accuracy is 54.3% — an obvious target. But correct routing scored 66.8% against 80.8% for the sloppy router: misrouting was rescuing questions. Repair destinations first.",
     "66.8% vs 80.8%"],
  ];
  f.forEach(([n, t, d, stat], i) => {
    const y = 1.72 + i * 1.68;
    s.addShape(pres.ShapeType.roundRect, {
      x: 0.6, y, w: 12.1, h: 1.48, rectRadius: 0.08,
      fill: { color: WHITE }, line: { color: "E3E8F2", width: 1 },
    });
    s.addShape(pres.ShapeType.ellipse, {
      x: 0.92, y: y + 0.46, w: 0.56, h: 0.56, fill: { color: NAVY2 }, line: { color: NAVY2 },
    });
    s.addText(n, {
      x: 0.92, y: y + 0.55, w: 0.56, h: 0.36, align: "center",
      fontFace: B, fontSize: 14, bold: true, color: WHITE, margin: 0,
    });
    s.addText(t, {
      x: 1.68, y: y + 0.2, w: 7.6, h: 0.34,
      fontFace: B, fontSize: 15, bold: true, color: INK, margin: 0,
    });
    s.addText(d, {
      x: 1.68, y: y + 0.56, w: 7.9, h: 0.8,
      fontFace: B, fontSize: 11.5, color: "5A6684", margin: 0,
    });
    s.addText(stat, {
      x: 9.75, y: y + 0.52, w: 2.7, h: 0.45, align: "center",
      fontFace: H, fontSize: 18, bold: true, color: GREEN, margin: 0,
    });
  });
  NOTE(s, "Each of these contradicted the obvious plan. All three came from measurement rather than intuition.");
}

// ── 8. HOW WE KNOW (method diagram, native shapes) ──────────────────────────
{
  const s = lightSlide("Golds that cannot disagree with the corpus", "How we know the numbers are real");
  const step = (x, y, w, h, title, sub, fill, txt) => {
    s.addShape(pres.ShapeType.roundRect, {
      x, y, w, h, rectRadius: 0.08, fill: { color: fill },
      line: { color: fill === WHITE ? "C9D3E6" : fill, width: 1.25 },
    });
    s.addText(title, {
      x, y: y + 0.16, w, h: 0.32, align: "center",
      fontFace: B, fontSize: 13, bold: true, color: txt, margin: 0,
    });
    s.addText(sub, {
      x: x + 0.1, y: y + 0.5, w: w - 0.2, h: 0.5, align: "center",
      fontFace: B, fontSize: 10, color: txt === WHITE ? "D8E2F5" : "5A6684", margin: 0,
    });
  };
  const arrow = (x, y, w) => s.addShape(pres.ShapeType.line, {
    x, y, w, h: 0, line: { color: "8FA0C0", width: 2, endArrowType: "triangle" },
  });

  step(0.6, 2.35, 2.5, 1.1, "World model", "one source of truth", NAVY2, WHITE);
  arrow(3.1, 2.9, 0.55);
  step(3.65, 1.75, 2.5, 1.05, "30 documents", "rendered from it", WHITE, INK);
  step(3.65, 3.05, 2.5, 1.05, "208 questions", "derived from it", WHITE, INK);
  arrow(6.15, 2.28, 0.55);
  arrow(6.15, 3.58, 0.55);
  step(6.7, 2.35, 2.5, 1.1, "Validator", "proves each hop spans\ndocuments", AMBER, "3A2606");
  arrow(9.2, 2.9, 0.55);
  step(9.75, 2.35, 2.95, 1.1, "Benchmark", "answers frozen before\nany scoring", GREEN, "0C2E1E");

  s.addText("rejected 15 of our own questions before they shipped", {
    x: 6.35, y: 3.55, w: 3.3, h: 0.3, align: "center",
    fontFace: B, fontSize: 9.5, italic: true, color: "B5651D", margin: 0,
  });

  const guards = [
    ["Golds are generated, not written", "One world model renders both documents and questions — a gold cannot disagree with the corpus."],
    ["Multi-hop is proven, not asserted", "The validator locates the bridge entity and the answer in disjoint documents."],
    ["Answers frozen before scoring", "No scorer can be written after seeing the numbers it judges."],
    ["Every change pre-registered", "It had to pass a statistical rule on two independent runs."],
  ];
  guards.forEach(([t, d], i) => {
    const x = 0.6 + (i % 2) * 6.15, y = 4.15 + Math.floor(i / 2) * 1.35;
    s.addText(t, {
      x, y, w: 5.9, h: 0.3, fontFace: B, fontSize: 13, bold: true, color: INK, margin: 0,
    });
    s.addText(d, {
      x, y: y + 0.33, w: 5.85, h: 0.72, fontFace: B, fontSize: 11.5, color: "5A6684", margin: 0,
    });
  });
  NOTE(s, "Most RAG demos are scored on questions written after seeing the answers. Ours are generated from the same world model that renders the corpus, and validated before use.");
}

// ── 9. WE PUBLISH WHAT FAILED ───────────────────────────────────────────────
{
  const s = pres.addSlide();
  s.background = { color: NAVY };
  s.addText("WE PUBLISH WHAT FAILED", {
    x: 0.6, y: 0.62, w: 9, h: 0.32, fontFace: B, fontSize: 12, bold: true,
    color: GREEN, charSpacing: 2.5, margin: 0,
  });
  s.addText("Two of four improvements failed our own gates", {
    x: 0.6, y: 0.98, w: 12.1, h: 0.75, fontFace: H, fontSize: 31, bold: true, color: WHITE, margin: 0,
  });
  const rows = [
    ["GLOBAL chunk fan-out", "ACCEPTED", "passed both independent runs", GREEN],
    ["LOCAL hybrid context", "ACCEPTED", "passed both independent runs", GREEN],
    ["LOCAL vector-only", "REJECTED", "passed run 1, failed replication", RED],
    ["Larger context window (4096)", "REJECTED", "55.5 s latency against a 60 s timeout", RED],
  ];
  rows.forEach(([name, verdict, why, col], i) => {
    const y = 2.15 + i * 0.92;
    s.addShape(pres.ShapeType.roundRect, {
      x: 0.6, y, w: 12.1, h: 0.76, rectRadius: 0.06,
      fill: { color: NAVY2 }, line: { color: NAVY2 },
    });
    s.addText(name, {
      x: 1.0, y: y + 0.21, w: 4.4, h: 0.35, fontFace: B, fontSize: 14, bold: true,
      color: WHITE, margin: 0,
    });
    s.addShape(pres.ShapeType.roundRect, {
      x: 5.6, y: y + 0.19, w: 1.5, h: 0.38, rectRadius: 0.06,
      fill: { color: col }, line: { color: col },
    });
    s.addText(verdict, {
      x: 5.6, y: y + 0.24, w: 1.5, h: 0.28, align: "center",
      fontFace: B, fontSize: 10.5, bold: true, color: verdict === "ACCEPTED" ? "0C2E1E" : "FFFFFF",
      margin: 0,
    });
    s.addText(why, {
      x: 7.35, y: y + 0.23, w: 5.0, h: 0.32, fontFace: B, fontSize: 12, color: ICE, margin: 0,
    });
  });
  s.addText(
    "A team that never reports a negative result has either been extraordinarily lucky, or is not looking.",
    { x: 0.6, y: 6.1, w: 12.1, h: 0.5, fontFace: B, fontSize: 15, italic: true, color: ICE, margin: 0 }
  );
  NOTE(s, "The rejected experiments are the credibility asset. One passed its first run and failed replication — the gate caught it.");
}

// ── 10. BUILT FOR INSTITUTIONS ──────────────────────────────────────────────
{
  const s = lightSlide("Built for institutions, not for a demo", "Product");
  const feats = [
    ["Runs on 4 GB VRAM", "An RTX 2050 laptop. No A100, no cloud bill, no per-query cost.", NAVY2],
    ["Offline by default", "Student data never leaves the machine. Enforced by a test.", GREEN],
    ["Multi-tenant", "Per-tenant data trees, scoped API keys, path-traversal guards.", "1C7293"],
    ["PII controls", "A role gate for student identities — built, tested, and shipping OFF, because that is an institution's policy call.", "6D4AA6"],
    ["Operator dashboard", "Query, health, documents, review queue, upload, live audit stream.", NAVY2],
    ["Chat delivery", "Telegram and WhatsApp bots against the same API.", "1C7293"],
  ];
  feats.forEach(([t, d, c], i) => {
    const x = 0.6 + (i % 2) * 6.15, y = 1.8 + Math.floor(i / 2) * 1.45;
    s.addShape(pres.ShapeType.ellipse, {
      x, y: y + 0.06, w: 0.42, h: 0.42, fill: { color: c }, line: { color: c },
    });
    s.addText(t, {
      x: x + 0.62, y, w: 5.3, h: 0.32, fontFace: B, fontSize: 14.5, bold: true, color: INK, margin: 0,
    });
    s.addText(d, {
      x: x + 0.62, y: y + 0.36, w: 5.25, h: 0.82, fontFace: B, fontSize: 11.5, color: "5A6684", margin: 0,
    });
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 6.05, w: 12.1, h: 0.72, rectRadius: 0.06,
    fill: { color: "ECF1F9" }, line: { color: "DCE4F2", width: 1 },
  });
  s.addText("FastAPI · DuckDB · FAISS · NetworkX · sentence-transformers · Ollama (qwen3:4b) · Next.js 16 · Python 3.12", {
    x: 0.9, y: 6.24, w: 11.5, h: 0.35, fontFace: B, fontSize: 12, color: "3D4A69", margin: 0,
  });
  NOTE(s, "The deployment story matters as much as accuracy: cheap hardware, no egress, tenant isolation, and an operator console.");
}

// ── 11. ROADMAP ─────────────────────────────────────────────────────────────
{
  const s = lightSlide("The remaining gap is well characterised", "Where we go next");
  const items = [
    ["18 of 23 remaining failures are abstentions",
     "And in 13 of them the answer was sitting in the retrieved context. That is a prompt problem, not a retrieval one — the cheapest remaining win.", GREEN],
    ["Cross-document arithmetic is 14 / 24",
     "A 4B model quotes a table accurately and adds two together unreliably. A compute step or a larger model closes this.", AMBER],
    ["Router accuracy is 54.3% — and now worth ~0 points",
     "Both destination routes were repaired, so route choice stopped mattering for accuracy. It still matters for latency and cost.", "7B88A8"],
  ];
  items.forEach(([t, d, c], i) => {
    const y = 1.85 + i * 1.42;
    s.addShape(pres.ShapeType.roundRect, {
      x: 0.6, y, w: 12.1, h: 1.2, rectRadius: 0.08,
      fill: { color: WHITE }, line: { color: "E3E8F2", width: 1 },
    });
    s.addShape(pres.ShapeType.ellipse, {
      x: 0.95, y: y + 0.42, w: 0.34, h: 0.34, fill: { color: c }, line: { color: c },
    });
    s.addText(t, {
      x: 1.5, y: y + 0.2, w: 10.8, h: 0.34, fontFace: B, fontSize: 14.5, bold: true, color: INK, margin: 0,
    });
    s.addText(d, {
      x: 1.5, y: y + 0.56, w: 10.8, h: 0.55, fontFace: B, fontSize: 11.5, color: "5A6684", margin: 0,
    });
  });
  s.addShape(pres.ShapeType.roundRect, {
    x: 0.6, y: 6.15, w: 12.1, h: 0.85, rectRadius: 0.08,
    fill: { color: "ECF1F9" }, line: { color: "DCE4F2", width: 1 },
  });
  s.addText("Honest scope:", {
    x: 0.95, y: 6.34, w: 1.5, h: 0.3, fontFace: B, fontSize: 12, bold: true, color: INK, margin: 0,
  });
  s.addText("one synthetic benchmark corpus, a 4B local model, single-sample runs. We benchmarked architectures — not commercial products. The harness is in the repo; adding a competitor takes about twenty minutes.",
    { x: 2.15, y: 6.34, w: 10.3, h: 0.5, fontFace: B, fontSize: 11.5, color: "3D4A69", margin: 0 });
  NOTE(s, "Stating the scope limits plainly is what makes the rest of the numbers credible under questioning.");
}

// ── 12. CLOSING ─────────────────────────────────────────────────────────────
{
  const s = darkSlide();
  s.addShape(pres.ShapeType.ellipse, {
    x: -1.8, y: 4.4, w: 5.2, h: 5.2, fill: { color: NAVY2 }, line: { color: NAVY2 },
  });
  s.addText("COMPANY BRAIN", {
    x: 0.85, y: 1.35, w: 9, h: 0.4, fontFace: B, fontSize: 13, bold: true,
    color: GREEN, charSpacing: 3, margin: 0,
  });
  s.addText("Every number in this deck\nis reproducible from a\nclean checkout.", {
    x: 0.85, y: 1.9, w: 9.6, h: 2.3, fontFace: H, fontSize: 36, bold: true,
    color: WHITE, lineSpacing: 42, margin: 0,
  });
  const stats = [["88.9%", "208 questions"], ["4 GB", "GPU, offline"], ["280", "tests"], ["0", "cloud calls"]];
  stats.forEach(([v, l], i) => {
    s.addText(v, {
      x: 0.85 + i * 2.7, y: 4.45, w: 2.5, h: 0.6,
      fontFace: H, fontSize: 32, bold: true, color: GREEN, margin: 0,
    });
    s.addText(l, {
      x: 0.85 + i * 2.7, y: 5.05, w: 2.6, h: 0.3,
      fontFace: B, fontSize: 11, color: MUTED, margin: 0,
    });
  });
  s.addText("Rohan Gaikwad", {
    x: 0.85, y: 5.95, w: 7, h: 0.4, fontFace: B, fontSize: 18, bold: true, color: WHITE, margin: 0,
  });
  s.addText("github.com/RohanExploit/startup-research-rag", {
    x: 0.85, y: 6.36, w: 8, h: 0.3, fontFace: B, fontSize: 12.5, color: ICE, margin: 0,
  });
  s.addText("itzrohan007@gmail.com", {
    x: 0.85, y: 6.68, w: 8, h: 0.3, fontFace: B, fontSize: 12.5, color: MUTED, margin: 0,
  });
  NOTE(s, "Close on reproducibility: the repo contains the harness, the benchmark, the validator and the rejected experiments.");
}

pres.writeFile({ fileName: "docs/pitch.pptx" }).then((f) => console.log("wrote", f));
