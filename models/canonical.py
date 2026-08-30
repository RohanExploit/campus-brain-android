from pydantic import BaseModel
from typing import List, Optional

class SubjectRecord(BaseModel):
    code: str
    credit: float
    grade_point: float

class StudentRecord(BaseModel):
    roll_no: str
    sgpa: Optional[float]
    subjects: List[SubjectRecord]
