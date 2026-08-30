"""
Security regression: uploaded filenames cannot escape the staging directory.
safe_filename() must reduce any crafted path to a bare basename.
"""
import sys
from pathlib import Path

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import config


ESCAPES = {
    "../../etc/passwd": "passwd",
    "..\\..\\windows\\system32\\evil.dll": "evil.dll",
    "/etc/shadow": "shadow",
    "C:\\Users\\victim\\secret.pdf": "secret.pdf",
    "....//x.txt": "x.txt",
    "normal_report.pdf": "normal_report.pdf",
    "a/b/c/d.json": "d.json",
}


@pytest.mark.parametrize("raw,expected", list(ESCAPES.items()))
def test_safe_filename_strips_directories(raw, expected):
    assert config.safe_filename(raw) == expected


@pytest.mark.parametrize("raw", list(ESCAPES.keys()) + ["...", "..", ".", ""])
def test_sanitized_name_cannot_escape_staging(tmp_path, raw):
    staging = tmp_path / "staging" / "upload-xyz"
    staging.mkdir(parents=True)
    target = (staging / config.safe_filename(raw)).resolve()
    # the resolved file must sit directly inside staging — no traversal
    assert target.parent == staging.resolve()


def test_dotonly_names_become_placeholder():
    for raw in ["...", "..", ".", ""]:
        assert config.safe_filename(raw) == "upload"
