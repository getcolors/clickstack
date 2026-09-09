# CLAUDE.md

## Repository

`clickstack` is a tri-colour Package Skill (green, red, blue) for a ClickStack
observability server on one VM through the shared colors-compute library.
OpenTofu manages the machine, a provider firewall (22/80/443), and a proxied
Cloudflare A record; Ansible converges a Docker Compose stack of ClickHouse,
MongoDB, the HyperDX OpenTelemetry collector, the HyperDX app, and Caddy.

One public host carries both halves: Caddy serves the HyperDX UI and proxies
OTLP/HTTP on the standard `/v1/{logs,traces,metrics}` paths to the collector,
so an exporter needs only `https://<clickstack-host>` as its endpoint. Every
other port is bound to loopback, which is why the firewall opens only 80/443
and never 4317/4318. The first consumer is `../clickstack-vultr`.

## Shared compute ownership

All three colors depend on `colors-compute`, currently pinned to `3451a05e719b0ad6809f3c88b241a8c010b8f58b`.
Read `../workspace/standards/compute-provider.md`, `compute-name.md` and
`compute-cluster.md` before changing this boundary. This package owns only
application requirements and singleton topology: role null, count 1. Its
`compute` module delegates to library `plan_deployment`, `orchestrate` and
`read_deployment`; do not add a provider registry, provider dispatch, compute
OpenTofu templates, backend implementation, state writer or key lifecycle here.
A newly supported provider requires only a library dependency update in consumers.
The default remains `vultr`; provider capabilities and option validation
are defined by the library. Neutral `clickstack-ssh-sources` and
`clickstack-http-sources` are accepted alongside the selected adapter's legacy keys.

Build writes library documents under `compute/shared` and `compute/nodes/0`.
Each stage receives the library `backend_plan` configuration. Remote state keys
are `<profile>/compute/shared.tfstate` and `<profile>/compute/nodes/0.tfstate`;
S3 uses ambient AWS credentials, R2 binds its explicit backend credentials in
private configuration. The deployment journal serializes mutations. Compute
credential checks occur inside the library after ownership/state inspection.
DNS remains an application stage with its separate `<profile>/clickstack-dns.tfstate`.

The library refuses existing `<profile>/clickstack-infrastructure.tfstate` before
mutation. That old monolithic state needs explicit ownership migration or
teardown using the original package version. Never delete a state object to
bypass this refusal. Unreadable state, identity mismatches, ambiguous resource
ownership and live results without an address fail closed. Build-only planned
addresses must never become fallback targets for create/delete.

The joined node supplies the address, login user, provider identity and SSH
identity for downstream application steps. Do not assume the user is root.
No private network is requested by default. Explicit network references and
adapter capabilities are library concerns. The ingress policy is TCP22/80/443;
empty HTTP sources close HTTP ingress.

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

## SSH lifecycle and local configuration

Read `../workspace/standards/ssh-keypair.md` and `ssh-config.md` before edits.
The library owns key mode, registration preflight, journaled generation,
fingerprint checks and cleanup. Managed keys live at `~/.ssh/<profile>` and
are removed only after owned compute resources are destroyed. External provider
key references may use `ssh-private-key-path` or operator/agent SSH configuration; external key material is never
generated, rotated or deleted. There is no package `ssh-cleanup` step.

The package SSH helper only formats identities and deterministic build paths.
Build/dry-run use `/home/build-placeholder/.ssh/<profile>` and never inspect
operator key files or `~/.ssh/config`. Application Ansible uses the returned
login and explicit identity for both managed and external keys.

The package-owned `ansible-local/main.yml` contains the workspace locked,
atomic SSH-config updater. Keep its Python implementation identical across
colors. Runtime alias, address, user and removal mode arrive as Ansible
extra-vars, never rendered machine addresses. The managed block uses the profile
alias and includes `IdentityFile`/`IdentitiesOnly` only in managed mode. The
updater refuses conflicting unmanaged stanzas and leading global options.
Create updates the block after compute and before DNS/convergence; delete
removes it before compute destruction. Never replace this with `blockinfile`
or move key cleanup ahead of resource destruction.

## Build and migration checks

The four shared fixtures exercise managed/external keys on two adapters;
they are regression examples, not a package provider allowlist. Run native
Blue/Red/Green tests, Red typecheck, `scripts/parity.sh`, `scripts/golden.sh`
and `scripts/launcher.sh`. Golden acceptance requires reviewing the generated
application changes first. `scripts/check-compute-plan.py` checks singleton
stages, exact backend keys, absence of inline backend secrets and absence of
the old compute stage. Run the root example build with its workdir directed
to a temporary directory; it is separate from fixture coverage.

After dependency changes, build actual copied standalone payloads with no
`*_LIB_ROOT` overrides. Local tests alone do not prove their dependency pins.
Keep unrelated untracked compute-matrix artifacts out of migration commits.
Do not claim live deployment verification from an offline build.

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

## Dependency pins and launchers

Keep colors-compute's revision aligned in all three manifests/locks, the root
Red manifest, Blue PEP723 payload metadata and `green/tasks/pin.clj`. ONCE is
still pinned at `38e3cd66674a32fb96605e1b17ae6791086ad5c1` for application DNS
backend credential mapping and utility helpers; it no longer owns compute or
machine keys for this package. S3 credentials stay ambient. Preserve the DNS
R2 credential mapping when changing ONCE helpers.

Use `CLICKSTACK_LIB_ROOT` for repository development. Canonical `bb pin` in
`green/` stamps the three launchers only after the source commit is pushed.
Use a clean temporary worktree if unrelated untracked files prevent pinning;
never fabricate a SHA or include those files merely to satisfy the guard.
Then test the copied payloads, commit and push the stamps. Deployment
launchers are copies, not symlinks. Avoid duplicate transitive Git package
entries in Red's standalone PINS: Bun can fail before package loading.

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
