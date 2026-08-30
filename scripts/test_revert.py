import urllib.request
import json
import time

API_URL = "http://127.0.0.1:8000/query"

QUERIES = [
    "search for gaikwad rohan vijay",
    "lookup patil",
    "22051470"
]

def run_query(query_text):
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
        with urllib.request.urlopen(req, timeout=120) as r:
            res = json.loads(r.read().decode())
            elapsed = time.time() - start_time

            print(f"CLASSIFICATION: {res.get('query_type')}")
            print(f"TIME TAKEN: {elapsed:.2f}s")
            print("-------------------------------------------------------")
            print(f"ANSWER:\n{res.get('answer')}")
            print("=======================================================\n")

    except Exception as e:
        print(f"FAILED: {e}")

if __name__ == "__main__":
    for q in QUERIES:
        run_query(q)
        time.sleep(2)
