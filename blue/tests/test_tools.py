import json

import pytest
from conftest import do_fixture, do_optout, fixture, optout
from package_clickstack_blue import tools


def spec_for(opts, file):
    return next(s for s in tools.ansible_specs(opts)
                if str(s["target"]).endswith(file))


def test_firewall_sources_parse():
    data = tools.infrastructure_data(fixture())
    assert tools.cidrs(data, "vultr-http-sources") == ["0.0.0.0/0", "::/0"]


def test_infrastructure_data_carries_the_ssh_mode():
    assert tools.infrastructure_data(fixture())["ssh-keygen"] is True
    assert tools.infrastructure_data(optout())["ssh-keygen"] is False
    assert tools.infrastructure_data(do_fixture())["ssh-keygen"] is True
    assert tools.infrastructure_data(do_optout())["ssh-keygen"] is False


def test_infrastructure_data_reads_the_selected_providers_keys():
    # The template interpolates one resolved name and one resolved list per
    # port, whichever provider they came from.
    data = tools.infrastructure_data(do_fixture({"digitalocean-ssh-sources": ["10.0.0.0/8"],
                                                 "vultr-ssh-sources": ["192.0.2.0/24"]}))
    assert data["ssh-sources-hcl"] == '["10.0.0.0/8"]'
    assert data["compute-name"] == "clickstack-digitalocean-fixture"
    assert tools.infrastructure_data(fixture())["compute-name"] == "clickstack-fixture"


def test_template_directory_follows_the_provider():
    assert tools.infrastructure_template(fixture())["name"] == "tools/infrastructure/vultr/main.tf"
    assert tools.infrastructure_template(do_fixture())["name"] == "tools/infrastructure/digitalocean/main.tf"
    assert 'provider = "digitalocean"' in tools.infrastructure_template(do_fixture())["content"]
    assert 'provider = "vultr"' in tools.infrastructure_template(fixture())["content"]
    # A registry entry without a template would pass every unit test and fail
    # the first build.
    with pytest.raises(FileNotFoundError):
        tools.infrastructure_template(fixture({"provider-compute": "hetzner"}))


def test_fallback_params_are_shaped_per_provider():
    assert tools.fallback_params(fixture()) == {
        "provider": "vultr", "ip": "192.0.2.10", "user": "root", "sudoer": "root",
        "name": "clickstack-fixture"}
    assert tools.fallback_params(do_fixture()) == {
        "provider": "digitalocean", "ip": "192.0.2.10", "user": "root", "sudoer": "root",
        "name": "clickstack-digitalocean-fixture"}


def test_a_real_create_refuses_a_missing_ip_output():
    # 192.0.2.10 is the documentation address build renders with; a real
    # converge must never fall back to it.
    refused = tools.resolved_compute({}, tools.fallback_params(fixture()), None)
    assert refused["blue/exit"] == 1
    assert "compute produced no ip output" in refused["blue/err"]
    assert tools.resolved_compute({}, tools.fallback_params(fixture()), {"name": "x"})["blue/exit"] == 1
    ok = tools.resolved_compute({}, tools.fallback_params(fixture()),
                                {"ip": "203.0.113.9", "provider": "vultr"})
    assert ok.get("blue/exit") is None
    assert ok["ip"] == "203.0.113.9"


async def test_a_delete_without_compute_skips_the_remote_cleanup(tmp_path):
    # No address in state means no host to clean up, and the inventory would
    # otherwise fall back to 192.0.2.10.
    result = await tools.ansible_step({**fixture(), "workdir": str(tmp_path), "blue/event": "delete"})
    assert result["blue/exit"] == 0
    assert result["clickstack/cleanup"] == "skipped-no-compute"


def test_dns_zone_is_registrable_domain():
    assert tools.zone(fixture()) == "example.com"


def test_dns_record_is_host_and_proxied():
    json_text = tools.dns_json({**fixture(), "ip": "192.0.2.10"})
    assert "clickstack.example.com" in json_text
    assert "192.0.2.10" in json_text
    assert '"proxied" : true' in json_text


def test_inventory_keeps_one_target():
    inventory = tools.inventory({**fixture(), "ip": "192.0.2.10"})
    assert "192.0.2.10" in inventory
    assert "clickstack-fixture" in inventory


def test_ansible_renders_the_whole_stack():
    targets = [str(s["target"]) for s in tools.ansible_specs(fixture())]
    for file in ["ansible.cfg", "main.yml", "cleanup.yml", "compose.yml",
                 "Caddyfile", "setup.sh", "smoke.sh", "inventory.json"]:
        assert any(t.endswith(file) for t in targets), file


def test_setup_carries_the_admin_email_and_no_password():
    # The team is created during convergence, so the login identity is
    # rendered; its password is generated on the server and must never reach a
    # rendered file.
    rendered = json.dumps(spec_for(fixture(), "main.yml")["data"])
    assert "admin@clickstack.example.com" in rendered
    assert "HYPERDX_ADMIN_PASSWORD=Cs-" not in rendered


async def test_acceptance_is_skipped_outside_a_real_create():
    for event in ["build", "delete"]:
        result = await tools.acceptance_step({**fixture(), "blue/event": event})
        assert result["blue/exit"] == 0


def test_a_wired_otlp_endpoint_tolerates_a_rejected_payload():
    # A live receiver that refuses an anonymous or malformed request is proof
    # the route exists; 404 and the 5xx family are not.
    assert "401" in tools.endpoint_wired
    assert "400" in tools.endpoint_wired
    assert "404" not in tools.endpoint_wired
    assert "502" not in tools.endpoint_wired
    assert "000" not in tools.endpoint_wired
