# Configuration reference

`colors.yml` is the only file to edit. Keys are kebab-case and hold non-secret
values only. Credentials are `COLORS_PAR_<UPPER_SNAKE_KEY>` environment
variables in the gitignored `.envrc.private`.

## Required credentials

| Credential | Variable |
|---|---|
| Vultr API key | `COLORS_PAR_VULTR_API_KEY` |
| Cloudflare API token (zone-scoped) | `COLORS_PAR_CLOUDFLARE_API_TOKEN` |
| R2 state backend | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |

Neither the HyperDX admin password nor the ingestion key is here. Convergence
generates the password into `/etc/clickstack/admin.env` (mode 0600), creates
the initial HyperDX team with it, and writes the team's ingestion key into
`/etc/clickstack/ingestion.env` (mode 0600). Neither ever reaches a tracked or
generated file.

## Keys

| Key | Meaning |
|---|---|
| `profile` | Names the work directory, state keys, cloud resources, and the machine keypair. Never overlay it. |
| `workdir` | Generated-output root, conventionally `.colors`. |
| `provider-compute` | Must be `vultr`. |
| `provider-dns` | Must be `cloudflare`. |
| `provider-backend` | `local`, `s3`, or `r2`. |
| `compute-prevent-destroy` | Keep `true`; guards `delete`. |
| `clickstack-admin-email` | Login identity for the initial HyperDX team, created during convergence. The password is generated on the server. |
| `clickstack-host` | Public hostname. Its registrable domain must be a Cloudflare zone; the package manages the proxied A record and Caddy obtains TLS. |
| `clickstack-hyperdx-image` | HyperDX app image (tagged or digest-pinned). |
| `clickstack-otel-collector-image` | HyperDX OpenTelemetry collector image. |
| `clickstack-clickhouse-image` | ClickHouse server image. |
| `clickstack-mongo-image` | MongoDB image, HyperDX application state. |
| `clickstack-caddy-image` | Caddy image. |
| `vultr-name` | Instance label (updates in place; never a hostname). |
| `vultr-region` / `vultr-plan` | Vultr region and plan. ClickHouse, HyperDX, MongoDB and the collector are colocated: 4 vCPU / 8 GiB is the realistic floor. |
| `vultr-os-id` | Numeric OS id; 2284 is Ubuntu 24.04 LTS x64. |
| `vultr-ssh-keys` | **Optional, and meaningful by its absence.** Omit it for keygen mode (below). Supplying an existing account key id opts out: the package then generates, validates and deletes no key material. |
| `vultr-ssh-sources` / `vultr-http-sources` | CIDR allowlists for the firewall (22 and 80/443). |
| `r2-bucket` / `r2-endpoint` | State backend location (`r2` backend). |

## The machine keypair

With `vultr-ssh-keys` absent the deployment owns its key, per the workspace SSH
Keypair Standard:

- The first real `create` generates `~/.ssh/<profile>` (ed25519, no passphrase,
  comment `<profile> managed by Colors`) and enforces `700` on `~/.ssh` and
  `600` on the private key on every real run.
- The compute stack declares `vultr_ssh_key` named `<profile>` and references
  it by attribute, so ownership is decidable from state rather than from a
  name.
- Before applying, a REST preflight lists the Vultr account's keys. A key named
  after the profile that this deployment's state does not own stops the run.
- `delete` removes the local keypair **last**, only after the compute destroy
  succeeded. A failed delete leaves it, because it is still needed.
- `build` and `--dry-run` never read or create anything under `~/.ssh`; they
  render a fixed placeholder path so output stays byte-identical everywhere.

There is no rotation verb: Vultr key lists are ForceNew, so rotation is
`delete` then `create`.

## Ports and exposure

| Port | Exposure | Purpose |
|---|---|---|
| 22 | `vultr-ssh-sources` | Key-only SSH for convergence and recovery |
| 80/443 | `vultr-http-sources` | Caddy: HyperDX UI and OTLP/HTTP ingestion |
| 4317/4318/13133 | loopback only | Collector OTLP and health, reached through Caddy |
| 8080/8000 | loopback only | HyperDX UI and API, reached through Caddy |
| 8123 | loopback only | ClickHouse HTTP |

## The initial team, and why convergence creates it

HyperDX configures the collector over OpAMP, and it pushes no configuration
until a team exists. Until then the collector runs with **no OTLP receivers at
all**: 4317 and 4318 are unbound and every exporter gets a connection reset. A
ClickStack nobody has signed into is therefore not a running deployment, so
`create` registers the first user rather than waiting for a human.

That also fixes where the ingestion key comes from. It is the team's `apiKey`,
minted by the app, so it cannot be chosen in advance — `clickstack-setup` reads
it back after registration and writes it to `/etc/clickstack/ingestion.env`,
recreating the app and collector when it changes. Both steps are idempotent:
`/installation` reports whether a team exists, and the key is rewritten only
when it actually differs.

Retrieve the credentials over SSH:

```sh
ssh -i ~/.ssh/<profile> root@SERVER 'cat /etc/clickstack/admin.env'
```

## Sending telemetry

```sh
key=$(ssh root@SERVER 'sed -n s/HYPERDX_API_KEY=//p /etc/clickstack/ingestion.env')
curl -X POST https://<clickstack-host>/v1/logs \
  -H 'content-type: application/json' -H "authorization: $key" \
  --data @payload.json
```

Any OTLP/HTTP exporter works with endpoint `https://<clickstack-host>` and an
`authorization` header carrying that key. OTLP/gRPC (4317) is not published;
use OTLP/HTTP.

## Operations

```sh
ssh -i ~/.ssh/<profile> root@SERVER 'cd /opt/clickstack && docker compose ps'
ssh -i ~/.ssh/<profile> root@SERVER '/usr/local/sbin/clickstack-setup'
ssh -i ~/.ssh/<profile> root@SERVER '/usr/local/sbin/clickstack-smoke'
ssh -i ~/.ssh/<profile> root@SERVER 'cd /opt/clickstack && docker compose logs --tail 100 otel-collector'
```

`clickstack-smoke` is the same end-to-end ingest proof convergence runs: it
sends one OTLP log and waits for the row to appear in `default.otel_logs`.

## Recovery

| Symptom | Cause | Action |
|---|---|---|
| `does not hold the machine key` | State exists, `~/.ssh/<profile>` does not — a fresh clone or a new workstation | Copy the keypair from where the deployment was created; a regenerated key cannot reach the existing host |
| `no compute state is readable` | Key on disk, no state — an interrupted create or an incomplete delete | Verify at Vultr that no host survives, then remove `~/.ssh/<profile>`(`.pub`) and retry |
| `already has an SSH key named …` and it matches yours | A previous delete left the provider key | Verify no host survives, delete that key at Vultr, retry |
| `already has an SSH key named …` and it does not match | A foreign key shares the name | Do not delete it. Investigate, or change `profile` |
| Acceptance fails on the OTLP endpoint | Caddy or the collector is not up | `docker compose ps`, then the collector logs |
| Exporters get connection reset on 4317/4318 | No team exists, so the collector received no OpAMP config and bound no receivers | Run `clickstack-setup`, then confirm `/installation` reports `isTeamExisting: true` |
