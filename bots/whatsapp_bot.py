import os
import secrets
import logging
import httpx
import time
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel
from pathlib import Path
from dotenv import load_dotenv

import sys
sys.path.append(str(Path(__file__).resolve().parent.parent))
from auth.allowlist import AllowlistManager
from utils.logging_config import setup_logging

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

setup_logging()

API_URL = "http://localhost:8000/query"
RATE_LIMIT_SECONDS = 10
WHATSAPP_WEBHOOK_SECRET = os.getenv("WHATSAPP_WEBHOOK_SECRET")

if not WHATSAPP_WEBHOOK_SECRET:
    # The real Open-WA/Meta send-API integration below is still a stub (see TODO
    # in the handler), so we don't hard-fail startup yet -- but this must be set
    # before this bot is pointed at a live, network-reachable WhatsApp integration.
    logging.warning(
        "WHATSAPP_WEBHOOK_SECRET is not set. /webhook will reject all requests "
        "until it is configured. Set it in .env (e.g. via secrets.token_urlsafe(32))."
    )

app = FastAPI(title="WhatsApp Bot Webhook")
auth_mgr = AllowlistManager()
user_last_message_time = {}

class WhatsAppMessage(BaseModel):
    # Matches a generic structure that can adapt to Open-WA or Meta API
    sender_number: str
    text: str
    tenant_id: str = "tenant_1"

@app.post("/webhook")
async def whatsapp_webhook(request: Request, msg: WhatsAppMessage):
    # 0. Webhook Origin Verification
    provided_secret = request.headers.get("X-Webhook-Secret", "")
    if not WHATSAPP_WEBHOOK_SECRET or not secrets.compare_digest(provided_secret, WHATSAPP_WEBHOOK_SECRET):
        logging.warning("Rejected WhatsApp webhook request with invalid or missing X-Webhook-Secret header.")
        raise HTTPException(status_code=401, detail="Invalid webhook signature")

    # 1. Authorization Check
    if not auth_mgr.is_whatsapp_user_allowed(msg.tenant_id, msg.sender_number):
        logging.warning(f"Unauthorized WhatsApp access attempt from {msg.sender_number} for tenant {msg.tenant_id}")
        raise HTTPException(status_code=403, detail="Number not authorized for this tenant.")

    # 2. Rate Limiting (crucial for WhatsApp bots to prevent bans)
    now = time.time()
    last_time = user_last_message_time.get(msg.sender_number, 0)
    if now - last_time < RATE_LIMIT_SECONDS:
        logging.info(f"Rate limiting WhatsApp user {msg.sender_number}")
        # Return 200 so the WA server doesn't retry, but we drop the message processing
        return {"status": "rate_limited", "message": "Please wait before sending another message."}

    user_last_message_time[msg.sender_number] = now

    # 3. Forward to Main API
    try:
        async with httpx.AsyncClient(timeout=120.0) as client:
            response = await client.post(API_URL, json={
                "query": msg.text,
                "tenant_id": msg.tenant_id,
                # See telegram_bot: the sender was authenticated and then discarded.
                # Ignored by the API unless config.PII_ROLE_GATE is on.
                "user_id": msg.sender_number,
                "channel": "whatsapp",
            })
            response.raise_for_status()
            data = response.json()

            answer = data.get("answer", "No answer generated.")

            # Format message for WhatsApp (plain text, maybe some basic formatting)
            whatsapp_reply = f"*{data.get('query_type', 'UNKNOWN')}*\n\n{answer}"

            # TODO: Here we would call the Open-WA /sendText API to send `whatsapp_reply`
            # For now we simulate success
            logging.info(f"Replied to {msg.sender_number}: {whatsapp_reply[:50]}...")
            return {"status": "success", "reply": whatsapp_reply}

    except Exception as e:
        logging.error(f"Error querying main API from WhatsApp bot: {e}")
        raise HTTPException(status_code=500, detail="Internal Server Error")

if __name__ == "__main__":
    import uvicorn
    logging.info("Starting WhatsApp bot webhook server on port 8001...")
    uvicorn.run(app, host="0.0.0.0", port=8001)
