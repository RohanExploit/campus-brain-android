"""Central logging setup. Import and call setup_logging() instead of each module
calling logging.basicConfig (which previously ran in ~23 modules with no shared
format). Idempotent and env-tunable via LOG_LEVEL."""
import logging
import os

_CONFIGURED = False

def _coerce_level(level: str | int | None) -> str | int:
    """Turn a raw level (env string, int, or None) into something
    logging accepts. A numeric string like "10" becomes int 10 (DEBUG)
    instead of blowing up _checkLevel; a word level is upper-cased so
    "debug" resolves the same as "DEBUG"."""
    if level is None:
        level = os.environ.get("LOG_LEVEL", "INFO")
    if isinstance(level, str):
        return int(level) if level.isdigit() else level.upper()
    return level

def setup_logging(level: str | int | None = None) -> None:
    global _CONFIGURED
    resolved = _coerce_level(level)
    if _CONFIGURED:
        # basicConfig is a no-op once handlers exist, so honor an explicit
        # level on later calls (e.g. setup_logging("DEBUG") to raise verbosity)
        # by setting it directly rather than silently dropping it.
        if level is not None:
            logging.getLogger().setLevel(resolved)
        return
    logging.basicConfig(
        level=resolved,
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
    )
    _CONFIGURED = True
