# clickstack

A Green Package Skill that provisions **ClickStack** — the ClickHouse
observability stack: ClickHouse, MongoDB, the HyperDX OpenTelemetry collector,
and the HyperDX UI — on one Vultr instance, behind Caddy and Cloudflare.

```sh
npx skills add getcolors/clickstack
cp .agents/skills/package-clickstack-green/green ./green
chmod +x green
./green build
./green create --dry-run
```

`build` and `create --dry-run` need no credentials and contact nothing, which
makes them the safe way to check a `colors.yml` edit.

## What it provisions

| Layer | Contents |
|---|---|
| Compute | One Vultr instance, a firewall opening 22/80/443, and — in keygen mode — the account SSH key named after the profile |
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
Leave `vultr-ssh-keys` out of `colors.yml` and the package generates
`~/.ssh/<profile>` on the first real `create`, registers it at Vultr under the
profile name, and deletes it after a successful `delete` — never before.

Consequences worth knowing before you clone a deployment elsewhere:

- The keypair lives in `~/.ssh`, not the checkout, so cloning a deployment
  repository does not carry machine access with it. Copy
  `~/.ssh/<profile>`(`.pub`) deliberately when access should move.
- A key on disk with no state is an error, never overwritten — it may be the
  only credential to a host that is still alive.
- A Vultr key named after the profile that this deployment's state does not own
  is an error too. If its fingerprint differs from yours, **do not delete it**.
- Rotation is a rebuild: Vultr key lists are ForceNew.

Supplying `vultr-ssh-keys` opts out entirely; the package then generates,
validates, and deletes nothing.

## Configuration

`colors.yml` is the only file to edit, and holds non-secret values only.
Credentials are `COLORS_PAR_*` variables in the gitignored `.envrc.private`:
`COLORS_PAR_VULTR_API_KEY`, `COLORS_PAR_CLOUDFLARE_API_TOKEN`,
`COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY`.

`clickstack-admin-email` names the login for the initial HyperDX team, which
convergence creates — the collector binds no OTLP receivers until a team
exists. The admin password and the team's ingestion key are both generated on
the server, into `/etc/clickstack/admin.env` and
`/etc/clickstack/ingestion.env`; retrieve them over SSH.

See [the configuration reference](skills/package-clickstack-green/references/configuration.md)
for every key.

## Development

```sh
bb test               # unit tests, including SSH-standard conformance
bb golden             # render both fixtures and diff against committed output
./scripts/launcher.sh # launcher payload and profile-guard checks
```

`bb golden` covers keygen and opt-out mode separately; read a golden diff after
a pin bump rather than accepting it.

## License

MIT.
