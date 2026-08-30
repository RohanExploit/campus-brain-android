import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import asyncio
import sys

sys.path.insert(0, f"{PROJECT_ROOT}")
from retrieval.router import QueryRouter
import retrieval.router

# Mock the URL to force a failure
retrieval.router.OLLAMA_API_URL = "http://127.0.0.1:9999/api/generate"

async def main():
    print("Testing router fallback...")
    router = QueryRouter()
    qtype, context, metadata = await router.route_query("Test query")
    print("\n--- Fallback Results ---")
    print(f"QType: {qtype}")
    print(f"Metadata: {metadata}")

if __name__ == "__main__":
    asyncio.run(main())
