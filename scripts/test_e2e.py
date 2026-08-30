import httpx
import asyncio

async def run_app():
    print("Testing WhatsApp Bot Webhook...")

    # Wait for servers to start if this is run right after

    webhook_url = "http://127.0.0.1:8001/webhook"

    payload = {
        "sender_number": "whatsapp_user_456",
        "text": "What is the capital of France?",
        "tenant_id": "tenant_1"
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(webhook_url, json=payload)
            print(f"Status Code: {resp.status_code}")
            print(f"Response: {resp.json()}")
    except Exception as e:
        print(f"Failed to reach webhook: {e}")

if __name__ == "__main__":
    asyncio.run(run_app())
