# Configuration reference

`colors.yml` is the only file to edit. Keys are kebab-case and hold non-secret
values only. Credentials are `COLORS_PAR_<UPPER_SNAKE_KEY>` environment
variables in the gitignored `.envrc.private`.

## Required credentials

| Credential | Variable |
|---|---|
| Vultr API key (`provider-compute: vultr`) | `COLORS_PAR_VULTR_API_KEY` |
| DigitalOcean token (`provider-compute: digitalocean`) | `COLORS_PAR_DO_TOKEN` |
| Cloudflare API token (zone-scoped) | `COLORS_PAR_CLOUDFLARE_API_TOKEN` |
| R2 state backend | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |

No HyperDX credential is here. Three values are generated on the server, all
mode 0600 and all written under `creates:` so a re-converge never rotates them:
the admin password in `/etc/clickstack/admin.env`, the team ingestion key in
`/etc/clickstack/ingestion.env`, and `EXPRESS_SESSION_SECRET` in
`/etc/clickstack/session.env`. None reaches a tracked or generated file. Only the selected compute provider's
credential is required; the other is ignored.

The session secret is not optional hardening. HyperDX falls back to a constant
published in its own repository when the variable is unset, so a deployment
without it signs session cookies with a value anybody can read.

## Keys

| Key | Meaning |
|---|---|
| `profile` | Names the work directory, state keys, cloud resources, and the machine keypair. Never overlay it. |
| `workdir` | Generated-output root, conventionally `.colors`. |
| `provider-compute` | Adapter selected by the pinned colors-compute library; defaults to `vultr`. |
| `provider-dns` | Must be `cloudflare`. |
| `provider-backend` | `s3` or `r2` remote state, with deployment-wide coordination. |
| `compute-prevent-destroy` | Keep `true`; guards `delete`. |
| `clickstack-admin-email` | Login identity for the initial HyperDX team, created during convergence. The password is generated on the server. |
| `clickstack-host` | Public hostname. Its registrable domain must be a Cloudflare zone; the package manages the proxied A record and Caddy obtains TLS. |
| `clickstack-hyperdx-image` | HyperDX app image (tagged or digest-pinned). |
| `clickstack-otel-collector-image` | HyperDX OpenTelemetry collector image. |
| `clickstack-clickhouse-image` | ClickHouse server image. |
| `clickstack-mongo-image` | MongoDB image, HyperDX application state. |
| `clickstack-caddy-image` | Caddy image. |
| `r2-bucket` / `r2-endpoint` | State backend location (`r2` backend). |

### Vultr (`provider-compute: vultr`)

| Key | Meaning |
|---|---|
| `vultr-name` | Optional instance label; defaults to the profile. Letters, digits, `.`, `_`, `-` (1-63 characters). Updates in place and is never a hostname. |
| `vultr-region` / `vultr-plan` | Vultr region and plan. ClickHouse, HyperDX, MongoDB and the collector are colocated: 4 vCPU / 8 GiB is the realistic floor. |
| `vultr-os-id` | Numeric OS id; 2284 is Ubuntu 24.04 LTS x64. |
| `vultr-ssh-keys` | **Optional, and meaningful by its absence.** Omit it for keygen mode (below). Supplying an existing account key id opts out: the package then generates, validates and deletes no key material. |
| `vultr-ssh-sources` / `vultr-http-sources` | CIDR allowlists for the firewall (22 and 80/443). |

### DigitalOcean (`provider-compute: digitalocean`)

| Key | Meaning |
|---|---|
| `digitalocean-name` | Optional droplet name; defaults to the profile. Hostname-like (lowercase letters, digits, dots, hyphens; 1-63 characters), checked before any provider call. Renames in place, but the guest hostname cloud-init set at creation lags until a rebuild. |
| `digitalocean-region` / `digitalocean-size` | Droplet region and size; `s-4vcpu-8gb` is the realistic floor. `region` is ForceNew, `size` resizes in place. |
| `digitalocean-image` | Droplet image slug, e.g. `ubuntu-24-04-x64`. ForceNew. |
| `digitalocean-ssh-keys` | **Optional, and meaningful by its absence.** Omit it for keygen mode (below). Supplying an existing account key id or fingerprint opts out. |
| `digitalocean-ssh-sources` / `digitalocean-http-sources` | CIDR allowlists for the firewall (22 and 80/443). |

Single-host deployments request no additional private network by default.
Explicit network settings are resolved and checked by the library. Provider
names, credentials, images, sizes and capabilities are library-owned; the
Vultr and DigitalOcean fields above describe the included fixture examples.
AWS uses ambient AWS credentials, Azure the ambient Azure CLI session,
Google Application Default Credentials, and OCI its configured profile.
Other token adapters use the library's `COLORS_PAR_*` bindings. S3 also uses
the ambient AWS credential chain.

