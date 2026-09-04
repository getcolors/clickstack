# CLAUDE.md

## Repository

`clickstack` is a tri-colour Package Skill (green, red, blue) for a ClickStack
observability server on one Vultr instance or one DigitalOcean droplet.
OpenTofu manages the machine, a provider firewall (22/80/443), and a proxied
Cloudflare A record; Ansible converges a Docker Compose stack of ClickHouse,
MongoDB, the HyperDX OpenTelemetry collector, the HyperDX app, and Caddy.

One public host carries both halves: Caddy serves the HyperDX UI and proxies
OTLP/HTTP on the standard `/v1/{logs,traces,metrics}` paths to the collector,
so an exporter needs only `https://<clickstack-host>` as its endpoint. Every
other port is bound to loopback, which is why the firewall opens only 80/443
and never 4317/4318. The first consumer is `../clickstack-vultr`.

## Two compute providers

The package supports two compute providers, selected by template directory —
`tools/infrastructure/vultr/` and `tools/infrastructure/digitalocean/` —
rather than by conditionals, so a build is the only thing that proves a
provider's tree renders at all. The registry is `compute-providers` in
`validate.clj` (mirrored in `validate.ts` and `validate.py`): provider name to
its required keys, its secret (`:vultr-api-key` or `:do-token`), and the
environment variable OpenTofu reads it from. The keys of that map are the
advertised providers; keys of the unselected provider are accepted and
ignored, never refused, so one `colors.yml` moves between providers by one
edit. `<provider>-name` is optional and resolves through `compute-name`,
profile by default (Compute Name Standard); the templates interpolate that one
value for the label, the firewall name and `params.name` and never branch.
`compute-key` is how the shared steps reach `<provider>-ssh-sources` and
`<provider>-http-sources`.

Every provider's compute stage outputs the same `params` —
`{provider, ip, user, sudoer, name, ssh_key_id (keygen only)}` — and
`provider` is the switch guard. Both providers share one state key, so a
changed `provider-compute` on a profile whose state already holds a machine
would plan a cross-provider replacement, and a delete would render and destroy
the *selected* provider's template against the wrong lifecycle. `start-step`
therefore reads the state once, up front, with backend credentials alone, and
a validator placed after `state-errors` and before the credential check
refuses a real create or delete whose recorded provider differs from the
selected one (`state holds a <recorded> machine; set provider-compute back to
<recorded> and delete first`). The order is deliberate: a mistaken provider
edit reports the actionable error, not a missing token for the provider that
was just selected. A recorded `params` without `provider` predates this
package recording one and is treated as Vultr, the only provider it ever
offered. An unreadable backend is not an empty state: on a real create it
counts as no state (a fresh clone has none), on a real delete `adopt-state`
fails closed rather than proceeding with nothing to address, and a real create
whose compute output carries no `ip` refuses to converge against the
documentation address (`resolved-compute`).

The provider firewall is the load-bearing network layer on both providers and
Ansible manages no host firewall for its ports. `state-errors` refuses an
empty `<provider>-ssh-sources` and any entry that is not a syntactically valid
IPv4 or IPv6 CIDR, before any provider call; an empty HTTP list means no public
HTTP. On DigitalOcean the droplet joins the region's default VPC, discovered
at plan time, and `digitalocean-vpc-uuid` and `digitalocean-vpc-cidr` are
refused. The DigitalOcean template emits its 80/443 rules through a `dynamic`
block because a DigitalOcean inbound rule with no source is an API error, not
a closed port.

## Why convergence creates the initial team

HyperDX configures the collector over OpAMP and pushes nothing until a team
exists, so before that the collector binds **no OTLP receivers at all** — 4317
and 4318 are unbound and every exporter gets a connection reset. That is not a
UI nicety to leave to a human; it is the difference between a deployment that
ingests and one that cannot, so `clickstack-setup` registers the first user
during convergence.

It also settles where the ingestion key comes from: it is the team's `apiKey`,
minted by the app and therefore unknowable in advance. The script reads it back
and rewrites `/etc/clickstack/ingestion.env`, recreating the app and collector
when it changes. Neither that key nor the generated admin password enters a
tracked or generated file.

## The SSH keypair

This package is born conforming to the workspace SSH Keypair Standard
(`../workspace/standards/ssh-keypair.md`). Read that document before touching
anything under `green/src/clj/io/github/getcolors/clickstack/ssh.clj` or its
red/blue counterparts.

The behaviour is ONCE's — `io.github.getcolors.once.ssh` — deliberately reused
rather than reimplemented, so one standard has one implementation. Absent
`<provider>-ssh-keys` for the selected provider in desired state means keygen
mode: the package generates `~/.ssh/<profile>`, declares the account key
resource (`vultr_ssh_key` or `digitalocean_ssh_key`) named after the profile
and references it by attribute, runs the provider REST preflight with that
provider's token before applying, and removes the local key last, only after
the compute destroy succeeded. Present `<provider>-ssh-keys` means opt-out:
the package touches no key material and renders the historical shape. Which
key that is comes from ONCE's `machine-key-keys` table, never a literal, which
is what lets the build placeholder land on the right key for either provider.

