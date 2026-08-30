"""Reusable reportlab framework for the student corpus: letterhead, notice header, body
helpers, and footer, so every document in corpus/render_academic.py — and every document
the two agents who follow render for the remaining categories — looks like it came from the
same institute.

Text must be extractable: this corpus gets parsed by an ingestion pipeline (docling /
pdfplumber), so everything below is built from reportlab Platypus flowables (Paragraph,
Table) — real text objects and real table cells, never a rasterized image of text and never
ASCII-art column alignment.

The public API other agents build against is NoticeDoc:

    doc = NoticeDoc(filename, notice_no, date, subject, to)
    doc.para("Some body text.")
    doc.bullets(["First point.", "Second point."])
    doc.table(["Column A", "Column B"], [["1", "2"], ["3", "4"]])
    doc.signature("hod_comp")   # a PEOPLE key from corpus.student_world
    doc.save()
"""
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import (
    ListFlowable, ListItem, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle,
)

from corpus.student_world import INSTITUTE, PEOPLE

PAGE_SIZE = A4
MARGIN_LEFT = MARGIN_RIGHT = 2.2 * cm
MARGIN_TOP = 2.0 * cm
MARGIN_BOTTOM = 2.0 * cm
CONTENT_WIDTH = PAGE_SIZE[0] - MARGIN_LEFT - MARGIN_RIGHT


def weighted_col_widths(weights):
    """Turn relative weights (e.g. [2, 1, 1, 3]) into column widths that fill exactly
    CONTENT_WIDTH. Use this instead of equal-width columns whenever a table's columns hold
    very different amounts of text (a "Code" column next to a "Seating" column) — an equal
    split forces long text to wrap mid-word, which is still extractable but reads badly
    both on the page and as text (see corpus/render_academic.py's exam schedule table)."""
    total = sum(weights)
    return [CONTENT_WIDTH * w / total for w in weights]

# Vertical space the letterhead occupies at the top of every page, and the footer at the
# bottom — the body's frame is inset by these so text never collides with either.
LETTERHEAD_HEIGHT = 2.6 * cm
FOOTER_HEIGHT = 1.2 * cm

_styles = getSampleStyleSheet()

STYLE_INSTITUTE_NAME = ParagraphStyle(
    "InstituteName", parent=_styles["Title"], fontName="Helvetica-Bold",
    fontSize=15, leading=18, alignment=TA_CENTER, textColor=colors.HexColor("#1a2f4b"),
    spaceAfter=1,
)
STYLE_INSTITUTE_SUB = ParagraphStyle(
    "InstituteSub", parent=_styles["Normal"], fontName="Helvetica",
    fontSize=8.5, leading=11, alignment=TA_CENTER, textColor=colors.HexColor("#333333"),
)
STYLE_NOTICE_META = ParagraphStyle(
    "NoticeMeta", parent=_styles["Normal"], fontName="Helvetica",
    fontSize=9.5, leading=13,
)
STYLE_SUBJECT = ParagraphStyle(
    "Subject", parent=_styles["Normal"], fontName="Helvetica-Bold",
    fontSize=11, leading=14, spaceBefore=6, spaceAfter=6,
)
STYLE_BODY = ParagraphStyle(
    "Body", parent=_styles["Normal"], fontName="Helvetica",
    fontSize=10, leading=14, alignment=TA_JUSTIFY, spaceAfter=8,
)
STYLE_BULLET = ParagraphStyle(
    "Bullet", parent=_styles["Normal"], fontName="Helvetica",
    fontSize=10, leading=13,
)
STYLE_TABLE_HEADER = ParagraphStyle(
    "TableHeader", parent=_styles["Normal"], fontName="Helvetica-Bold",
    fontSize=9, leading=11, textColor=colors.white,
)
STYLE_TABLE_CELL = ParagraphStyle(
    "TableCell", parent=_styles["Normal"], fontName="Helvetica",
    fontSize=9, leading=11,
)
STYLE_SIGNATURE_NAME = ParagraphStyle(
    "SignatureName", parent=_styles["Normal"], fontName="Helvetica-Bold",
    fontSize=10, leading=13, alignment=TA_RIGHT,
)
STYLE_SIGNATURE_SUB = ParagraphStyle(
    "SignatureSub", parent=_styles["Normal"], fontName="Helvetica",
    fontSize=9, leading=12, alignment=TA_RIGHT,
)
STYLE_FOOTER = ParagraphStyle(
    "Footer", parent=_styles["Normal"], fontName="Helvetica",
    fontSize=7.5, leading=9, textColor=colors.HexColor("#555555"),
)