External key references additionally require `ssh-private-key-path` pointing
to the operator's existing private key. The library validates access without
replacing or deleting external key material.

### The firewall sources

The provider firewall is the load-bearing layer on both providers — inbound 22
from `<provider>-ssh-sources`, 80 and 443 from `<provider>-http-sources`,
nothing else; Ansible manages no host firewall for these ports. Every entry
must be a syntactically valid IPv4 or IPv6 CIDR and the SSH list must not be
empty, both checked before any provider call. An empty HTTP list is allowed
and means no public HTTP.

### State and provider changes

The library owns `<profile>/compute/shared.tfstate`, one node state and the
remote ownership journal. Every operation checks their identity before
compute mutation. An unreadable backend is an error, never empty state.
Changing providers on an existing deployment is refused; restore the original
provider and complete deletion before creating a replacement.

The old monolithic `<profile>/clickstack-infrastructure.tfstate` is an explicit
migration boundary. Its presence stops the new lifecycle. Do not delete or
rename remote state to bypass the guard. Use a reviewed ownership migration,
or the original package version to destroy the old deployment first.

## The machine keypair

The library owns the managed key lifecycle. With external key options absent the deployment owns its key, per the
workspace SSH Keypair Standard, through the selected adapter:

- The first real `create` generates `~/.ssh/<profile>` (ed25519, no passphrase,
  comment `<profile> managed by Colors`) and enforces `700` on `~/.ssh` and
  `600` on the private key on every real run.
- The compute stack declares the account key resource (`vultr_ssh_key` or
  `digitalocean_ssh_key`) named `<profile>` and references it by attribute, so
  ownership is decidable from state rather than from a name.
- Before applying, a REST preflight lists the account's keys with the selected
  provider's token. A key named after the profile that this deployment's state
  does not own stops the run.
- `delete` removes the local keypair **last**, only after the compute destroy
  succeeded. A failed delete leaves it, because it is still needed.
- `build` and `--dry-run` never read or create anything under `~/.ssh`; they
  render a fixed placeholder path so output stays byte-identical everywhere.

There is no application key-rotation verb. Use a reviewed replacement workflow
when changing an existing deployment identity.

## Ports and exposure

| Port | Exposure | Purpose |
|---|---|---|
| 22 | `<provider>-ssh-sources` | Key-only SSH for convergence and recovery |
| 80/443 | `<provider>-http-sources` | Caddy: HyperDX UI and OTLP/HTTP ingestion |
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

Convergence writes a `~/.ssh/config` block, so retrieval needs no address, no
user and no `-i` flag:

```sh
ssh <profile> 'cat /etc/clickstack/admin.env'
```

## Sending telemetry

```sh
key=$(ssh <profile> 'sed -n s/HYPERDX_API_KEY=//p /etc/clickstack/ingestion.env')
curl -X POST https://<clickstack-host>/v1/logs \
  -H 'content-type: application/json' -H "authorization: $key" \
  --data @payload.json
```

Any OTLP/HTTP exporter works with endpoint `https://<clickstack-host>` and an
`authorization` header carrying that key. OTLP/gRPC (4317) is not published;
use OTLP/HTTP.

## Operations

```sh
ssh <profile> 'cd /opt/clickstack && docker compose ps'
ssh <profile> '/usr/local/sbin/clickstack-setup'
ssh <profile> '/usr/local/sbin/clickstack-smoke'
ssh <profile> 'cd /opt/clickstack && docker compose logs --tail 100 otel-collector'
```

`clickstack-smoke` is the same end-to-end ingest proof convergence runs: it
sends one OTLP log and waits for the row to appear in `default.otel_logs`.

## Recovery

| Symptom | Cause | Action |
|---|---|---|
| Legacy compute state requires migration | The old monolithic state still exists | Complete an explicit ownership migration or delete with the original package version; never erase the state to bypass the guard |
| Compute lifecycle refused | Remote ownership, state, key or provider identity cannot be established | Inspect the library diagnostic and reconcile the existing deployment before retrying |
| Compute node unavailable | No owned live node address is available | Restore state access; application cleanup refuses a placeholder target |
| Managed key unavailable | State exists but this workstation lacks the key | Copy the original keypair deliberately; a newly generated key cannot access the existing host |
| Acceptance fails on the OTLP endpoint | Caddy or the collector is not up | `docker compose ps`, then the collector logs |
| Exporters get connection reset on 4317/4318 | No team exists, so the collector received no OpAMP config and bound no receivers | Run `clickstack-setup`, then confirm `/installation` reports `isTeamExisting: true` |
