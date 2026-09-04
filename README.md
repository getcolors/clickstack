# clickstack

A tri-colour Package Skill (green, red, blue) that provisions **ClickStack** —
the ClickHouse observability stack: ClickHouse, MongoDB, the HyperDX
OpenTelemetry collector, and the HyperDX UI — on one Vultr instance or one
DigitalOcean droplet, behind Caddy and Cloudflare.

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
| Compute | One Vultr instance or one DigitalOcean droplet (`provider-compute`), a provider firewall opening 22/80/443, and — in keygen mode — the account SSH key named after the profile. On DigitalOcean the droplet joins the region's default VPC, discovered at plan time |
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
package generates `~/.ssh/<profile>` on the first real `create`, registers it
at the provider under the profile name, and deletes it after a successful
`delete` — never before. Keygen mode works on both providers.

Consequences worth knowing before you clone a deployment elsewhere:

- The keypair lives in `~/.ssh`, not the checkout, so cloning a deployment
  repository does not carry machine access with it. Copy
  `~/.ssh/<profile>`(`.pub`) deliberately when access should move.
- A key on disk with no state is an error, never overwritten — it may be the
  only credential to a host that is still alive.
- A provider key named after the profile that this deployment's state does not
  own is an error too. If its fingerprint differs from yours, **do not delete
  it**.
- Rotation is a rebuild: machine key lists are ForceNew on both providers.

Supplying `<provider>-ssh-keys` opts out entirely; the package then generates,
validates, and deletes nothing.

## Two compute providers

`provider-compute` selects `vultr` or `digitalocean`. Each provider is a
template directory of its own, with its own credential and its own
provider-scoped keys (`vultr-region`, `vultr-plan`, `vultr-os-id`;
`digitalocean-region`, `digitalocean-size`, `digitalocean-image`; and
`<provider>-ssh-sources` / `<provider>-http-sources` on both). Keys of the
unselected provider are ignored, so one `colors.yml` can carry both.
`<provider>-name` is optional and defaults to the profile.

Switching providers is a rebuild, never an apply: a profile whose state already
holds a machine refuses a create or delete under a different `provider-compute`
until it is set back and deleted.

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
