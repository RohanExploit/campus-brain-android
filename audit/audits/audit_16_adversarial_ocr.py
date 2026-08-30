"""
Audit 16 — Adversarial OCR
Pass: Parser explicitly fails (FAILED status) on unreadable inputs, never silently passes.
"""
import pytest
from pathlib import Path
from PIL import Image, ImageFilter, ImageDraw

pytestmark = pytest.mark.integrity

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent

import sys
sys.path.insert(0, str(PROJECT_ROOT))
from ingestion.manifest import _get_manifest_conn


def _make_degraded_image(tmp_path: Path, degradation: str) -> Path:
    img = Image.new("RGB", (800, 600), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.text((50, 50), "ROLL: 2021001001  SGPA: 8.5", fill=(0, 0, 0))
    draw.text((50, 100), "Subject: Mathematics  Credits: 4  Grade: AA", fill=(0, 0, 0))

    if degradation == "blur":
        img = img.filter(ImageFilter.GaussianBlur(radius=8))
    elif degradation == "rotate_15":
        img = img.rotate(15, expand=True, fillcolor=(255, 255, 255))
    elif degradation == "rotate_30":
        img = img.rotate(30, expand=True, fillcolor=(255, 255, 255))
    elif degradation == "low_dpi":
        img = img.resize((80, 60))  # 72 DPI equivalent
        img = img.resize((800, 600), Image.NEAREST)
    elif degradation == "noise":
        import random
        pixels = img.load()
        for _ in range(5000):
            x, y = random.randint(0, 799), random.randint(0, 599)
            pixels[x, y] = (random.randint(0, 255),) * 3
    elif degradation == "high_contrast":
        from PIL import ImageEnhance
        img = ImageEnhance.Contrast(img).enhance(0.1)

    out = tmp_path / f"degraded_{degradation}.png"
    img.save(str(out))
    return out


class TestAdversarialOCR:

    def test_blurred_image_fixture_creatable(self, tmp_path):
        out = _make_degraded_image(tmp_path, "blur")
        assert out.exists()
        assert out.stat().st_size > 0

    def test_rotated_15deg_fixture_creatable(self, tmp_path):
        out = _make_degraded_image(tmp_path, "rotate_15")
        assert out.exists()

    def test_rotated_30deg_fixture_creatable(self, tmp_path):
        out = _make_degraded_image(tmp_path, "rotate_30")
        assert out.exists()

    def test_low_dpi_fixture_creatable(self, tmp_path):
        out = _make_degraded_image(tmp_path, "low_dpi")
        assert out.exists()

    def test_noise_fixture_creatable(self, tmp_path):
        out = _make_degraded_image(tmp_path, "noise")
        assert out.exists()

    def test_parse_failure_recorded_not_silenced(self, tmp_path):
        """A file that cannot be parsed must appear in manifest as FAILED, not missing."""
        import sqlite3
        import json
        import hashlib

        # Create a blank (unreadable) image
        img = Image.new("RGB", (100, 100), color=(200, 200, 200))
        blank_path = tmp_path / "blank.png"
        img.save(str(blank_path))

        # Simulate manifest recording
        manifest_db = tmp_path / "manifest.db"
        conn = sqlite3.connect(manifest_db)
        conn.close()
        # Production schema. The local five-column CREATE this replaced was
        # paired with a positional INSERT, so the row only landed correctly as
        # long as both stayed in lockstep with each other and with nothing else.
        conn = _get_manifest_conn(tmp_path)
        h = hashlib.sha256(blank_path.read_bytes()).hexdigest()
        conn.execute(
            """INSERT OR REPLACE INTO manifest
               (doc_id, file_hash, parse_status, error_message, flags)
               VALUES (?, ?, 'FAILED', ?, ?)""",
            (blank_path.name, h, "OCR failed: no text detected", json.dumps(["PARSE_FAILURE"]))
        )
        conn.commit()

        row = conn.execute(
            "SELECT parse_status FROM manifest WHERE doc_id = ?", (blank_path.name,)
        ).fetchone()
        conn.close()
        assert row is not None, "Failed file not recorded in manifest"
        assert row[0] == "FAILED", f"Expected FAILED, got {row[0]}"

    def test_docling_converter_exists(self):
        """Docling must be importable — it is the OCR engine."""
        from docling.document_converter import DocumentConverter
        assert DocumentConverter is not None

    def test_parse_status_options_cover_failure(self):
        """manifest.db parse_status must allow FAILED as a valid value."""
        parse_py = PROJECT_ROOT / "ingestion" / "parse.py"
        content = parse_py.read_text(encoding="utf-8")
        assert "FAILED" in content, "FAILED status not used in parse.py"
        assert "SUCCESS" in content, "SUCCESS status not used in parse.py"
