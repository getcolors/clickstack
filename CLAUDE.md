# CLAUDE.md

## Repository

`clickstack` is a Green-only Package Skill for a ClickStack observability
server on one Vultr instance. OpenTofu manages the instance, a firewall
(22/80/443), and a proxied Cloudflare A record; Ansible converges a Docker
Compose stack of ClickHouse, MongoDB, the HyperDX OpenTelemetry collector, the
HyperDX app, and Caddy.

One public host carries both halves: Caddy serves the HyperDX UI and proxies
OTLP/HTTP on the standard `/v1/{logs,traces,metrics}` paths to the collector,
so an exporter needs only `https://<clickstack-host>` as its endpoint. Every
other port is bound to loopback, which is why the firewall opens only 80/443
and never 4317/4318. The first consumer is `../clickstack-vultr`.

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
anything under `src/clj/io/github/getcolors/clickstack/ssh.clj`.

The behaviour is ONCE's — `io.github.getcolors.once.ssh` — deliberately reused
rather than reimplemented, so one standard has one implementation. Absent
`vultr-ssh-keys` in desired state means keygen mode: the package generates
`~/.ssh/<profile>`, declares the `vultr_ssh_key` resource named after the
profile and references it by attribute, runs the Vultr REST preflight before
applying, and removes the local key last, only after the compute destroy
succeeded. Present `vultr-ssh-keys` means opt-out: the package touches no key
material and renders the historical shape.

What this repository adds is the build placeholder. ONCE derives key paths from
`$HOME` and commits no rendered output; clickstack commits goldens, so `build`
and `--dry-run` render `/home/build-placeholder/.ssh/<profile>` instead. That
is why `ssh/rendered-only?` tests `:green/dry-run` as well as the event — a
dry-run that fell through to the real path would read `~/.ssh`, which the
standard forbids, and `bb test` covers exactly that.

`bb golden` renders two fixtures because the standard has two modes: keygen
(`test/fixtures/colors.yml`) and opt-out (`test/fixtures/optout.yml`). A change
that only holds in one of them is not conforming.

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

```sh
bb test
bb golden
bb golden:accept
./scripts/launcher.sh
./green build
./green create --dry-run
./green create                 # requires explicit authorization
./green delete                 # guarded and destructive
```

Never read `.envrc.private`, edit `.colors/`, export `COLORS_PAR_PROFILE`, or
weaken `compute-prevent-destroy`. Build and dry-run are credential-free and
must not touch `~/.ssh`.

## Coupling

The package pins Green and ONCE in `deps.edn`. ONCE supplies the backend
provider registry, the registrable-domain helper, and the whole SSH standard
implementation — so the ONCE pin can never go below `bc06f2f`, the commit that
moved the machine keypair into the operator's `~/.ssh`. Use `GREEN_LIB_ROOT`,
`ONCE_LIB_ROOT`, and `CLICKSTACK_LIB_ROOT` for working-tree development. Final
launchers use a pushed SHA managed by `bb pin`; deployment launchers are
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
