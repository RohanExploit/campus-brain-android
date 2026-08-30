import urllib.request
import json
import time

API_URL = "http://127.0.0.1:8000/query"

QUERIES = [
    "What is RAG-MicroSim?",
    "What is connected to High-frequency trading?",
    "What are the main themes of the dataset?"
]

def test_query(query_text):
    print("\n=======================================================")
    print(f"QUERY: {query_text}")
    print("=======================================================")

    req = urllib.request.Request(
        API_URL,
        data=json.dumps({"query": query_text, "tenant_id": "tenant_1"}).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )

    start_time = time.time()
    try:
        # Increase timeout to 120s to allow Ollama generation to finish
        with urllib.request.urlopen(req, timeout=120) as r:
            res = json.loads(r.read().decode())
            elapsed = time.time() - start_time

            print(f"CLASSIFICATION: {res.get('query_type')}")
            print(f"TIME TAKEN: {elapsed:.2f}s")
            print("-------------------------------------------------------")
            print(f"CONTEXT SNIPPET:\n{res.get('context_used')}")
            print("-------------------------------------------------------")
            print(f"ANSWER:\n{res.get('answer')}")
            print("=======================================================\n")

    except Exception as e:
        print(f"FAILED: {e}")

if __name__ == "__main__":
    print("Starting LightRAG Router End-to-End Test...")
    for q in QUERIES:
        test_query(q)
        # Sleep briefly between queries to avoid overwhelming the local LLM
        time.sleep(2)
