import os
import logging
import httpx
import time
from pathlib import Path
from dotenv import load_dotenv
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, MessageHandler, filters, ContextTypes

import sys
sys.path.append(str(Path(__file__).resolve().parent.parent))
from auth.allowlist import AllowlistManager
from utils.logging_config import setup_logging

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

setup_logging()

TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN")
if not TOKEN:
    raise RuntimeError("TELEGRAM_BOT_TOKEN not set in .env")

API_URL = "http://localhost:8000/query"

ADMIN_ID = "1990648223"
TENANT_ID = "tenant_1"
user_last_message_time = {}
RATE_LIMIT_SECONDS = 5

http_client = httpx.AsyncClient(timeout=120.0)
auth_mgr = AllowlistManager()

def is_admin(user_id: int) -> bool:
    return str(user_id) == ADMIN_ID

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "Hello! I am the Campus Brain.\n"
        "Ask me anything about our internal documents."
    )

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id

    if not is_admin(uid) and not auth_mgr.is_telegram_user_allowed(TENANT_ID, str(uid)):
        logging.warning(f"Unauthorized Telegram access attempt from {uid}")
        await update.message.reply_text("Sorry, you are not authorized to use this bot.")
        return

    if not is_admin(uid):
        now = time.time()
        last_time = user_last_message_time.get(uid, 0)
        if now - last_time < RATE_LIMIT_SECONDS:
            await update.message.reply_text("⏳ Please wait a few seconds before asking another question.")
            return
        user_last_message_time[uid] = now

    user_query = update.message.text

    await context.bot.send_chat_action(chat_id=update.effective_chat.id, action="typing")
    thinking_msg = await update.message.reply_text("🤔 Thinking...")

    try:
        response = await http_client.post(API_URL, json={
            "query": user_query,
            "tenant_id": TENANT_ID,
            # Carry the sender through. Until now the bot authenticated the user and then
            # threw their identity away, so the API answered every allowlisted user
            # identically — including roster answers listing other students by name.
            # The API ignores these unless config.PII_ROLE_GATE is on.
            "user_id": uid,
            "channel": "telegram",
        })
        response.raise_for_status()
        data = response.json()

        answer = data.get("answer", "No answer generated.")
        qtype = data.get("query_type", "UNKNOWN")
        context_used = data.get("context_used", "")
        metadata = data.get("metadata", {})

        tag = {"FACT": "🔍 FACT", "LOCAL": "🔗 LOCAL", "GLOBAL": "🌐 GLOBAL", "TABULAR": "📊 TABULAR"}.get(qtype, qtype)
        text = f"[{tag}]\n\n{answer}"

        fallback_reason = metadata.get("fallback_reason")
        if fallback_reason:
            text += f"\n\n⚠️ *Note:* Request was routed as fallback due to: `{fallback_reason}`"

        # Provenance, admin-only for now. Source labels are raw document filenames and
        # at least one real tenant_1 document is named after a student ("Rutuja fees.md"),
        # so showing them to every allowlisted chat user would be a new PII surface —
        # a call for the owner, not for an unattended run. The operator dashboard shows
        # them unconditionally; non-admin chat output is byte-identical to before.
        sources = metadata.get("sources") or []
        if sources and is_admin(uid):
            listed = "\n".join(
                f"• {s.get('source')}" + (f" › {s['section']}" if s.get("section") else "")
                for s in sources[:5]
            )
            text += f"\n\n─── Sources (Admin) ───\n{listed}"

        if context_used and is_admin(uid) and answer.strip() != context_used.strip():
            text += "\n\n─── Context snippet (Admin) ───\n" + context_used[:400]

        await thinking_msg.edit_text(text)

    except httpx.HTTPStatusError as exc:
        await thinking_msg.edit_text(
            f"Backend returned error {exc.response.status_code}.\n"
            f"Detail: {exc.response.text[:200]}"
        )
    except httpx.RequestError as exc:
        await thinking_msg.edit_text(
            f"Cannot reach the Campus Brain API.\n"
            f"Make sure the FastAPI server is running on port 8000.\n"
            f"Error: {exc}"
        )
    except Exception as e:
        logging.exception("Unexpected error in handle_message")
        await thinking_msg.edit_text(f"An unexpected error occurred: {e}")

if __name__ == "__main__":

    app = ApplicationBuilder().token(TOKEN).build()

    app.add_handler(CommandHandler("start", start))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))

    logging.info("Telegram bot starting (Open access, rate limit %s, Admin: %s)", RATE_LIMIT_SECONDS, ADMIN_ID)
    app.run_polling()