def _page_decorations(canvas, doc):
    """Draws the letterhead and footer on every page. Registered as both onFirstPage and
    onLaterPages so a multi-page notice repeats the institute's identity on each sheet."""
    canvas.saveState()

    # Letterhead
    top = PAGE_SIZE[1] - MARGIN_TOP
    canvas.setFont("Helvetica-Bold", 15)
    canvas.setFillColor(colors.HexColor("#1a2f4b"))
    canvas.drawCentredString(PAGE_SIZE[0] / 2, top - 0.55 * cm, INSTITUTE["name"])
    canvas.setFont("Helvetica", 8.5)
    canvas.setFillColor(colors.HexColor("#333333"))
    canvas.drawCentredString(
        PAGE_SIZE[0] / 2, top - 1.0 * cm,
        f"Affiliated to {INSTITUTE['affiliation']} | AICTE Approved ({INSTITUTE['aicte_approval_ref']})",
    )
    canvas.drawCentredString(
        PAGE_SIZE[0] / 2, top - 1.35 * cm,
        f"{INSTITUTE['address']} | Phone: {INSTITUTE['main_phone']} | {INSTITUTE['website']}",
    )
    canvas.setStrokeColor(colors.HexColor("#1a2f4b"))
    canvas.setLineWidth(1.1)
    canvas.line(MARGIN_LEFT, top - 1.6 * cm, PAGE_SIZE[0] - MARGIN_RIGHT, top - 1.6 * cm)

    # Footer
    canvas.setStrokeColor(colors.HexColor("#999999"))
    canvas.setLineWidth(0.5)
    footer_rule_y = MARGIN_BOTTOM - 0.35 * cm
    canvas.line(MARGIN_LEFT, footer_rule_y, PAGE_SIZE[0] - MARGIN_RIGHT, footer_rule_y)
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(colors.HexColor("#555555"))
    canvas.drawString(MARGIN_LEFT, footer_rule_y - 0.35 * cm, INSTITUTE["website"])
    canvas.drawRightString(
        PAGE_SIZE[0] - MARGIN_RIGHT, footer_rule_y - 0.35 * cm, f"Page {canvas.getPageNumber()}",
    )

    canvas.restoreState()


class NoticeDoc:
    """A single institute notice/circular, built as a flowing story of Platypus elements
    and rendered onto letterheaded, footered A4 pages.

    filename  -- output path (str or Path), typically under corpus/out/.
    notice_no -- e.g. "KRIET/EXAM/2026-27/014" — pull from student_world.NOTICE_LOG so the
                 number printed here can never drift from what a DEADLINES entry points at.
    date      -- issue date as a display string, e.g. "26 August 2026".
    subject   -- the notice's one-line subject.
    to        -- the addressee line, e.g. "Students of Semester 3, Division A".
    """

    def __init__(self, filename, notice_no, date, subject, to):
        self.filename = str(filename)
        self.notice_no = notice_no
        self.date = date
        self.subject = subject
        self.to = to
        self.story = []
        self._build_header()

    def _build_header(self):
        meta = (
            f"<b>Notice No.:</b> {self.notice_no} &nbsp;&nbsp;&nbsp; "
            f"<b>Date:</b> {self.date}<br/>"
            f"<b>To:</b> {self.to}"
        )
        self.story.append(Paragraph(meta, STYLE_NOTICE_META))
        self.story.append(Spacer(1, 6))
        self.story.append(Paragraph(f"Subject: {self.subject}", STYLE_SUBJECT))

    def para(self, text: str):
        """Append a body paragraph. `text` may use a small set of reportlab inline markup
        (<b>, <i>) since it is passed straight to Paragraph."""
        self.story.append(Paragraph(text, STYLE_BODY))
        return self

    def bullets(self, items):
        """Append a bulleted list of plain-text (or lightly-marked-up) strings."""
        flowables = [ListItem(Paragraph(item, STYLE_BULLET), leftIndent=12) for item in items]
        self.story.append(
            ListFlowable(flowables, bulletType="bullet", start="circle", leftIndent=14)
        )
        self.story.append(Spacer(1, 8))
        return self

    def table(self, headers, rows, col_widths=None):
        """Append a bordered table with a shaded header row. `rows` is a list of lists of
        strings; cells are wrapped in Paragraph so long text wraps instead of overflowing
        (and stays real, extractable text rather than a fixed-width string)."""
        header_cells = [Paragraph(str(h), STYLE_TABLE_HEADER) for h in headers]
        body_cells = [[Paragraph(str(c), STYLE_TABLE_CELL) for c in row] for row in rows]
        data = [header_cells] + body_cells

        if col_widths is None:
            col_widths = [CONTENT_WIDTH / len(headers)] * len(headers)

        t = Table(data, colWidths=col_widths, repeatRows=1)
        t.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1a2f4b")),
            ("GRID", (0, 0), (-1, -1), 0.6, colors.HexColor("#999999")),
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f2f4f7")]),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ("LEFTPADDING", (0, 0), (-1, -1), 5),
            ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ]))
        self.story.append(t)
        self.story.append(Spacer(1, 10))
        return self

    def signature(self, person_key: str):
        """Append a right-aligned signature block resolved from student_world.PEOPLE."""
        p = PEOPLE[person_key]
        self.story.append(Spacer(1, 22))
        self.story.append(Paragraph(p["name"], STYLE_SIGNATURE_NAME))
        self.story.append(Paragraph(p["designation"], STYLE_SIGNATURE_SUB))
        role = p.get("role")
        if role and role != p["designation"]:
            self.story.append(Paragraph(role, STYLE_SIGNATURE_SUB))
        self.story.append(Paragraph(f"Date: {self.date}", STYLE_SIGNATURE_SUB))
        return self

    def save(self):
        Path(self.filename).parent.mkdir(parents=True, exist_ok=True)
        doc = SimpleDocTemplate(
            self.filename, pagesize=PAGE_SIZE,
            leftMargin=MARGIN_LEFT, rightMargin=MARGIN_RIGHT,
            topMargin=MARGIN_TOP + LETTERHEAD_HEIGHT, bottomMargin=MARGIN_BOTTOM + FOOTER_HEIGHT,
            title=self.subject, author=INSTITUTE["name"],
        )
        doc.build(self.story, onFirstPage=_page_decorations, onLaterPages=_page_decorations)
        return self.filename
