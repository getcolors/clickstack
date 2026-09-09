# clickstack

A tri-colour Package Skill (green, red, blue) that provisions **ClickStack** —
the ClickHouse observability stack: ClickHouse, MongoDB, the HyperDX
OpenTelemetry collector, and the HyperDX UI — on one VM through the shared `colors-compute` library, behind Caddy and Cloudflare.

```sh
npx skills add getcolors/clickstack
cp .agents/skills/package-clickstack-green/green ./green
chmod +x green
./green build
./green create --dry-run
```

The same deployment can run through the TypeScript (`package-clickstack-red`)
or Python (`package-clickstack-blue`) implementation — all three render
byte-identical artifacts from one `colors.yml`.

`build` and `create --dry-run` need no credentials and contact nothing, which
makes them the safe way to check a `colors.yml` edit.

## What it provisions

| Layer | Contents |
|---|---|
| Compute | One library-owned node with SSH/HTTP ingress, guarded S3/R2 state and managed or explicit external SSH identity. No extra private network is requested by default. |
| DNS | One proxied Cloudflare `A` record for `clickstack-host` |
| Server | Docker Compose: ClickHouse, MongoDB, HyperDX collector, HyperDX app, Caddy |

Caddy terminates TLS and routes one hostname two ways: OTLP/HTTP on the
standard `/v1/logs`, `/v1/traces`, `/v1/metrics` paths to the collector, and
everything else to the HyperDX UI. Point any OTLP exporter at
`https://<clickstack-host>` with the ingestion key as its `authorization`
header. Nothing but Caddy publishes a port.

## The SSH keypair

The deployment owns its machine key, per the workspace
[SSH Keypair Standard](https://github.com/getcolors/workspace/blob/main/standards/ssh-keypair.md).
Leave `vultr-ssh-keys` (or `digitalocean-ssh-keys`) out of `colors.yml` and the
library generates `~/.ssh/<profile>` on the first real `create`, registers it
at the provider under the profile name, and deletes it after a successful
`delete` — never before.

Consequences worth knowing before you clone a deployment elsewhere:

- The keypair lives in `~/.ssh`, not the checkout, so cloning a deployment
  repository does not carry machine access with it. Copy
  `~/.ssh/<profile>`(`.pub`) deliberately when access should move.
- A key on disk with no state is an error, never overwritten — it may be the
  only credential to a host that is still alive.
- A provider key named after the profile that this deployment's state does not
  own is an error too. If its fingerprint differs from yours, **do not delete
  it**.
- A changed machine identity requires a reviewed replacement workflow.

Supplying an external key reference selects external mode. The library never
generates or deletes that key. Supply `ssh-private-key-path` explicitly for access.

## Shared compute library

All three packages depend on `colors-compute`. They declare one node and
application ingress; provider templates, credentials, naming, state and key
lifecycle stay in the library. New provider support requires a library version
bump rather than another package template. The selected adapter must support
the requested security policy and an Ubuntu image suitable for the stack.

`clickstack-ssh-sources` and `clickstack-http-sources` are neutral CIDR options.
The existing provider-prefixed options remain compatible. The library refuses
provider changes on an existing deployment and requires remote S3 or R2 state.

The old `<profile>/clickstack-infrastructure.tfstate` is guarded: existing
installations require an explicit ownership migration or deletion with the
original package version. Removing state is not a migration.

## Configuration

`colors.yml` is the only file to edit, and holds non-secret values only.
Credentials are `COLORS_PAR_*` variables in the gitignored `.envrc.private`:
`COLORS_PAR_VULTR_API_KEY` or `COLORS_PAR_DO_TOKEN` for the selected provider,
`COLORS_PAR_CLOUDFLARE_API_TOKEN`, `COLORS_PAR_R2_ACCESS_KEY_ID`,
`COLORS_PAR_R2_SECRET_ACCESS_KEY`.

`clickstack-admin-email` names the login for the initial HyperDX team, which
convergence creates — the collector binds no OTLP receivers until a team
exists. The admin password, the team's ingestion key and the session secret are
all generated on the server, into `/etc/clickstack/admin.env`,
`/etc/clickstack/ingestion.env` and `/etc/clickstack/session.env`. Convergence
also writes a `~/.ssh/config` block, so `ssh <profile>` reaches the host with no
address or flags.

See [the configuration reference](skills/package-clickstack-green/references/configuration.md)
for every key.

## Development

```sh
cd green && bb test      # unit tests (canonical Clojure implementation)
cd green && bb golden    # render all four fixtures and diff against committed output
cd green && bb golden:accept  # regenerate after an intended change — read the diff first
cd red && bun test && bun run typecheck   # TypeScript implementation
cd blue && uv run pytest                  # Python implementation
./scripts/parity.sh      # all three colours render byte-identical trees, both providers
./scripts/launcher.sh    # launcher payload and profile-guard checks
```

`bb golden` covers keygen and opt-out mode separately on each provider (four
fixtures); read a golden diff after a pin bump rather than accepting it. Point the launchers at working trees with
`CLICKSTACK_LIB_ROOT`, `GREEN_LIB_ROOT` and `ONCE_LIB_ROOT`.

## License

MIT.
