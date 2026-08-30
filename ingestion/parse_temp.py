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

logging.basicConfig(level=logging.INFO)

def check_table_broken(markdown_text):
    # Simple heuristic: find markdown tables and check if they are aligned
    lines = markdown_text.split("\n")
    in_table = False
    for line in lines:
        if "|" in line:
            if not in_table:
                in_table = True
            # Very basic check: just check if line starts and ends with |
            # A real check would split by | and ensure column counts match the header.
        else:
            in_table = False
    return False # Keep it simple for now, we'll let humans eyeball it as requested by the user, but we can flag obvious issues if we implement deeper checks.

def main():
    input_dir = Path(f"{PROJECT_ROOT}/data/tenants/tenant_2/raw")
    output_dir = Path(f"{PROJECT_ROOT}/data/tenants/tenant_2/parsed")
    output_dir.mkdir(parents=True, exist_ok=True)

    # Validation log path
    val_log_path = Path(f"{PROJECT_ROOT}/validation_log.md")

    converter = DocumentConverter()

    files = list(input_dir.iterdir())
    logging.info(f"Found {len(files)} files to parse")

    schema_written = False

    with open(val_log_path, "w", encoding="utf-8") as vlog:
        vlog.write("# Gate-1 Validation Log\n\n")

        for i, file_path in enumerate(files):
            if not file_path.is_file():
                continue

            flags = []
            if file_path.suffix.lower() in [".png", ".jpg", ".jpeg"] or "funsd" in file_path.name.lower():
                flags.append("[OCR TRIGGERED]")

            try:
                out_md_path = output_dir / f"{file_path.stem}.md"
                if out_md_path.exists():
                    logging.info(f"Skipping {file_path}, already parsed.")
                    with open(out_md_path, "r", encoding="utf-8") as f:
                        md_text = f.read()
                    page_count = "Cached"
                else:
                    logging.info(f"[{i+1}/{len(files)}] Parsing {file_path}")
                    result = converter.convert(file_path)
                    doc = result.document

                    doc_dict = doc.export_to_dict()

                    if not schema_written:
                        try:
                            schema_dict = doc.model_json_schema()
                            with open(output_dir / "schema.json", "w", encoding="utf-8") as f:
                                json.dump(schema_dict, f, indent=2)
                            schema_written = True
                        except Exception:
                            with open(output_dir / "schema.json", "w", encoding="utf-8") as f:
                                json.dump(list(doc_dict.keys()), f, indent=2)
                            schema_written = True

                    out_path = output_dir / f"{file_path.stem}.json"
                    with open(out_path, "w", encoding="utf-8") as f:
                        json.dump(doc_dict, f, indent=2)

                    md_text = doc.export_to_markdown()
                    with open(out_md_path, "w", encoding="utf-8") as f:
                        f.write(md_text)
                    page_count = len(doc.pages) if hasattr(doc, "pages") else "Unknown"

                # Check for table issues (dummy heuristic for now)
                if md_text and ("|" in md_text) and ("--|--" not in md_text.replace(" ", "")):
                    # A table might be broken if it doesn't have a valid separator
                    flags.append("[TABLE BROKEN]")

                # Excerpt
                lines = [l for l in md_text.split("\n") if l.strip() != ""]
                excerpt = "\n".join(lines[:5])

                vlog.write(f"### File: `{file_path.name}`\n")
                vlog.write(f"- **Format**: {file_path.suffix}\n")
                vlog.write(f"- **Pages**: {page_count}\n")
                if flags:
                    vlog.write(f"- **Flags**: {' '.join(flags)}\n")
                vlog.write(f"- **Excerpt**:\n```markdown\n{excerpt}\n```\n\n")
                vlog.flush()

            except Exception as e:
                logging.error(f"Error parsing {file_path}: {e}")
                flags.append("[PARSE FAILURE]")
                vlog.write(f"### File: `{file_path.name}`\n")
                vlog.write(f"- **Format**: {file_path.suffix}\n")
                vlog.write(f"- **Flags**: {' '.join(flags)}\n")
                vlog.write(f"- **Error**: {e}\n\n")

if __name__ == "__main__":
    main()
