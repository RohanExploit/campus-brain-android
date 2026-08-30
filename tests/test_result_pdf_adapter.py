import pytest
from models.canonical import StudentRecord
import adapters.result_pdf_adapter as result_pdf_adapter
from adapters.result_pdf_adapter import load_student_record

def test_load_student_record_valid(seeded_tenant, monkeypatch):
    # Hermetic: points the adapter's DATA_ROOT at a tmp_path tenant seeded with
    # synthetic rows instead of the real (gitignored, CI-absent) tenant data.
    monkeypatch.setattr(result_pdf_adapter, "DATA_ROOT", seeded_tenant.data_root)

    roll_no = seeded_tenant.roll_nos[0]
    student = load_student_record(seeded_tenant.tenant_id, roll_no)

    assert isinstance(student, StudentRecord)
    assert student.roll_no == roll_no
    assert student.sgpa is not None
    assert len(student.subjects) > 0

    # Check shape of subject
    subject = student.subjects[0]
    assert hasattr(subject, "code")
    assert hasattr(subject, "credit")
    assert hasattr(subject, "grade_point")


@pytest.mark.parametrize("bad_tenant", [
    "../../etc", r"..\..\x", "/abs/path", "tenant_1/../tenant_2", "..", "bad tenant",
])
def test_load_student_record_rejects_traversal_tenant_id(bad_tenant):
    # Security regression: tenant_id is validated before it is used to build the
    # duckdb path, so a traversal attempt raises ValueError (never touches disk
    # outside DATA_ROOT).
    with pytest.raises(ValueError):
        load_student_record(bad_tenant, "2267571242025")
