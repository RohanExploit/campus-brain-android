"""
PDF Factory — programmatically generate test PDFs for audit fixtures.
Requires: reportlab, pypdf, Pillow
"""
from pathlib import Path


def _check_reportlab():
    try:
        from reportlab.lib.pagesizes import letter  # noqa: F401 — import is the availability probe
        from reportlab.pdfgen import canvas  # noqa: F401
        return True
    except ImportError:
        return False


def make_valid_pdf(path: Path, text: str = "This is a valid test document.\nStudent Roll: 9999999999\nSGPA: 8.50") -> Path:
    """Create a minimal valid single-page PDF."""
    if _check_reportlab():
        from reportlab.pdfgen import canvas as rl_canvas
        c = rl_canvas.Canvas(str(path))
        for i, line in enumerate(text.splitlines()):
            c.drawString(72, 750 - i * 20, line)
        c.save()
    else:
        # Fallback: hand-craft a minimal valid PDF
        _minimal_pdf(path, text)
    return path


def make_blank_pdf(path: Path) -> Path:
    """Create a PDF with pages containing no text."""
    if _check_reportlab():
        from reportlab.pdfgen import canvas as rl_canvas
        c = rl_canvas.Canvas(str(path))
        c.showPage()  # blank page
        c.save()
    else:
        _minimal_pdf(path, "")
    return path


def make_corrupted_pdf(path: Path) -> Path:
    """Create a PDF with corrupted bytes (invalid xref table)."""
    content = b"%PDF-1.4\n%CORRUPTED BYTES\x00\x01\x02\x03\xff\xfe\xfd"
    content += b"\n1 0 obj\n<< /Type /Catalog >>\nendobj\nBAD XREF\n%%EOF"
    path.write_bytes(content)
    return path


def make_rotated_pdf(path: Path, rotation: int = 90) -> Path:
    """Create a PDF with a rotated page (valid but unusual orientation)."""
    if _check_reportlab():
        from reportlab.pdfgen import canvas as rl_canvas
        from reportlab.lib.pagesizes import letter
        c = rl_canvas.Canvas(str(path), pagesize=letter)
        c.rotate(rotation)
        c.drawString(-500, 10, f"Rotated {rotation} degrees test document.")
        c.save()
    else:
        make_valid_pdf(path, f"Rotated {rotation} degrees test document.")
    return path


def make_password_pdf(path: Path, password: str = "secret123") -> Path:
    """Create an encrypted, password-protected PDF."""
    try:
        from pypdf import PdfWriter
        writer = PdfWriter()
        writer.add_blank_page(width=612, height=792)
        # Write temp unencrypted first
        tmp = path.with_suffix(".tmp.pdf")
        make_valid_pdf(tmp, "Password protected document.")
        from pypdf import PdfReader
        reader = PdfReader(str(tmp))
        writer = PdfWriter()
        writer.append(reader)
        writer.encrypt(password)
        with open(path, "wb") as f:
            writer.write(f)
        tmp.unlink(missing_ok=True)
    except ImportError:
        # Fall back to corrupted-looking PDF if pypdf not available
        make_corrupted_pdf(path)
    return path


def make_multipage_pdf(path: Path, pages: int = 5) -> Path:
    """Create a multi-page PDF."""
    if _check_reportlab():
        from reportlab.pdfgen import canvas as rl_canvas
        c = rl_canvas.Canvas(str(path))
        for i in range(pages):
            c.drawString(72, 750, f"Page {i+1} of {pages}. Audit test document.")
            c.showPage()
        c.save()
    else:
        make_valid_pdf(path, "\n".join([f"Page {i+1}" for i in range(pages)]))
    return path


def make_duplicate_pdf(src: Path, dst: Path) -> Path:
    """Create an exact copy of a PDF (byte-for-byte duplicate)."""
    import shutil
    shutil.copy2(src, dst)
    return dst


def _minimal_pdf(path: Path, text: str):
    """Bare-minimum hand-crafted PDF (no reportlab dependency)."""
    offsets = []
    buf = b"%PDF-1.4\n"
    offsets.append(len(buf))
    buf += (
        b"1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n"
    )
    offsets.append(len(buf))
    buf += b"2 0 obj\n<</Type /Pages /Kids [3 0 R] /Count 1>>\nendobj\n"
    offsets.append(len(buf))
    buf += b"3 0 obj\n<</Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]>>\nendobj\n"
    xref_offset = len(buf)
    buf += b"xref\n0 4\n0000000000 65535 f \n"
    for o in offsets:
        buf += f"{o:010d} 00000 n \n".encode()
    buf += b"trailer\n<</Size 4 /Root 1 0 R>>\nstartxref\n"
    buf += f"{xref_offset}\n".encode()
    buf += b"%%EOF\n"
    path.write_bytes(buf)
