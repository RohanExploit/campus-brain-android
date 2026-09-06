import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import json
import logging
from pathlib import Path
from docling.document_converter import DocumentConverter
from utils.logging_config import setup_logging

setup_logging()

# ─── Manifest helpers ─────────────────────────────────────────────────

# Moved to ingestion/manifest.py so the ledger can be read and written without
# importing Docling. Re-exported here: these names are imported from this module
# in several places and the indirection keeps those call sites unchanged.
from ingestion.manifest import (  # noqa: F401
    MANIFEST_SCHEMA,
    _sha256,
    _get_manifest_conn,
    _manifest_update,
    _manifest_get,
)


# ─── Table integrity check (unchanged) ────────────────────────────────────────

def check_table_broken(markdown_text):
    if not markdown_text or not markdown_text.strip():
        return True
    if "|" not in markdown_text:
        return False
    if "--|--" not in markdown_text.replace(" ", ""):
        return False
    lines = markdown_text.split("\n")
    in_table = False
    expected_cols = None
    for line in lines:
        line = line.strip()
        if "|" in line:
            if not in_table:
                in_table = True
                expected_cols = None
            if "---" in line and "--|--" in line.replace(" ", ""):
                continue
            clean_line = line.strip("|")
            col_count = len(clean_line.split("|"))
            if expected_cols is None:
                expected_cols = col_count
            elif col_count != expected_cols:
                return True
        else:
            in_table = False
    return False


# ─── Main pipeline ─────────────────────────────────────────────────────────────

def main(input_dir: str = f"{PROJECT_ROOT}/data/tenants/tenant_1/raw",
         output_dir: str = f"{PROJECT_ROOT}/data/tenants/tenant_1/parsed"):
    input_dir = Path(input_dir)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Derive tenant dir from input_dir (two levels up from raw/)
    tenant_dir = input_dir.parent
    val_log_path = Path(f"{PROJECT_ROOT}/validation_log.md")

    converter = DocumentConverter()
    files = list(input_dir.iterdir())
    logging.info(f"Found {len(files)} files to parse")

    # Open manifest DB connection for this tenant
    manifest_conn = _get_manifest_conn(tenant_dir)

    schema_written = False

    with open(val_log_path, "w", encoding="utf-8") as vlog:
        vlog.write("# Gate-1 Validation Log\n\n")

        for i, file_path in enumerate(files):
            if not file_path.is_file():
                continue

            doc_id = file_path.name
            file_hash = _sha256(file_path)
            file_size = file_path.stat().st_size
            flags = []

            if file_path.suffix.lower() in [".png", ".jpg", ".jpeg"] or "funsd" in file_path.name.lower():
                flags.append("OCR_TRIGGERED")

            # ── Idempotency: skip if already parsed with same hash ─────────
            existing = _manifest_get(manifest_conn, doc_id)
            out_md_path = output_dir / f"{file_path.stem}.md"

            if existing and existing["file_hash"] == file_hash and out_md_path.exists():
                logging.info(f"Skipping {file_path.name} — unchanged (hash match).")
                with open(out_md_path, "r", encoding="utf-8") as f:
                    md_text = f.read()
                page_count = "Cached"
                # Re-check table integrity even on cache hit
                if check_table_broken(md_text):
                    flags.append("TABLE_BROKEN")
                _manifest_update(manifest_conn, doc_id, file_hash, "SUCCESS",
                                 flags=flags, file_size_bytes=file_size)
            else:
                # ── New or changed file: parse it ──────────────────────────
                _manifest_update(manifest_conn, doc_id, file_hash, "PENDING",
                                 flags=flags, file_size_bytes=file_size)
                try:
                    logging.info(f"[{i+1}/{len(files)}] Parsing {file_path.name}")
                    result = converter.convert(file_path)
                    doc = result.document

                    doc_dict = doc.export_to_dict()

                    if not schema_written:
                        try:
                            schema_dict = doc.model_json_schema()
                        except Exception:
                            schema_dict = list(doc_dict.keys())
                        with open(output_dir / "schema.json", "w", encoding="utf-8") as f:
                            json.dump(schema_dict, f, indent=2)
                        schema_written = True

                    out_path = output_dir / f"{file_path.stem}.json"
                    with open(out_path, "w", encoding="utf-8") as f:
                        json.dump(doc_dict, f, indent=2)

                    md_text = doc.export_to_markdown()
                    with open(out_md_path, "w", encoding="utf-8") as f:
                        f.write(md_text)

                    page_count = len(doc.pages) if hasattr(doc, "pages") else None

                    if check_table_broken(md_text):
                        flags.append("TABLE_BROKEN")

                    # ── SUCCESS: write to manifest ─────────────────────────
                    _manifest_update(manifest_conn, doc_id, file_hash, "SUCCESS",
                                     page_count=page_count, flags=flags,
                                     file_size_bytes=file_size)

                except Exception as e:
                    logging.error(f"Error parsing {file_path.name}: {e}")
                    flags.append("PARSE_FAILURE")
                    md_text = ""
                    page_count = None
                    # ── FAILED: explicit error recorded — never silent ─────
                    _manifest_update(manifest_conn, doc_id, file_hash, "FAILED",
                                     error_message=str(e)[:500], flags=flags,
                                     file_size_bytes=file_size)
                    vlog.write(f"### File: `{file_path.name}`\n")
                    vlog.write(f"- **Format**: {file_path.suffix}\n")
                    vlog.write(f"- **Flags**: {' '.join(flags)}\n")
                    vlog.write(f"- **Error**: {e}\n\n")
                    continue

            # ── Validation log entry ───────────────────────────────────────
            lines = [l for l in md_text.split("\n") if l.strip()]
            excerpt = "\n".join(lines[:5])
            vlog.write(f"### File: `{file_path.name}`\n")
            vlog.write(f"- **Format**: {file_path.suffix}\n")
            vlog.write(f"- **Pages**: {page_count}\n")
            vlog.write(f"- **Hash**: `{file_hash[:16]}...`\n")
            if flags:
                vlog.write(f"- **Flags**: {' '.join(flags)}\n")
            vlog.write(f"- **Excerpt**:\n```markdown\n{excerpt}\n```\n\n")
            vlog.flush()

    manifest_conn.close()
    logging.info("Parsing complete. manifest.db updated.")


if __name__ == "__main__":
    main()
