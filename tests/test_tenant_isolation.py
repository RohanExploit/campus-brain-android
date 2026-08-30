"""
Security regression: cross-tenant path-traversal must be rejected.

A malicious tenant_id like '../../other_tenant' must never resolve to a path
outside DATA_ROOT, and every path-building entry point must refuse it.
"""
import sys
from pathlib import Path

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import config


MALICIOUS = [
    "../../other_tenant",
    "../other_tenant",
    "..\\..\\other_tenant",
    "..",
    "/etc/passwd",
    "e:/x",
    "R:/Startup research/Start up V2/data/tenants/tenant_2",
    "tenant_1/../tenant_2",
    "tenant 1",          # space not in the allowed pattern
    "tenant_1;drop",
    "",
]

VALID = ["tenant_1", "tenant_2", "acme-corp", "T_42"]


@pytest.mark.parametrize("bad", MALICIOUS)
def test_validate_tenant_id_rejects_traversal(bad):
    with pytest.raises(ValueError):
        config.validate_tenant_id(bad)


@pytest.mark.parametrize("good", VALID)
def test_validate_tenant_id_accepts_legit(good):
    assert config.validate_tenant_id(good) == good


@pytest.mark.parametrize("bad", MALICIOUS)
def test_tenant_dir_rejects_traversal(bad):
    with pytest.raises(ValueError):
        config.tenant_dir(bad)


def test_tenant_dir_stays_under_data_root():
    p = config.tenant_dir("tenant_1").resolve()
    assert str(p).startswith(str(config.DATA_ROOT.resolve()))
    # the key property: a valid tenant cannot escape DATA_ROOT
    assert config.DATA_ROOT.resolve() in p.parents or p == config.DATA_ROOT.resolve() / "tenant_1"


def test_traversal_cannot_escape_data_root_even_if_joined():
    # Even a naive DATA_ROOT / bad would escape; validate_tenant_id is what stops it.
    escaped = (config.DATA_ROOT / "../../other_tenant").resolve()
    assert not str(escaped).startswith(str(config.DATA_ROOT.resolve()))  # proves the danger is real
    with pytest.raises(ValueError):
        config.tenant_dir("../../other_tenant")            # proves we block it
