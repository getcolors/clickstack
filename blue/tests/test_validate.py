import pytest
from conftest import fixture, optout, do_fixture, do_optout
from package_clickstack_blue import validate, compute

@pytest.mark.parametrize('factory',[fixture,optout,do_fixture,do_optout])
def test_fixture_valid(factory):
    assert validate.state_errors(factory()) == []

def test_compute_contract_is_library_owned():
    assert not hasattr(validate, 'compute_providers')
    assert compute.TOPOLOGY == [{'role': None, 'count': 1}]
    assert compute.requirements(fixture())['legacy_state_keys'] == ['clickstack-fixture/clickstack-infrastructure.tfstate']
    assert compute.requirements(fixture())['single_host'] is True

@pytest.mark.parametrize('updates',[{'provider-compute':'unsupported'},{'vultr-plan':None},{'vultr-ssh-sources':[]},{'vultr-http-sources':['not-a-cidr']}])
def test_invalid_compute_input_refused(updates):
    assert validate.state_errors(fixture(updates))

def test_application_validation_accumulates():
    errors = validate.state_errors(fixture({'clickstack-host':'bad','clickstack-admin-email':'bad','clickstack-hyperdx-image':'floating','provider-dns':'bad'}))
    for name in ['host','admin-email','image','provider-dns']:
        assert any(name in error for error in errors)

def test_external_mode_requires_an_explicit_private_path():
    assert validate.state_errors(optout({'ssh-private-key-path':None}))
    assert validate.keygen(fixture()) and not validate.keygen(optout())

def test_compute_credentials_are_deferred_to_owned_state_inspection():
    errors = '\n'.join(validate.secret_errors(fixture()))
    assert 'COLORS_PAR_VULTR_API_KEY' not in errors
    assert 'COLORS_PAR_CLOUDFLARE_API_TOKEN' in errors
    assert 'COLORS_PAR_R2_ACCESS_KEY_ID' in errors
    assert validate.tofu_env(fixture(), 'provider-compute') == {}
    assert validate.tofu_env(fixture(), 'provider-backend') == {'r2-access-key-id':'AWS_ACCESS_KEY_ID','r2-secret-access-key':'AWS_SECRET_ACCESS_KEY'}

def test_profile_overlay_refused():
    assert validate.env_errors({'COLORS_PAR_PROFILE':'other'})
