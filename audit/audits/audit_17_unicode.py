"""
Audit 17 — Unicode Support
Pass: Student names in Hindi, Tamil, Arabic, Marathi stored and retrieved without corruption.
"""
import pytest
import duckdb
from pathlib import Path

pytestmark = pytest.mark.retrieval

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DATA_ROOT = PROJECT_ROOT / "data" / "tenants"

UNICODE_NAMES = [
    ("विक्रम सिंह",       "Devanagari"),
    ("தமிழ் மாணவர்",     "Tamil"),
    ("محمد علي",          "Arabic"),
    ("ਗੁਰਪ੍ਰੀਤ ਕੌਰ",    "Punjabi"),
    ("আহমেদ রেজা",       "Bengali"),
]


class TestUnicodeSupport:

    def test_utf8_roundtrip_for_all_scripts(self):
        for name, script in UNICODE_NAMES:
            encoded = name.encode("utf-8")
            decoded = encoded.decode("utf-8")
            assert decoded == name, f"{script} name corrupted in UTF-8 roundtrip: {name!r}"

    def test_duckdb_stores_unicode_varchar(self, tmp_path):
        con = duckdb.connect(str(tmp_path / "test_unicode.duckdb"))
        con.execute("CREATE TABLE names (id INTEGER, name VARCHAR)")
        for i, (name, _) in enumerate(UNICODE_NAMES):
            con.execute("INSERT INTO names VALUES (?, ?)", [i, name])
        for i, (name, script) in enumerate(UNICODE_NAMES):
            row = con.execute("SELECT name FROM names WHERE id = ?", [i]).fetchone()
            assert row[0] == name, f"{script} name corrupted in DuckDB: {name!r} → {row[0]!r}"
        con.close()

    def test_unicode_in_json_serialization(self):
        import json
        for name, script in UNICODE_NAMES:
            dumped = json.dumps({"name": name}, ensure_ascii=False)
            loaded = json.loads(dumped)
            assert loaded["name"] == name, \
                f"{script} name corrupted in JSON roundtrip: {name!r}"

    def test_unicode_in_markdown_output(self, tmp_path):
        for name, script in UNICODE_NAMES:
            md_path = tmp_path / f"test_{script}.md"
            md_path.write_text(f"# Student: {name}\nSGPA: 8.5\n", encoding="utf-8")
            content = md_path.read_text(encoding="utf-8")
            assert name in content, f"{script} name lost in markdown write/read: {name!r}"

    def test_real_student_names_unicode_clean(self):
        dbs = list(DATA_ROOT.glob("*/tabular.duckdb"))
        if not dbs:
            pytest.skip("No tabular.duckdb found")
        con = duckdb.connect(str(dbs[0]), read_only=True)
        names = con.execute("SELECT name FROM students LIMIT 20").fetchall()
        con.close()
        for (name,) in names:
            if name:
                # Name must be valid UTF-8 string (DuckDB returns Python str)
                assert isinstance(name, str), f"Name is not a string: {name!r}"
                # Must not contain replacement characters (UTF-8 corruption marker)
                assert "\ufffd" not in name, f"UTF-8 replacement char in name: {name!r}"

    def test_query_with_unicode_name_returns_results(self, tmp_path):
        con = duckdb.connect(str(tmp_path / "test_query.duckdb"))
        con.execute("CREATE TABLE students (roll_no VARCHAR, name VARCHAR)")
        con.execute("INSERT INTO students VALUES ('2021001001', 'विक्रम सिंह')")
        result = con.execute(
            "SELECT roll_no FROM students WHERE name = ?", ["विक्रम सिंह"]
        ).fetchone()
        con.close()
        assert result is not None, "Unicode name query returned no results"
        assert result[0] == "2021001001"
