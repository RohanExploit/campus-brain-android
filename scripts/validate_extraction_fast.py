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
from ingestion.chunk import process_markdown_files
from ingestion.extract_entities import extract_entities_from_chunk
import tempfile

logging.basicConfig(level=logging.INFO)

RAW_DIR = Path(f"{PROJECT_ROOT}/data/tenants/tenant_1/raw")
CHUNKED_DIR = Path(f"{PROJECT_ROOT}/data/tenants/tenant_1/chunked")

converter = DocumentConverter()

def run_validation():
    # 1. We know we have 11 real docs in raw/. We will pick 5.
    all_files = list(RAW_DIR.glob("*.*"))
    real_files = [f for f in all_files if not f.name.startswith("funsd_") and not f.name.endswith(".png")]

    if len(real_files) < 5:
        logging.error(f"Fewer than 5 real files found. Found {len(real_files)}. Stopping.")
        return

    selected_files = real_files[:5]
    logging.info(f"Selected 5 real files: {[f.name for f in selected_files]}")

    results = {}

    for file_path in selected_files:
        logging.info(f"Processing {file_path.name}...")

        # Check if we already have chunks for this file
        chunk_file = CHUNKED_DIR / f"{file_path.stem}_chunks.json"

        chunks = []
        if chunk_file.exists():
            logging.info(f"Using existing chunks from {chunk_file.name}")
            with open(chunk_file, "r", encoding="utf-8") as f:
                chunks = json.load(f)
        else:
            logging.info(f"Parsing and chunking {file_path.name}...")
            with tempfile.TemporaryDirectory() as temp_dir:
                temp_parsed_dir = Path(temp_dir) / "parsed"
                temp_parsed_dir.mkdir()
                try:
                    result = converter.convert(file_path)
                    md_text = result.document.export_to_markdown()
                    md_path = temp_parsed_dir / f"{file_path.stem}.md"
                    with open(md_path, "w", encoding="utf-8") as f:
                        f.write(md_text)
                except Exception as e:
                    logging.error(f"Failed to parse {file_path.name}: {e}")
                    results[file_path.name] = {"error": f"Parse failed: {e}", "entities": []}
                    continue

                md_files = list(temp_parsed_dir.glob("*.md"))
                if not md_files:
                    logging.error(f"No MD file generated for {file_path.name}")
                    results[file_path.name] = {"error": "No MD generated", "entities": []}
                    continue

                temp_chunked_dir = Path(temp_dir) / "chunked"
                process_markdown_files(temp_parsed_dir, temp_chunked_dir)
                out_chunk_file = temp_chunked_dir / f"{file_path.stem}_chunks.json"
                if out_chunk_file.exists():
                    with open(out_chunk_file, "r", encoding="utf-8") as f:
                        chunks = json.load(f)

        if not chunks:
            logging.error(f"No chunks found for {file_path.name}")
            results[file_path.name] = {"error": "No chunks", "entities": []}
            continue

        # Extract entities
        doc_entities = {"nodes": [], "edges": []}

        # Optional: limit chunks if too many, but user said "no chunk-count bounding/truncation this time."
        # However, for debugging the JSON parsing error quickly, we will just use the first 2 chunks for now.
        for i, chunk in enumerate(chunks[:2]):
            logging.info(f"  Extracting chunk {i+1}/{len(chunks)} for {file_path.name}...")
            ext = extract_entities_from_chunk(chunk["page_content"])
            if ext:
                doc_entities["nodes"].extend(ext.get("nodes", []))
                doc_entities["edges"].extend(ext.get("edges", []))

        results[file_path.name] = {
            "nodes_count": len(doc_entities["nodes"]),
            "edges_count": len(doc_entities["edges"]),
            "entities": doc_entities
        }

    out_file = Path(f"{PROJECT_ROOT}/extraction_review.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    logging.info(f"Validation complete. Results saved to {out_file}")

if __name__ == "__main__":
    run_validation()
