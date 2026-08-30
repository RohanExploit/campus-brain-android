import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import asyncio
from unittest.mock import AsyncMock, MagicMock
import sys
sys.path.insert(0, f"{PROJECT_ROOT}")

from bots.telegram_bot import handle_message, ADMIN_ID, user_last_message_time
import bots.telegram_bot

# Bypass the allowlist for this smoke test so it still exercises the
# query-forwarding logic for non-admin synthetic user IDs.
bots.telegram_bot.auth_mgr.is_telegram_user_allowed = lambda tenant_id, user_id: True

# Mock httpx response to avoid needing the real backend
class MockResponse:
    def __init__(self, data, status=200):
        self._data = data
        self.status_code = status
    def json(self):
        return self._data
    def raise_for_status(self):
        import httpx
        if self.status_code != 200:
            request = httpx.Request("POST", "http://test")
            raise httpx.HTTPStatusError("Error", request=request, response=self)

async def run_tests():
    print("--- Telegram Bot Smoke Test ---")

    # 1 & 2. Basic Query (Admin vs Non-Admin)
    def create_mock_update(user_id, text):
        update = MagicMock()
        update.effective_user.id = user_id
        update.message.text = text
        update.message.reply_text = AsyncMock()
        update.message.reply_text.return_value = MagicMock(edit_text=AsyncMock())
        return update

    # Inject mock client logic

    class MockClient:
        def __init__(self, *args, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, exc_type, exc_val, exc_tb): pass
        async def post(self, url, json):
            return MockResponse({
                "query_type": "FACT",
                "answer": "42",
                "context_used": "Lots of context here."
            })

    bots.telegram_bot.httpx.AsyncClient = MockClient

    # Admin test
    print("\n1. Admin Query Test")
    admin_update = create_mock_update(int(ADMIN_ID), "What is life?")
    await handle_message(admin_update, None)
    admin_response = admin_update.message.reply_text.return_value.edit_text.call_args[0][0]
    print(f"Admin response contains context? {'(Admin)' in admin_response}")

    # Non-Admin test
    print("\n2. Non-Admin Query Test")
    user_last_message_time.clear() # reset rate limits
    normal_update = create_mock_update(12345, "What is life?")
    await handle_message(normal_update, None)
    normal_response = normal_update.message.reply_text.return_value.edit_text.call_args[0][0]
    print(f"Normal response contains context? {'(Admin)' in normal_response}")

    # 3. Rate Limit Check (Non-Admin)
    print("\n3. Rate Limit Check (Non-Admin)")
    normal_update2 = create_mock_update(12345, "Another fast question")
    await handle_message(normal_update2, None)
    rate_limit_res = normal_update2.message.reply_text.call_args[0][0]
    print(f"Throttled? {'wait' in rate_limit_res.lower()}")

    # 4. Rate Limit Bypass (Admin)
    print("\n4. Rate Limit Bypass (Admin)")
    admin_update2 = create_mock_update(int(ADMIN_ID), "Another fast question")
    await handle_message(admin_update2, None)
    admin_bypass_res = admin_update2.message.reply_text.return_value.edit_text.call_args[0][0]
    print(f"Admin bypassed throttle? {'42' in admin_bypass_res}")

    # 5. Fallback Surface Test
    print("\n5. Fallback Surface Test")
    class BrokenClient:
        def __init__(self, *args, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, exc_type, exc_val, exc_tb): pass
        async def post(self, url, json):
            import httpx
            raise httpx.RequestError("Connection Refused", request=httpx.Request("POST", "http://test"))

    bots.telegram_bot.httpx.AsyncClient = BrokenClient
    fallback_update = create_mock_update(99999, "Will this break?")
    await handle_message(fallback_update, None)
    fallback_res = fallback_update.message.reply_text.return_value.edit_text.call_args[0][0]
    print(f"Fallback response correctly handles exception? {'Cannot reach' in fallback_res}")

    # Testing router-level fallback metadata (when router catches it and returns FACT)
    class RouterFallbackClient:
        def __init__(self, *args, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, exc_type, exc_val, exc_tb): pass
        async def post(self, url, json):
            return MockResponse({
                "query_type": "FACT",
                "answer": "Fallback answer",
                "metadata": {"fallback_reason": "ollama_exception:ConnectError"}
            })

    bots.telegram_bot.httpx.AsyncClient = RouterFallbackClient
    meta_update = create_mock_update(11111, "Will this fallback?")
    await handle_message(meta_update, None)
    meta_res = meta_update.message.reply_text.return_value.edit_text.call_args[0][0]
    print(f"Fallback metadata displayed? {'routed as fallback' in meta_res}")

    # 6. Malformed input
    print("\n6. Malformed Input Test")
    try:
        malformed_update = create_mock_update(123, None)
        await handle_message(malformed_update, None)
        print("Bot handled missing text without crashing? Yes")
    except Exception as e:
        print(f"Bot crashed: {e}")

if __name__ == "__main__":
    asyncio.run(run_tests())
