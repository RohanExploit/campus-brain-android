"""Manual Ollama latency/JSON-mode probe (needs Ollama on :11434). Not a pytest test."""
import time


def run():
    import requests
    text = (
        "High-frequency trading (HFT) methods work on sub-second time scales and often cause "
        "complicated market dynamics that are difficult to explain. Retrieval-Augmented Generation "
        "(RAG) has evolved as a strategy to ground large language models (LLMs) with factual context."
    )
    prompt = f"Extract entities and relations as JSON from this text: {text}"
    payload = {
        "model": "qwen3:4b-instruct-2507-q4_K_M",
        "prompt": prompt,
        "stream": False,
        "format": "json",
        "options": {"num_ctx": 2048},
    }
    start = time.time()
    print("Starting request...")
    try:
        response = requests.post("http://localhost:11434/api/generate", json=payload, timeout=600)
        response.raise_for_status()
        print(f"Time taken: {time.time() - start:.2f}s")
        print(response.json().get("response", ""))
    except Exception as e:
        print(f"Error: {e}")


if __name__ == "__main__":
    run()
