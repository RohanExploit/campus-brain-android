import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import json
import time
import requests
import asyncio
from openai import AsyncOpenAI
import os
from dotenv import load_dotenv

load_dotenv(f"{PROJECT_ROOT}/.env")

OLLAMA_API_URL = "http://127.0.0.1:11434/api/generate"
OLLAMA_MODEL = "qwen3:4b-instruct-2507-q4_K_M"

NVIDIA_API_KEY = os.environ.get("NVIDIA_API_KEY")
client = AsyncOpenAI(base_url="https://integrate.api.nvidia.com/v1", api_key=NVIDIA_API_KEY)
NIM_MODEL = "meta/llama-3.1-70b-instruct"

def ollama_extract(text: str):
    entity_types = "Person, Organization, Document, Policy, Project, Date, Amount, Location"
    relation_types = "WORKS_ON, REPORTS_TO, MENTIONED_IN, RELATED_TO, GOVERNED_BY, PART_OF"
    prompt = f"""
You are an intelligent assistant that helps a human analyst extract knowledge graph entities and relationships from a text document.

-Goal-
Given a text document, extract all entities that match the entity specification and all relationships between them.
Entity specification (STRICTLY USE ONLY THESE): {entity_types}
Relationship specification (STRICTLY USE ONLY THESE): {relation_types}

-Steps-
1. Extract all named entities that match the predefined entity specification.
2. For each entity, identify if it has any relationships with other extracted entities using ONLY the allowed relationship specification.
3. If a relationship doesn't perfectly match, use "RELATED_TO".

Output strictly as a JSON object with 'nodes' and 'edges'. Do NOT invent new node types or relation types.

-Real Data-
Text: {text}
Output:"""
    payload = {
        "model": OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "format": "json",
        "options": {"num_ctx": 2048}
    }
    start = time.time()
    try:
        res = requests.post(OLLAMA_API_URL, json=payload, timeout=60).json()
        ans = json.loads(res["response"])
        lat = time.time() - start
        return ans, lat
    except Exception as e:
        return str(e), time.time() - start

async def nim_extract(text: str):
    entity_types = "Person, Organization, Document, Policy, Project, Date, Amount, Location"
    relation_types = "WORKS_ON, REPORTS_TO, MENTIONED_IN, RELATED_TO, GOVERNED_BY, PART_OF"
    prompt = f"""
You are an intelligent assistant that helps a human analyst extract knowledge graph entities and relationships from a text document.

-Goal-
Given a text document, extract all entities that match the entity specification and all relationships between them.
Entity specification (STRICTLY USE ONLY THESE): {entity_types}
Relationship specification (STRICTLY USE ONLY THESE): {relation_types}

-Steps-
1. Extract all named entities that match the predefined entity specification.
2. For each entity, identify if it has any relationships with other extracted entities using ONLY the allowed relationship specification.
3. If a relationship doesn't perfectly match, use "RELATED_TO".

Output strictly as a JSON object with 'nodes' and 'edges'. Do NOT invent new node types or relation types.

-Real Data-
Text: {text}
Output:"""
    start = time.time()
    try:
        completion = await client.chat.completions.create(
            model=NIM_MODEL,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.2,
            top_p=0.7,
            max_tokens=1024,
            response_format={"type": "json_object"}
        )
        ans = json.loads(completion.choices[0].message.content)
        lat = time.time() - start
        return ans, lat
    except Exception as e:
        return str(e), time.time() - start

def ollama_generate(query: str, context: str):
    prompt = f"Answer the user's query based ONLY on the provided context.\nContext:\n{context}\nQuery: {query}\nAnswer:"
    payload = {
        "model": OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "options": {"num_ctx": 2048}
    }
    start = time.time()
    try:
        res = requests.post(OLLAMA_API_URL, json=payload, timeout=60).json()
        ans = res["response"].strip()
        lat = time.time() - start
        return ans, lat
    except Exception as e:
        return str(e), time.time() - start

async def nim_generate(query: str, context: str):
    prompt = f"Answer the user's query based ONLY on the provided context.\nContext:\n{context}\nQuery: {query}\nAnswer:"
    start = time.time()
    try:
        completion = await client.chat.completions.create(
            model=NIM_MODEL,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.2,
            top_p=0.7,
            max_tokens=1024
        )
        ans = completion.choices[0].message.content.strip()
        lat = time.time() - start
        return ans, lat
    except Exception as e:
        return str(e), time.time() - start

async def main():
    with open(f"{PROJECT_ROOT}/data/tenants/tenant_2/chunked/SESSION-STUDENT-DETAILS-2_chunks.json", "r") as f:
        chunks1 = json.load(f)
    with open(f"{PROJECT_ROOT}/data/tenants/tenant_1/chunked/Indian_Students_Data_chunks.json", "r") as f:
        chunks2 = json.load(f)
    with open(f"{PROJECT_ROOT}/data/tenants/tenant_2/chunked/2026 Rohan_Gaikwad - Copy_chunks.json", "r") as f:
        chunks3 = json.load(f)

    docs = [
        ("SESSION-STUDENT-DETAILS-2.xlsx (Chunk 1)", chunks1[1]["page_content"]),
        ("Indian_Students_Data (Chunk 1)", chunks2[1]["page_content"]),
        ("2026 Rohan_Gaikwad - Copy (Chunk 1)", chunks3[1]["page_content"])
    ]

    print("### 3. For each document, report side by side:")
    for name, text in docs:
        print(f"Document: {name}")

        q_ans, q_lat = ollama_extract(text)
        q_entities = [n.get("id", "") for n in q_ans.get("nodes", [])] if isinstance(q_ans, dict) else str(q_ans)
        print(f"qwen3:4b — Latency: {q_lat:.2f}s | Entities extracted: {q_entities} | Correct? [fill_me]")

        n_ans, n_lat = await nim_extract(text)
        n_entities = [n.get("id", "") for n in n_ans.get("nodes", [])] if isinstance(n_ans, dict) else str(n_ans)
        print(f"llama-3.1-70b — Latency: {n_lat:.2f}s | Entities extracted: {n_entities} | Correct? [fill_me]")
        print()

    print("### 4. Also run 3 real user-style queries against both")
    queries = [
        ("search for gaikwad rohan vijay", chunks3[1]["page_content"]),
        ("lookup patil", chunks2[1]["page_content"]),
        ("what is the gender and caste of SAGEETA GROVER?", chunks1[1]["page_content"])
    ]

    for query, context in queries:
        print(f"Query: {query}")

        q_ans, q_lat = ollama_generate(query, context)
        print(f"qwen3:4b — Latency: {q_lat:.2f}s | Answer: {q_ans}")

        n_ans, n_lat = await nim_generate(query, context)
        print(f"llama-3.1-70b — Latency: {n_lat:.2f}s | Answer: {n_ans}")
        print()

if __name__ == "__main__":
    asyncio.run(main())
