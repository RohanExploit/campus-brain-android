import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import json
import logging
from pathlib import Path
from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter

logging.basicConfig(level=logging.INFO)

def process_markdown_files(parsed_dir, chunked_dir):
    parsed_dir = Path(parsed_dir)
    chunked_dir = Path(chunked_dir)
    chunked_dir.mkdir(parents=True, exist_ok=True)

    headers_to_split_on = [
        ("#", "Header 1"),
        ("##", "Header 2"),
        ("###", "Header 3"),
    ]
    markdown_splitter = MarkdownHeaderTextSplitter(headers_to_split_on=headers_to_split_on)

    # We want reasonable chunk sizes for embedding
    text_splitter = RecursiveCharacterTextSplitter(
        chunk_size=1000,
        chunk_overlap=200
    )

    md_files = list(parsed_dir.glob("*.md"))
    logging.info(f"Found {len(md_files)} markdown files to chunk")

    total_chunks = 0
    for md_file in md_files:
        try:
            with open(md_file, "r", encoding="utf-8") as f:
                md_text = f.read()

            if not md_text.strip():
                logging.warning(f"Empty markdown file: {md_file.name}")
                continue

            md_header_splits = markdown_splitter.split_text(md_text)
            splits = text_splitter.split_documents(md_header_splits)

            # Serialize chunks
            chunks_data = []
            for i, split in enumerate(splits):
                chunk_meta = split.metadata
                chunk_meta["source"] = md_file.name
                chunk_meta["chunk_index"] = i
                chunks_data.append({
                    "page_content": split.page_content,
                    "metadata": chunk_meta
                })

            out_path = chunked_dir / f"{md_file.stem}_chunks.json"
            with open(out_path, "w", encoding="utf-8") as f:
                json.dump(chunks_data, f, indent=2)

            total_chunks += len(chunks_data)
            logging.info(f"Chunked {md_file.name} into {len(chunks_data)} chunks.")

        except Exception as e:
            logging.error(f"Error chunking {md_file.name}: {e}")

    logging.info(f"Chunking complete. Created {total_chunks} total chunks.")

if __name__ == "__main__":
    parsed_dir = f"{PROJECT_ROOT}/data/tenants/tenant_2/parsed"
    chunked_dir = f"{PROJECT_ROOT}/data/tenants/tenant_2/chunked"
    process_markdown_files(parsed_dir, chunked_dir)
