---
name: package-clickstack-green
description: Provisions ClickStack — ClickHouse, MongoDB, the HyperDX OpenTelemetry collector and UI — as a single-server observability stack on one Vultr instance.
license: MIT
---

# ClickStack with Green

Operate one ClickStack deployment from non-secret `colors.yml`. Read
[references/configuration.md](references/configuration.md) before changing
configuration or running a lifecycle operation.

One public host serves both halves of the stack: Caddy terminates TLS, proxies
OTLP/HTTP on `/v1/{logs,traces,metrics}` to the collector, and serves the
HyperDX UI on everything else. Point an OTLP exporter at
`https://<clickstack-host>` with the server-generated ingestion key as its
`authorization` header.

## Safety

- Keep credentials in gitignored `.envrc.private` as `COLORS_PAR_*` variables.
- Never set `COLORS_PAR_PROFILE` or edit/commit `.colors/`.
- Keep `compute-prevent-destroy: true`; deletion requires separate explicit
  authorization and a one-run environment override.
- Build and dry-run before a real create.
- Only Caddy 80/443 and key-only SSH are public; the ingestion key is generated
  on the server and never leaves it.

```sh
./green build
./green create --dry-run
./green create
```

## The machine keypair

The deployment owns its SSH key per the workspace SSH Keypair Standard. With no
`vultr-ssh-keys` in `colors.yml`, the first real `create` generates
`~/.ssh/<profile>`, registers it at Vultr under the profile name, and a
successful `delete` removes it last.

The key lives outside the checkout, so cloning the deployment repository
elsewhere does not carry access — copy `~/.ssh/<profile>`(`.pub`) deliberately.
A key with no state, or a Vultr key named after the profile that this
deployment's state does not own, stops the run: verify at the provider before
removing anything, and never delete a key whose fingerprint is not yours.
Rotation is a rebuild. Supplying `vultr-ssh-keys` opts out and the package then
touches no key material.

A real create ends with public HTTPS acceptance checks on the UI and the OTLP
endpoint; the end-to-end ingest proof — one OTLP record through the collector
into ClickHouse — runs on the server during convergence.
