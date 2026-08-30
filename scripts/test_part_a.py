"""
Part A: Tokenized name search — verify all three orderings resolve to roll 23067571242048
Also confirm single-token disambiguation ('patil') still works.
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import asyncio
import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
sys.path.insert(0, f'{PROJECT_ROOT}')


from retrieval.tabular_queries import get_student_by_name

async def main():
    # --- Part A tests ---
    test_cases = [
        ("gaikwad rohan vijay",      "23067571242048", "forward order"),
        ("rohan vijay gaikwad",      "23067571242048", "middle-first"),
        ("vijay gaikwad rohan",      "23067571242048", "last-name scrambled"),
        ("lookup patil",             None,             "single-token disambiguation (must list, not crash)"),
    ]

    for query, expected_roll, label in test_cases:
        print(f"\n{'='*60}")
        print(f"TEST: {label}")
        print(f"Query: {query!r}")
        result = await get_student_by_name(query, "tenant_1")
        print(f"Output:\n{result}")
        if expected_roll:
            if expected_roll in result:
                print(f"✅ PASS — roll {expected_roll} found in output")
            else:
                print(f"❌ FAIL — expected roll {expected_roll} NOT in output")

asyncio.run(main())
