import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import asyncio
import sys
from pathlib import Path
from dotenv import load_dotenv

project_root = Path(f"{PROJECT_ROOT}")
sys.path.append(str(project_root))
load_dotenv(project_root / ".env")

from generation.answer import generate_answer

async def run_nvidia_api():
    print("Testing NVIDIA API via generate_answer...")
    context = "NVIDIA NIM provides highly optimized inference microservices for LLMs."
    query = "What is NVIDIA NIM?"

    try:
        response = await generate_answer(query, context, qtype="LOCAL")
        print(f"API Response:\n{response}")
    except Exception as e:
        print(f"Failed: {e}")

if __name__ == "__main__":
    asyncio.run(run_nvidia_api())
