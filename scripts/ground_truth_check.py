import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import duckdb
conn = duckdb.connect(f'{PROJECT_ROOT}/data/tenants/tenant_1/tabular.duckdb', read_only=True)

print('=== students schema ===')
print(conn.execute('DESCRIBE students').fetchall())
print()

print('=== student_subjects schema ===')
print(conn.execute('DESCRIBE student_subjects').fetchall())
print()

print('=== student count ===')
print(conn.execute('SELECT COUNT(*) FROM students').fetchone())
print()

print('=== Gaikwad Rohan ===')
print(conn.execute("SELECT roll_no, name, sgpa, result FROM students WHERE LOWER(name) LIKE '%gaikwad%' AND LOWER(name) LIKE '%rohan%'").fetchall())
print()

print('=== sample result values ===')
print(conn.execute("SELECT DISTINCT result FROM students LIMIT 20").fetchall())
print()

print('=== sample subject_codes (sem5) ===')
print(conn.execute("SELECT DISTINCT subject_code FROM student_subjects WHERE subject_code LIKE 'BT%5%' LIMIT 10").fetchall())
print()

print('=== sem5 failure grades ===')
print(conn.execute("SELECT DISTINCT grade FROM student_subjects WHERE subject_code LIKE 'BT%5%'").fetchall())
print()

print('=== students failing 2+ sem5 subjects (ground truth) ===')
q = """
SELECT COUNT(*) FROM (
  SELECT roll_no
  FROM student_subjects
  WHERE subject_code LIKE 'BT%5%'
    AND grade = 'FF'
  GROUP BY roll_no
  HAVING COUNT(*) >= 2
) t
"""
print(conn.execute(q).fetchone())
print()

print('=== total students with any FAIL result ===')
print(conn.execute("SELECT COUNT(*) FROM students WHERE result LIKE '%FAIL%'").fetchone())
print()

print('=== students who passed everything (result = PASS) ===')
print(conn.execute("SELECT COUNT(*) FROM students WHERE result = 'PASS'").fetchone())
print()

print('=== gaikwad rohan vijay sgpa ===')
print(conn.execute("SELECT roll_no, name, sgpa, result FROM students WHERE roll_no = '23067571242048'").fetchall())
print()

print('=== highest sgpa ===')
print(conn.execute("SELECT MAX(sgpa) FROM students WHERE sgpa IS NOT NULL").fetchone())
print()

print('=== any student with sgpa > 9.5 ===')
print(conn.execute("SELECT COUNT(*) FROM students WHERE sgpa > 9.5").fetchone())
print()

print('=== students with seat_cancelled ===')
print(conn.execute("SELECT COUNT(*) FROM students WHERE seat_cancelled = true").fetchone())
print()

print('=== students by name patil ===')
print(conn.execute("SELECT roll_no, name FROM students WHERE LOWER(name) LIKE '%patil%' LIMIT 10").fetchall())

conn.close()
