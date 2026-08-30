"""Manual query smoke script (needs API on :8000). Not a pytest test."""
import httpx
import asyncio


async def run():
    async with httpx.AsyncClient() as client:
        res = await client.post(
            "http://127.0.0.1:8000/query",
            json={"query": "search For Gaikwad Rohan Vijay", "tenant_id": "tenant_1"},
        )
        print(res.json())


if __name__ == "__main__":
    asyncio.run(run())
