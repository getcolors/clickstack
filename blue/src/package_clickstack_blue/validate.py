"""Validation over desired state, the port of io.github.getcolors.clickstack.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from package_once_blue import ssh as once_ssh
from package_once_blue.validate import providers as once_providers

profile_par = par_name("profile")

# Every key desired state must carry. `vultr-ssh-keys` is deliberately absent:
# per the SSH Keypair Standard its *absence* selects keygen mode, where the
# package owns the keypair, and requiring it would make conforming deployments
# invalid.
required = [
    "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
    "compute-prevent-destroy", "clickstack-host", "clickstack-admin-email",
    "clickstack-hyperdx-image", "clickstack-otel-collector-image",
    "clickstack-clickhouse-image", "clickstack-mongo-image", "clickstack-caddy-image",
    "vultr-name", "vultr-region", "vultr-plan", "vultr-os-id",
    "vultr-ssh-sources", "vultr-http-sources",
    "r2-bucket", "r2-endpoint",
]

image_keys = [
    "clickstack-hyperdx-image", "clickstack-otel-collector-image",
    "clickstack-clickhouse-image", "clickstack-mongo-image", "clickstack-caddy-image",
]

host_re = re.compile(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
email_re = re.compile(r"[^@\s]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
image_re = re.compile(r"[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64})")


def _s(value) -> str:
    """Clojure's `str`: nil renders empty, booleans lowercase."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def missing(value) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def keygen(opts: dict) -> bool:
    """Whether this deployment owns its machine keypair. Delegates to ONCE, the
    standard's reference implementation, so one rule decides it everywhere."""
    return once_ssh.keygen(opts)


def env_errors(env: dict) -> list[str]:
    if _s(env.get(profile_par)):
        return [f"{profile_par} is set; profile must come from colors.yml only"]
    return []


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    errors += [f":{k} is required" for k in required if missing(opts.get(k))]
    if opts.get("provider-compute") != "vultr":
        errors.append(":provider-compute must be vultr")
    if opts.get("provider-dns") != "cloudflare":
        errors.append(":provider-dns must be cloudflare")
    if opts.get("provider-backend") not in ("local", "s3", "r2"):
        errors.append(":provider-backend must be local, s3, or r2")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    if not (missing(opts.get("clickstack-host"))
            or host_re.fullmatch(_s(opts.get("clickstack-host")))):
        errors.append(":clickstack-host must be a fully qualified hostname")
    if not (missing(opts.get("clickstack-admin-email"))
            or email_re.fullmatch(_s(opts.get("clickstack-admin-email")))):
        errors.append(":clickstack-admin-email must be an email address")
    for k in image_keys:
        v = opts.get(k)
        if not missing(v) and not image_re.fullmatch(_s(v)):
            errors.append(f":{k} must carry an explicit image tag or digest")
    os_id = opts.get("vultr-os-id")
    if not (missing(os_id) or (isinstance(os_id, int) and not isinstance(os_id, bool))):
        errors.append(":vultr-os-id must be Vultr's numeric operating-system id")
    return errors


def backend_secrets(opts: dict) -> list[str]:
    entry = once_providers["provider-backend"].get(str(opts.get("provider-backend")), {})
    return entry.get("secrets", [])


def secret_errors(opts: dict) -> list[str]:
    """Credentials a real create or delete needs. The HyperDX ingestion key is
    not here: it is generated on the server and never supplied by the
    operator."""
    keys = ["vultr-api-key", "cloudflare-api-token", *backend_secrets(opts)]
    return [f"required credential is not set: {par_name(k)}"
            for k in dict.fromkeys(keys) if missing(opts.get(k))]


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    if slot == "provider-compute":
        return {"vultr-api-key": "VULTR_API_KEY"}
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        entry = once_providers["provider-backend"].get(str(opts.get("provider-backend")), {})
        return entry.get("tofu-env", {})
    return {}
