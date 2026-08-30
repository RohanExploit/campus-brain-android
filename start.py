"""
Canonical entrypoint for the Campus Brain API.

Run with the project venv (system Python often lacks faiss/duckdb):
    venv/Scripts/python.exe start.py            # Windows
    ./venv/bin/python start.py                  # Linux/macOS

Binds 127.0.0.1 by default so the unauthenticated-by-default admin API is not
exposed to the network. Pass --host 0.0.0.0 only alongside REQUIRE_API_KEY=1.
"""
import argparse
import sys


def _preflight():
    """Fail fast with a clear message if heavy native deps are missing, instead
    of the opaque ModuleNotFoundError uvicorn raises mid-import."""
    missing = []
    for mod in ("faiss", "duckdb", "fastapi", "uvicorn"):
        try:
            __import__(mod)
        except ImportError:
            missing.append(mod)
    if missing:
        sys.stderr.write(
            "\n[start.py] Missing dependencies: "
            + ", ".join(missing)
            + "\nYou are probably using system Python. Run with the venv:\n"
            + "    venv/Scripts/python.exe start.py   (Windows)\n"
            + "    ./venv/bin/python start.py         (Linux/macOS)\n"
            + "Or install: pip install -r requirements.txt\n\n"
        )
        sys.exit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Start Campus Brain API")
    parser.add_argument("--host", default="127.0.0.1",
                        help="Host IP (default 127.0.0.1; use 0.0.0.0 only with REQUIRE_API_KEY=1)")
    parser.add_argument("--port", type=int, default=8000, help="Port")
    parser.add_argument("--reload", action="store_true",
                        help="Enable auto-reload (development only)")
    args = parser.parse_args()

    _preflight()

    import uvicorn
    uvicorn.run("api.main:app", host=args.host, port=args.port, reload=args.reload)
