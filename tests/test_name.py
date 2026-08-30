"""Manual name-lookup smoke script (needs DuckDB data + Ollama). Not a pytest test."""


def run():
    from retrieval.tabular_queries import get_student_by_name
    import asyncio
    print(asyncio.run(get_student_by_name("gaikwad rohan vijay", "tenant_1")))


if __name__ == "__main__":
    run()
