"""Deterministic tabular-query intent classification, extracted from
retrieval/router.py so the routing decision is pure and unit-testable (no DB,
no LLM). route_query() dispatches on the returned intent; the decisions here
are byte-for-byte the same as the old inline if/elif cascade."""
import re
from dataclasses import dataclass, field


@dataclass(frozen=True)
class TabularIntent:
    kind: str                 # one of: name_search, dynamic_sql, average_sgpa,
                              # count_failures, below_sgpa, record_by_roll
    params: dict = field(default_factory=dict)


# Words that signal a single-student record lookup (a marksheet/result/grades
# for one named person), and aggregate markers that veto that interpretation
# (a query counting/listing/averaging over many students is NOT a name lookup).
# "cgpa"/"sgpa"/"gpa" are here because "What is the CGPA of <person>?" is a
# single-student lookup, but carried none of the old keywords, so it fell to the
# text-to-SQL generator — which emitted a per-subject SELECT without a GROUP BY
# and crashed with a DuckDB binder error in front of the user. The aggregate
# veto below is what keeps threshold questions ("students below 6 SGPA",
# "how many scored above 8 SGPA") out of the name-lookup path.
_LOOKUP_KW = ("result", "results", "record", "marksheet", "marks", "grade",
              "grades", "score", "scores", "details", "cgpa", "sgpa", "gpa")
_AGG_KW = ("how many", "count", "number of", "list", "which", "average",
           "percentage", "rate", "top ", "most", " all ", "every", "each",
           "below", "under", "above", "greater", "or more", "at least",
           "atleast", "highest", "lowest", "bottom", "topper", "rank")


def classify_tabular_intent(query: str) -> TabularIntent:
    q_lower = query.lower()

    # Route to dynamic SQL generator for complex/list queries
    if "search for" in q_lower or "list all" in q_lower or "which students" in q_lower or "at least" in q_lower or "atleast" in q_lower:
        # If it is a simple single student name search
        if "search for" in q_lower and not ("fail" in q_lower or "sgpa" in q_lower or "subject" in q_lower or "grade" in q_lower or "sem" in q_lower):
            return TabularIntent("name_search")
        else:
            return TabularIntent("dynamic_sql")
    # Single-student lookup: a personal-name query asking for a result / record /
    # marksheet / marks / grades / score — in ANY word order ("result of Rohan
    # Vijay gaikwad", "rohan gaikwad result", "gaikwad rohan marksheet"). These
    # MUST use the fuzzy name_search path, never the text-to-SQL generator: that
    # generator emits an exact, case-sensitive `name = '...'` match and misses
    # reordered/differently-cased names (the DB stores "SURNAME NAME MIDDLE" in
    # upper case). name_search LIKE-matches each name token independently, so
    # order and case don't matter. Guarded against aggregate phrasings that
    # merely mention "result/marks" ("how many students ... result").
    elif (any(k in q_lower for k in _LOOKUP_KW)
          and not any(k in q_lower for k in _AGG_KW)):
        roll = re.search(r'(\d{10,15})', query)
        return TabularIntent("record_by_roll", {"roll": roll.group(1)}) if roll else TabularIntent("name_search")
    elif "average sgpa" in q_lower:
        match = re.search(r'subject\s+(BT\w+)', query, re.IGNORECASE)
        return TabularIntent("average_sgpa", {"subject": match.group(1) if match else None})
    elif "fail" in q_lower:
        if "how many" in q_lower or "count" in q_lower or "number" in q_lower:
            match = re.search(r'subject\s+(BT\w+)', query, re.IGNORECASE)
            return TabularIntent("count_failures", {"subject": match.group(1) if match else None})
        else:
            return TabularIntent("dynamic_sql")
    elif "below" in q_lower and "sgpa" in q_lower:
        # Pull the threshold tied to "below"/"under"/"sgpa" — not just the
        # first number in the query, which could be a semester or year
        # (e.g. "semester 3 students below 6 sgpa" must give 6, not 3).
        # Fall back to the first decimal, then a 6.0 default.
        match = (re.search(r'(?:below|under|sgpa)\D{0,10}(\d+(?:\.\d+)?)', q_lower)
                 or re.search(r'(\d+\.\d+)', query))
        threshold = float(match.group(1)) if match else 6.0
        return TabularIntent("below_sgpa", {"threshold": threshold})
    elif "record" in q_lower or "roll" in q_lower or "student" in q_lower or "score" in q_lower:
        match = re.search(r'(\d{10,15})', query)
        if match:
            return TabularIntent("record_by_roll", {"roll": match.group(1)})
        else:
            return TabularIntent("name_search")
    else:
        return TabularIntent("dynamic_sql")
