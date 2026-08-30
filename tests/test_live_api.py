import asyncio
import httpx

async def run_live():   # renamed from test_live: manual script, needs API on :8000
    print("=== LIVE API TEST ===")
    queries = [
        "search for damle sandip devshri",
        "fetch me the marks of saniya dipak gaikwad plz", # Brand new phrasing
        "lookup patil",
        "23063181242002" # Follow up for patil
    ]

    async with httpx.AsyncClient(timeout=30.0) as client:
        for q in queries:
            print(f"\nUser: {q}")
            res = await client.post(
                "http://localhost:8000/query",
                json={"query": q, "user_id": "1990648223", "chat_id": "1990648223"}
            )
            data = res.json()
            ans = data.get('answer', '')
            print(f"Bot:\n{ans}")
            print("-" * 40)

if __name__ == "__main__":
    asyncio.run(run_live())
