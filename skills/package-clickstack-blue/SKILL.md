---
name: package-clickstack-blue
description: Provisions ClickStack — ClickHouse, MongoDB, the HyperDX OpenTelemetry collector and UI — as a single-server observability stack on one Vultr instance.
license: MIT
---

# ClickStack with Blue

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
- Only Caddy 80/443 and key-only SSH are public; the admin password and the
  ingestion key are generated on the server and never leave it.

```sh
./blue build
./blue create --dry-run
./blue create
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

Convergence creates the initial HyperDX team named by `clickstack-admin-email`.
This is required, not cosmetic: HyperDX configures the collector over OpAMP and
sends nothing until a team exists, so before that the collector binds no OTLP
receivers at all. The ingestion key is that team's, written to
`/etc/clickstack/ingestion.env`. Convergence also writes a `~/.ssh/config`
block, so reading it and the generated admin password needs no address:

```sh
ssh <profile> 'cat /etc/clickstack/admin.env'
ssh <profile> 'cat /etc/clickstack/ingestion.env'
```

A real create ends with public HTTPS acceptance checks on the UI and the OTLP
endpoint; the end-to-end ingest proof — one OTLP record through the collector
into ClickHouse — runs on the server during convergence.
