"""
Security regression: /upload/{upload_id}/process and /upload/{upload_id}/status
must reject a caller-supplied tenant_id/filename that doesn't match the
tenant/filename the upload_id was actually issued for (cross-tenant IDOR).

Tests _check_upload_ownership directly against a temp staging tree so no
real tenant data under data/tenants/ is touched.
"""
import json
import sys
from pathlib import Path

import pytest
from fastapi import HTTPException

sys.path.append(str(Path(__file__).resolve().parent.parent))

import api.main as main


@pytest.fixture
def staging_root(tmp_path, monkeypatch):
    """Point DATA_ROOT at a throwaway tree so staging/ (DATA_ROOT.parent/staging)
    lands under tmp_path, never inside the real data/tenants/ directory."""
    fake_data_root = tmp_path / "data" / "tenants"
    fake_data_root.mkdir(parents=True)
    monkeypatch.setattr(main, "DATA_ROOT", fake_data_root)
    return fake_data_root.parent


def _make_upload(staging_parent, upload_id, tenant_id, filename):
    staging_dir = staging_parent / "staging" / upload_id
    staging_dir.mkdir(parents=True)
    (staging_dir / "_owner.json").write_text(
        json.dumps({"tenant_id": tenant_id, "filename": filename})
    )
    return staging_dir


def test_matching_tenant_and_filename_passes(staging_root):
    upload_id = "u1"
    _make_upload(staging_root, upload_id, "tenant_1", "report.pdf")
    # should not raise
    main._check_upload_ownership(upload_id, "tenant_1", "report.pdf")


def test_cross_tenant_reuse_is_rejected(staging_root):
    upload_id = "u2"
    _make_upload(staging_root, upload_id, "tenant_1", "report.pdf")
    with pytest.raises(HTTPException) as exc:
        main._check_upload_ownership(upload_id, "tenant_2", "report.pdf")
    assert exc.value.status_code == 403


def test_filename_mismatch_is_rejected(staging_root):
    upload_id = "u3"
    _make_upload(staging_root, upload_id, "tenant_1", "report.pdf")
    with pytest.raises(HTTPException) as exc:
        main._check_upload_ownership(upload_id, "tenant_1", "other.pdf")
    assert exc.value.status_code == 403


def test_missing_sidecar_is_rejected(staging_root):
    # upload_id was never issued (or predates this fix) -> no _owner.json
    with pytest.raises(HTTPException) as exc:
        main._check_upload_ownership("never-issued", "tenant_1", "report.pdf")
    assert exc.value.status_code == 403


def test_corrupt_sidecar_is_rejected(staging_root):
    upload_id = "u4"
    staging_dir = staging_root / "staging" / upload_id
    staging_dir.mkdir(parents=True)
    (staging_dir / "_owner.json").write_text("not json")
    with pytest.raises(HTTPException) as exc:
        main._check_upload_ownership(upload_id, "tenant_1", "report.pdf")
    assert exc.value.status_code == 403
