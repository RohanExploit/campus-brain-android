import asyncio
from retrieval.router import QueryRouter

async def run_queries():   # renamed from test_queries: manual script (needs data+Ollama); see test_sql_route.py for real tests
    router = QueryRouter()

    questions = [
        "What is the average SGPA?",
        "How many students failed in subject BTCOC501?",
        "List all students with SGPA below 6.5"
    ]

    # We might need to mock classify_query if Ollama 500s
    # but we'll try the real one first. If it fails, we fallback to TABULAR for testing.
    original_classify = router.classify_query

    async def mock_classify(query):
        # simple heuristic for test
        if any(w in query.lower() for w in ["average", "failed", "below", "how many"]):
            return "TABULAR"
        return await original_classify(query)

    for q in questions:
        print(f"\nQ: {q}")
        try:
            qtype, ctx = await router.route_query(q)
            if qtype != "TABULAR":
                print(f"Ollama classification failed/returned {qtype}. Retrying with Mock...")
                router.classify_query = mock_classify
                qtype, ctx = await router.route_query(q)
                router.classify_query = original_classify # restore

            print(f"Routed as: {qtype}")
            print(f"Answer Context:\n{ctx}")
        except Exception as e:
            print(f"Error: {e}")

if __name__ == '__main__':
    asyncio.run(run_queries())
