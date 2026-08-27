from conftest import fixture, optout
from package_clickstack_blue import validate


def test_fixture_is_valid():
    assert validate.state_errors(fixture()) == []


def test_optout_fixture_is_valid():
    assert validate.state_errors(optout()) == []


def test_machine_key_is_not_required():
    # The standard makes absence meaningful: requiring vultr-ssh-keys would
    # make every conforming deployment invalid.
    assert not any("vultr-ssh-keys" in e for e in validate.state_errors(fixture()))


def test_absent_machine_key_selects_keygen():
    assert validate.keygen(fixture()) is True
    assert validate.keygen(optout()) is False


def test_reports_all_errors():
    errors = validate.state_errors(fixture({
        "clickstack-host": "bad", "clickstack-hyperdx-image": "floating",
        "clickstack-admin-email": "not-an-email",
        "provider-dns": "other", "provider-compute": "digitalocean",
        "vultr-os-id": "2284"}))
    assert len(errors) >= 6
    for part in ["host", "image", "admin-email", "provider-dns", "vultr", "os-id"]:
        assert any(part in e for e in errors), part


def test_accepts_a_digest_pin():
    assert validate.state_errors(fixture(
        {"clickstack-caddy-image": "caddy@sha256:" + "a" * 64})) == []


def test_profile_overlay_is_refused():
    assert validate.env_errors({"COLORS_PAR_PROFILE": "other"})
    assert not validate.env_errors({})


def test_names_all_package_secrets():
    errors = "\n".join(validate.secret_errors(fixture()))
    for name in ["COLORS_PAR_VULTR_API_KEY", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
                 "COLORS_PAR_R2_ACCESS_KEY_ID", "COLORS_PAR_R2_SECRET_ACCESS_KEY"]:
        assert name in errors, name
    # The ingestion key is generated on the server, never supplied.
    assert "INGESTION" not in errors
