import asyncio
import time
from retrieval.tabular_queries import extract_student_identifier, fuzzy_find_student, get_student_by_name

async def main():
    print("=== PART 1 & 2: Testing Extraction and Match ===")
    test_queries = [
        "search for gaikwad rohan vijay",
        "tell me about Gaikwad Rohan Vijay",
        "lookup gaikwad rohan",
        "kindly share info on rohan vijay gaikwad",
        "iska result batao gaikwad ka",
        "23067571242048"
    ]

    for q in test_queries:
        print(f"\nQuery: '{q}'")
        t0 = time.time()
        ext = await extract_student_identifier(q)
        t1 = time.time()
        print(f"Extracted JSON: {ext} (Latency: {t1-t0:.2f}s)")

        # Test full end-to-end to see fuzzy match and DB pull
        t0 = time.time()
        res = await get_student_by_name(q, "tenant_1")
        t1 = time.time()
        print(f"Full DB Lookup Latency (LLM + DB): {t1-t0:.2f}s")
        # Print a snippet of the result to verify success without flooding logs
        print(f"Result Snippet: {res[:150]}")

    print("\n=== PART 3: Testing Disambiguation ===")
    # Find an ambiguous name to test. Let's just create a mock list and pass it to fuzzy directly
    mock_db = [
        ("23067571242048", "GAIKWAD ROHAN VIJAY"),
        ("23067571242099", "GAIKWAD ROHAN VINOD"),
        ("11111", "SMITH JOHN")
    ]
    matches = fuzzy_find_student("Gaikwad Rohan", mock_db, threshold=75)
    print("Fuzzy matches for 'Gaikwad Rohan':")
    for m in matches:
        print(f" - {m}")

    print("\nEnd-to-End simulation of disambiguation (by overriding the DB locally):")
    # Instead of overriding DB, let's just query a known vague name like "Patil" or "Deshmukh" which surely exists multiple times in this dataset.
    res = await get_student_by_name("lookup patil", "tenant_1")
    print("Result for 'lookup patil':")
    print(res)

if __name__ == "__main__":
    asyncio.run(main())