What this repository adds is the build placeholder. ONCE derives key paths from
`$HOME` and commits no rendered output; clickstack commits goldens, so `build`
and `--dry-run` render `/home/build-placeholder/.ssh/<profile>` instead. That
is why `ssh/rendered-only?` tests `:green/dry-run` as well as the event — a
dry-run that fell through to the real path would read `~/.ssh`, which the
standard forbids, and `bb test` covers exactly that.

`bb golden` renders four fixtures: one per advertised provider per keypair
mode, because the standard has two modes — keygen (`test/fixtures/colors.yml`,
`colors-digitalocean.yml`) and opt-out (`optout.yml`, `optout-digitalocean.yml`)
— and a change that only holds in one of them, or on one provider, is not
conforming. The two DigitalOcean fixtures also split the Compute Name
Standard: the keygen one carries no `digitalocean-name` and proves the profile
default, the opt-out one sets it to the profile. The Vultr fixtures keep
`vultr-name` equal to the profile so their goldens stayed byte-identical
through adoption, apart from the `provider` line in `params`.

## The `~/.ssh/config` block

This package is also the reference implementation of the workspace SSH Config
Standard (`../workspace/standards/ssh-config.md`), which is why the
`ansible-local` stage exists: one `blockinfile` task giving the operator
`ssh <profile>` instead of an address, a user and an identity file.

Two rules there are easy to undo by accident.

The play is **this package's own copy**, deliberately not shared with ONCE's,
which is the opposite choice from `ssh.clj` above. `ssh.clj` acts on
profile-named files only this deployment uses, so sharing it spreads fixes.
The local play writes into a file the operator shares with every host they
reach, so sharing it would let an unrelated upstream change rewrite that file
at pin-bump time.

Address, user, alias and `block_state` arrive as **Ansible extra-vars, never
through Selmer**. That is what keeps `build` byte-identical across workstations
and keeps addresses out of the goldens, and `scripts/golden.sh` fails if a
dotted quad ever appears under `clickstack-ansible-local`.

Create writes the block after compute and before convergence. Delete removes it
*before* the destroy, which is the reverse of the keypair: a block that
outlives its host is stale but harmless, while a key removed early locks you
out of a machine that still exists.

## Credentials generated on the server

Three values live only on the host, all mode 0600 and all created under
`creates:` so a re-converge never rotates them: the admin password in
`/etc/clickstack/admin.env`, the team ingestion key in
`/etc/clickstack/ingestion.env`, and `EXPRESS_SESSION_SECRET` in
`/etc/clickstack/session.env`.

The session secret matters more than it looks. HyperDX falls back to a constant
published in its own repository when the variable is unset, so without this the
deployment signs session cookies with a value anybody can read. It is generated
rather than supplied because nothing outside the host ever needs it, which
keeps it out of `.envrc.private`, `.colors/` and the goldens entirely.

## Commands

The three implementations live in the tri-colour layout, matching `netbird`:
canonical Clojure in `green/` (`green/bb.edn`, `green/deps.edn`, `green/src/`,
`green/tasks/`, tests under `green/test/clj`), TypeScript/Bun in `red/`, and
Python/uv in `blue/`. Green is canonical: a behavioural change lands in all
three colours in the same commit and passes `scripts/parity.sh`, which renders
all four fixtures through every colour and diffs the trees — and the colour
template trees (`red/resources`, blue's embedded `resources/`) — byte for byte.
The four fixtures and the goldens are shared across colours at the repository
root — `test/fixtures/` and `test/resources/golden/` — with
`green/test/fixtures` and `green/test/resources` symlinks pointing at them.
Each colour dir holds a launcher symlink to its skill payload (`green/green`,
`red/red`, `blue/blue`).

```sh
cd green && bb test
cd green && bb golden
cd green && bb golden:accept
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, four fixtures, byte for byte
./scripts/launcher.sh          # from the repository root
cd green && ./green build
cd green && ./green create --dry-run
cd green && ./green create     # requires explicit authorization
cd green && ./green delete     # guarded and destructive
```

Never read `.envrc.private`, edit `.colors/`, export `COLORS_PAR_PROFILE`, or
weaken `compute-prevent-destroy`. Build and dry-run are credential-free and
must not touch `~/.ssh`.

## Coupling

The package pins Green and ONCE in `green/deps.edn`, the Red SDK and
`package-once-red` in `red/package.json`, and the Blue SDK and
`package-once-blue` in `blue/pyproject.toml`. All three colours pin ONCE at the
**same rev** — ONCE's own parity is what guarantees its colours agree per
commit. ONCE supplies the backend provider registry, the registrable-domain
helper, and the whole SSH standard implementation — so the ONCE pin can never
go below `bc06f2f`, the commit that moved the machine keypair into the
operator's `~/.ssh`. Use `GREEN_LIB_ROOT`, `ONCE_LIB_ROOT`, and
`CLICKSTACK_LIB_ROOT` for working-tree development (`CLICKSTACK_LIB_ROOT`
names the repository root for every colour; red also accepts the `red/` dir
directly). Final launchers use a pushed SHA managed by `bb pin`, which stamps
all three payloads from their unpinned birth forms; deployment launchers are
copies, not symlinks.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
