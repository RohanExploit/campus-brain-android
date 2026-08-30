import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import json
import logging
from pathlib import Path
from ingestion.parse import process_file
from ingestion.chunk import chunk_markdown
from ingestion.extract_entities import extract_entities_from_chunk
import tempfile

logging.basicConfig(level=logging.INFO)

RAW_DIR = Path(f"{PROJECT_ROOT}/data/tenants/tenant_1/raw")

def run_validation():
    # 1. Select 5 real files from RAW_DIR
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

        # Parse
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_parsed_dir = Path(temp_dir) / "parsed"
            temp_parsed_dir.mkdir()
            process_file(file_path, temp_parsed_dir)

            # Find parsed .md file
            md_files = list(temp_parsed_dir.glob("*.md"))
            if not md_files:
                logging.error(f"Failed to parse {file_path.name}")
                results[file_path.name] = {"error": "Parsing failed or timed out", "entities": []}
                continue

            md_file = md_files[0]

            # Chunk
            temp_chunked_dir = Path(temp_dir) / "chunked"
            temp_chunked_dir.mkdir()
            chunks = chunk_markdown(md_file, temp_chunked_dir)

            if not chunks:
                logging.error(f"No chunks generated for {file_path.name}")
                results[file_path.name] = {"error": "No chunks", "entities": []}
                continue

            # Extract
            doc_entities = {"nodes": [], "edges": []}
            for i, chunk in enumerate(chunks):
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

    # Output to single reviewable file
    out_file = Path(f"{PROJECT_ROOT}/extraction_review.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    logging.info(f"Validation complete. Results saved to {out_file}")

if __name__ == "__main__":
    run_validation()
