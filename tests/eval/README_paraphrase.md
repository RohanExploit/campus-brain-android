# Paraphrase robustness

A benchmark asks each question once, in the wording its author chose. Real users
ask the same thing five ways. This harness measures the difference.

```bash
python tests/eval/run_paraphrase.py --answers tests/eval/runs/paraphrase.jsonl
python tests/eval/score_paraphrase.py --answers tests/eval/runs/paraphrase.jsonl
```

Same RUN/SCORE split as `run_eval.py`: answers are frozen to disk first, so scoring
is a free CPU replay and no scorer can be written after seeing the numbers it judges.
Run artifacts live under `tests/eval/runs/` and are gitignored — they contain raw
answer text, which on `tenant_1` includes student names.

## The three numbers

| Metric | Meaning |
|---|---|
| **per-phrasing** | Share of individual phrasings answered correctly. This is what a normal benchmark reports. |
| **stability** | Share of questions where *every* phrasing scores the same. A question right 4 times and wrong once is not 80% right — it is unreliable. |
| **all-correct** | Share of questions correct in every phrasing. This is the number to quote to an institution. |

Stability is the headline. Per-phrasing accuracy hides the failure mode that kills
pilots: a user who happens to type the unlucky wording first concludes the product
is broken, and never types the lucky one.

## Ground truth

`tenant_1` golds are exact figures computed from `analytics.duckdb`, not hand-judged:

```sql
-- 369 students
SELECT COUNT(DISTINCT roll_no) FROM exam_results;

-- 35 FAIL, 9.5%   (334 PASS, 90.5%)
SELECT 100.0 * COUNT(*) FILTER (WHERE result = 'FAIL') / COUNT(*)
FROM (SELECT DISTINCT roll_no, result FROM exam_results);

-- 16 students failed >= 2 subjects, 12 failed >= 3
SELECT COUNT(*) FROM (
  SELECT roll_no FROM exam_results WHERE is_fail
  GROUP BY roll_no HAVING COUNT(DISTINCT subject_code) >= 2);

-- 89 students with SGPA >= 8
SELECT COUNT(*) FROM (SELECT DISTINCT roll_no, sgpa FROM exam_results
                      WHERE sgpa IS NOT NULL) WHERE sgpa >= 8;

-- BTCOC502 is the most-failed subject (16)
SELECT subject_code, COUNT(*) FILTER (WHERE is_fail) f FROM exam_results
GROUP BY subject_code ORDER BY f DESC LIMIT 1;
```

## `anti_gold`

Some groups list `anti_gold` strings: answers we have seen the system give that are
specifically, known-to-be wrong (e.g. `100.0` for the fail percentage, which is what
`WHERE result = 'FAIL'`-before-aggregating produces). An answer matching an
`anti_gold` is scored wrong even if it would otherwise pass, and is reported
separately — these are regressions, not near-misses.

## Adding a group

One group is one *user intent*, with the phrasings a real user would type — including
the awkward ones. Do not paraphrase mechanically; the point is to sample how the
question actually gets asked. Include at least one phrasing that avoids the vocabulary
the templates key on ("paper" instead of "subject", "did not pass" instead of "failed"),
because that is where the failures live.
