import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import type { Opts } from "red/workflow";
import * as ssh from "../src/ssh.ts";
import * as sshConfig from "../src/ssh-config.ts";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import * as workflow from "../src/workflow.ts";

const fixtureFile = join(import.meta.dir, "../../test/fixtures/colors.yml");
const optoutFile = join(import.meta.dir, "../../test/fixtures/optout.yml");

function readFixture(path: string, overrides: Opts): Opts {
  const text = readFileSync(path, "utf8").replaceAll("WORKDIR", ".colors");
  return { ...(Bun.YAML.parse(text) as Opts), ...overrides };
}

const fixture = (overrides: Opts = {}) => readFixture(fixtureFile, overrides);
const optout = (overrides: Opts = {}) => readFixture(optoutFile, overrides);

// ~/.ssh redirection: ONCE's ssh module and this package's ssh-config both
// read $HOME at call time, exactly so tests can point them at a fresh
// temporary home.
let savedHome: string | undefined;
let home: string;
beforeEach(() => {
  savedHome = process.env.HOME;
  home = mkdtempSync(join(tmpdir(), "clickstack-red-test"));
  process.env.HOME = home;
});
afterEach(() => {
  process.env.HOME = savedHome;
  rmSync(home, { recursive: true, force: true });
});

function write(path: string, content: string) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

// --- desired state -----------------------------------------------------------

describe("validate", () => {
  test("both fixtures are valid", () => {
    expect(validate.stateErrors(fixture())).toEqual([]);
    expect(validate.stateErrors(optout())).toEqual([]);
  });

  test("the machine key is not required", () => {
    // The standard makes absence meaningful: requiring vultr-ssh-keys would
    // make every conforming keygen deployment invalid.
    expect(validate.stateErrors(fixture()).some((e) => e.includes("vultr-ssh-keys"))).toBe(false);
  });

  test("absent machine key selects keygen", () => {
    expect(validate.keygen(fixture())).toBe(true);
    expect(validate.keygen(optout())).toBe(false);
  });

  test("reports all errors at once", () => {
    const errors = validate.stateErrors(fixture({
      "clickstack-host": "bad",
      "clickstack-hyperdx-image": "floating",
      "clickstack-admin-email": "not-an-email",
      "provider-dns": "other", "provider-compute": "digitalocean",
      "vultr-os-id": "2284",
    }));
    expect(errors.length).toBeGreaterThanOrEqual(6);
    for (const part of ["host", "image", "admin-email", "provider-dns", "vultr", "os-id"]) {
      expect(errors.some((e) => e.includes(part))).toBe(true);
    }
  });

  test("accepts a digest pin", () => {
    expect(validate.stateErrors(
      fixture({ "clickstack-caddy-image": `caddy@sha256:${"a".repeat(64)}` }))).toEqual([]);
  });

  test("profile overlay is refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "other" }).length).toBe(1);
    expect(validate.envErrors({})).toEqual([]);
  });

  test("names all package secrets", () => {
    const errors = validate.secretErrors(fixture()).join("\n");
    for (const name of ["COLORS_PAR_VULTR_API_KEY", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
                        "COLORS_PAR_R2_ACCESS_KEY_ID", "COLORS_PAR_R2_SECRET_ACCESS_KEY"]) {
      expect(errors).toContain(name);
    }
    // The ingestion key is generated on the server, never supplied.
    expect(errors).not.toContain("INGESTION");
  });
});

// --- tools -------------------------------------------------------------------

describe("tools", () => {
  test("firewall sources parse and infrastructure data carries the ssh mode", () => {
    const data = tools.infrastructureData(fixture());
    expect(tools.cidrs(data, "vultr-http-sources")).toEqual(["0.0.0.0/0", "::/0"]);
    expect(data["ssh-keygen"]).toBe(true);
    expect(tools.infrastructureData(optout())["ssh-keygen"]).toBe(false);
  });

  test("cidrs accept overlay strings", () => {
    expect(tools.cidrs({ x: "10.0.0.0/8, 20.0.0.0/8" }, "x"))
      .toEqual(["10.0.0.0/8", "20.0.0.0/8"]);
  });

  test("dns zone is the registrable domain", () => {
    expect(tools.zone(fixture())).toBe("example.com");
  });

  test("dns record is the host, proxied", () => {
    const json = tools.dnsJson(fixture({ ip: "192.0.2.10" }));
    expect(json).toContain("clickstack.example.com");
    expect(json).toContain("192.0.2.10");
    expect(json).toContain('"proxied" : true');
  });

  test("the inventory keeps one target", () => {
    const inventory = tools.inventory(fixture({ ip: "192.0.2.10" }));
    expect(inventory).toContain("192.0.2.10");
    expect(inventory).toContain("clickstack-fixture");
  });

  test("the ansible stage renders the whole stack", () => {
    const targets = tools.ansibleSpecs(fixture()).map((s) => String(s.target));
    for (const file of ["ansible.cfg", "main.yml", "cleanup.yml", "compose.yml",
                        "Caddyfile", "setup.sh", "smoke.sh", "inventory.json"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
  });

  test("setup carries the admin email and no password", () => {
    // The team is created during convergence, so the login identity is
    // rendered; its password is generated on the server and must never reach a
    // rendered file.
    const spec = tools.ansibleSpecs(fixture())
      .find((s) => String(s.target).endsWith("main.yml"));
    const rendered = JSON.stringify(spec?.data ?? {});
    expect(rendered).toContain("admin@clickstack.example.com");
    expect(rendered).not.toContain("HYPERDX_ADMIN_PASSWORD=Cs-");
  });

  test("acceptance is skipped outside a real create", async () => {
    for (const event of ["build", "delete"]) {
      const result = await tools.acceptanceStep(fixture({ "red/event": event }));
      expect(result["red/exit"]).toBe(0);
    }
  });

  test("a wired OTLP endpoint tolerates a rejected payload", () => {
    // A live receiver that refuses an anonymous or malformed request is proof
    // the route exists; 404 and the 5xx family are not.
    expect(tools.endpointWired.has("401")).toBe(true);
    expect(tools.endpointWired.has("400")).toBe(true);
    expect(tools.endpointWired.has("404")).toBe(false);
    expect(tools.endpointWired.has("502")).toBe(false);
    expect(tools.endpointWired.has("000")).toBe(false);
  });

  test("tool dirs live under <workdir>/<profile>", () => {
    const opts = { workdir: "/work", profile: "clickstack-fixture" };
    expect(tools.toolDir(opts, tools.infrastructureTool))
      .toBe("/work/clickstack-fixture/clickstack-infrastructure");
    expect(tools.toolDir(opts, tools.ansibleLocalTool))
      .toBe("/work/clickstack-fixture/clickstack-ansible-local");
  });

  test("backend advice writes the conventional state address", () => {
    const work = mkdtempSync(join(tmpdir(), "clickstack-red-backend"));
    try {
      const opts = fixture({ workdir: work, "provider-backend": "r2" });
      workflow.backendAdvice(tools.dnsTool)(opts);
      const backend = JSON.parse(readFileSync(
        join(work, "clickstack-fixture", "clickstack-dns", "backend.tf.json"), "utf8"));
      const s3 = backend.terraform.backend.s3;
      expect(s3.bucket).toBe("tofu-state-319271fed8bc6d2d9059362be1165f37-eu");
      expect(s3.key).toBe("clickstack-fixture/clickstack-dns.tfstate");
      expect(s3.endpoints.s3).toBe("https://319271fed8bc6d2d9059362be1165f37.eu.r2.cloudflarestorage.com");
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });
});

// --- ssh keypair (SSH Keypair Standard) --------------------------------------

describe("ssh", () => {
  test("build renders a stable placeholder path", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "build" }));
    expect(String(opts["ssh-public-key-path"])).toStartWith(ssh.buildPlaceholderDir);
    expect(opts["vultr-ssh-keys"]).toBe(opts["ssh-public-key-path"]);
    expect(String(opts["ssh-private-key-path"])).not.toContain(home);
  });

  test("a dry-run renders the placeholder too", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "create", "red/dry-run": true }));
    expect(String(opts["ssh-public-key-path"])).toStartWith(ssh.buildPlaceholderDir);
  });

  test("real events render the real path", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "create" }));
    expect(opts["ssh-private-key-path"]).toBe(join(home, ".ssh", "clickstack-fixture"));
    expect(opts["ssh-public-key-path"]).toBe(join(home, ".ssh", "clickstack-fixture.pub"));
  });

  test("opt-out passes through untouched", () => {
    for (const event of ["build", "create", "delete"]) {
      const opts = ssh.withMachineKey(optout({ "red/event": event }));
      expect(opts["vultr-ssh-keys"]).toBe("00000000-0000-0000-0000-000000000000");
      expect(opts["ssh-public-key-path"]).toBeUndefined();
      expect(opts["ssh-keygen"]).toBeUndefined();
    }
  });

  test("first create generates the keypair", async () => {
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    const prv = join(home, ".ssh", "clickstack-fixture");
    const pub = `${prv}.pub`;
    expect(opts["red/err"]).toBeUndefined();
    expect(existsSync(prv)).toBe(true);
    expect(existsSync(pub)).toBe(true);
    // ed25519, no passphrase, profile-named comment
    expect(readFileSync(pub, "utf8")).toContain("ssh-ed25519");
    expect(readFileSync(pub, "utf8")).toContain("clickstack-fixture managed by Colors");
    // 600 on the private key, 700 on ~/.ssh
    expect(statSync(prv).mode & 0o777).toBe(0o600);
    expect(statSync(join(home, ".ssh")).mode & 0o777).toBe(0o700);
  });

  test("converge reuses an existing key", async () => {
    write(join(home, ".ssh", "clickstack-fixture"), "private");
    write(join(home, ".ssh", "clickstack-fixture.pub"), "ssh-ed25519 AAAA test");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }),
      async () => ({ ip: "192.0.2.10" }));
    expect(opts["red/err"]).toBeUndefined();
    expect(readFileSync(join(home, ".ssh", "clickstack-fixture"), "utf8")).toBe("private");
  });

  test("state without a key is an error", async () => {
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }),
      async () => ({ ip: "192.0.2.10" }));
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("does not hold the machine key");
    expect(String(opts["red/err"])).toContain("rebuild");
  });

  test("a key without state is never overwritten", async () => {
    const prv = join(home, ".ssh", "clickstack-fixture");
    write(prv, "irreplaceable");
    write(`${prv}.pub`, "ssh-ed25519 AAAA test");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("no compute state is readable");
    expect(String(opts["red/err"])).toContain("survives");
    expect(readFileSync(prv, "utf8")).toBe("irreplaceable");
  });

  test("half a keypair is an error", async () => {
    write(join(home, ".ssh", "clickstack-fixture"), "private");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("half a keypair");
  });

  test("opt-out generates nothing", async () => {
    const opts = await ssh.ensureKey(optout({ "red/event": "create" }), async () => undefined);
    expect(opts["red/err"]).toBeUndefined();
    expect(existsSync(join(home, ".ssh"))).toBe(false);
  });

  test("preflight passes when no account key matches, or when it is ours", async () => {
    const clean = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "1", name: "someone-else", public: "ssh-ed25519 BBBB" }]);
    expect(clean["red/err"]).toBeUndefined();
    const owned = await ssh.preflight(
      ssh.withMachineKey(fixture({ "red/event": "create",
        "once/ssh-state-params": { ssh_key_id: "abc" } })),
      async () => [{ id: "abc", name: "clickstack-fixture", public: "ssh-ed25519 AAAA" }]);
    expect(owned["red/err"]).toBeUndefined();
  });

  test("preflight refuses our leftover key", async () => {
    write(join(home, ".ssh", "clickstack-fixture.pub"), "ssh-ed25519 AAAA comment");
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "abc", name: "clickstack-fixture", public: "ssh-ed25519 AAAA" }]);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("previous delete");
    expect(String(opts["red/err"])).toContain("delete that key");
  });

  test("preflight refuses a foreign key and says do not delete it", async () => {
    write(join(home, ".ssh", "clickstack-fixture.pub"), "ssh-ed25519 OURS comment");
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "abc", name: "clickstack-fixture", public: "ssh-ed25519 THEIRS" }]);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("Do not delete it");
  });

  test("preflight failure is an error, not a skip", async () => {
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => { throw new Error("HTTP 500"); });
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("cannot list");
  });

  test("delete removes the keypair; ~/.ssh itself survives", () => {
    write(join(home, ".ssh", "clickstack-fixture"), "private");
    write(join(home, ".ssh", "clickstack-fixture.pub"), "public");
    ssh.cleanupStep(fixture({ "red/event": "delete", "ssh-keygen": true }));
    expect(existsSync(join(home, ".ssh", "clickstack-fixture"))).toBe(false);
    expect(existsSync(join(home, ".ssh", "clickstack-fixture.pub"))).toBe(false);
    expect(existsSync(join(home, ".ssh"))).toBe(true);
  });

  test("cleanup is inert on create and in opt-out mode", () => {
    write(join(home, ".ssh", "clickstack-fixture"), "private");
    ssh.cleanupStep(fixture({ "red/event": "create", "ssh-keygen": true }));
    expect(existsSync(join(home, ".ssh", "clickstack-fixture"))).toBe(true);
    ssh.cleanupStep(optout({ "red/event": "delete" }));
    expect(existsSync(join(home, ".ssh", "clickstack-fixture"))).toBe(true);
  });
});

// --- ~/.ssh/config (SSH Config Standard) -------------------------------------

describe("ssh-config", () => {
  test("the alias is the profile and the identity file keeps the tilde", () => {
    expect(sshConfig.hostAlias(fixture())).toBe("clickstack-fixture");
    expect(sshConfig.identityFile(fixture())).toBe("~/.ssh/clickstack-fixture");
    expect(sshConfig.identityFile(fixture())).not.toContain(home);
  });

  test("the marker is the alias alone", () => {
    expect(sshConfig.beginMarker("clickstack-vultr")).toBe("# BEGIN clickstack-vultr ANSIBLE MANAGED BLOCK");
    expect(sshConfig.endMarker("clickstack-vultr")).toBe("# END clickstack-vultr ANSIBLE MANAGED BLOCK");
  });

  test("a foreign stanza is found; our own block is not foreign", () => {
    expect(sshConfig.foreignStanzaLine(
      ["Host other", "    HostName 192.0.2.1", "", "Host clickstack-fixture"],
      "clickstack-fixture")).toBe(4);
    const alias = "clickstack-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, "    HostName 192.0.2.1",
       sshConfig.endMarker(alias)], alias)).toBeUndefined();
  });

  test("a stanza after our block is still foreign", () => {
    const alias = "clickstack-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, sshConfig.endMarker(alias),
       `Host ${alias}`], alias)).toBe(4);
  });

  test("a block under a retired marker is foreign", () => {
    const alias = "clickstack-vultr";
    expect(sshConfig.foreignStanzaLine(
      [`# BEGIN clickstack ${alias} ANSIBLE MANAGED BLOCK`, `Host ${alias}`,
       `# END clickstack ${alias} ANSIBLE MANAGED BLOCK`], alias)).toBe(2);
  });

  test("multi-pattern host lines count; unrelated files are left alone", () => {
    expect(sshConfig.foreignStanzaLine(["Host web clickstack-fixture db"], "clickstack-fixture")).toBe(1);
    expect(sshConfig.foreignStanzaLine(["Host build", "Host clickstack-other"], "clickstack-fixture"))
      .toBeUndefined();
  });

  test("an option above the first Host is refused; comments and Host openers are fine", () => {
    expect(sshConfig.leadingOptionLine(["ServerAliveInterval 60", "Host a"])).toBe(1);
    expect(sshConfig.leadingOptionLine(["# comment", "", "IdentitiesOnly yes", "Host a"])).toBe(3);
    expect(sshConfig.leadingOptionLine(["Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# lead comment", "", "Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["Match host b", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# nothing here", ""])).toBeUndefined();
  });

  test("preflight refuses rather than overwrites", () => {
    const refused = sshConfig.preflight(fixture(), {
      adoptError: () => "already declares `Host x`",
      placementError: () => undefined,
    });
    expect(refused["red/exit"]).toBe(1);
    expect(String(refused["red/err"])).toContain("already declares");
    const clean = sshConfig.preflight(fixture(), {
      adoptError: () => undefined,
      placementError: () => undefined,
    });
    expect(clean["red/exit"]).toBeUndefined();
  });

  test("adopt and placement errors read the real file and mention the recovery", () => {
    write(join(home, ".ssh", "config"), "ServerAliveInterval 60\nHost clickstack-fixture\n");
    expect(String(sshConfig.adoptError(fixture()))).toContain("Host clickstack-fixture");
    expect(String(sshConfig.placementError(fixture()))).toContain("Host *");
  });

  test("the local play renders no address and follows keygen mode", () => {
    const data = tools.ansibleLocalData(fixture({ ip: "203.0.113.7" }));
    expect(data["ssh-config-identity-file"]).toBe("~/.ssh/clickstack-fixture");
    expect(data["ssh-keygen"]).toBe(true);
    expect(tools.ansibleLocalData(optout())["ssh-keygen"]).toBe(false);
  });

  test("the local stage renders three files", () => {
    const targets = tools.ansibleLocalSpecs(fixture()).map((s) => String(s.target));
    for (const file of ["/ansible.cfg", "/inventory.ini", "/main.yml"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
    expect(targets.every((t) => t.includes("clickstack-ansible-local"))).toBe(true);
  });
});

// --- workflow ----------------------------------------------------------------

describe("workflow", () => {
  test("build and dry-run need no credentials and never touch ~/.ssh", async () => {
    // The standard forbids reading, creating, or requiring anything under
    // ~/.ssh on a build or dry-run: they render from desired state alone.
    // A poisoned config proves nothing in the build path reads it.
    write(join(home, ".ssh", "config"), "ServerAliveInterval 60\nHost clickstack-fixture\n");
    for (const overrides of [{ "red/event": "build" },
                             { "red/event": "create", "red/dry-run": true }]) {
      const result = await workflow.startStep(fixture(overrides), {});
      expect(result["red/exit"]).toBe(0);
      expect(String(result["ssh-public-key-path"])).toStartWith("/home/build-placeholder");
    }
  });

  test("a real create requires credentials", async () => {
    const result = await workflow.startStep(fixture({ "red/event": "create" }), {});
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COLORS_PAR_VULTR_API_KEY");
    expect(String(result["red/err"])).toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
  });

  test("delete is protected", async () => {
    const result = await workflow.startStep(fixture({ "red/event": "delete" }), {});
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COMPUTE_PREVENT_DESTROY");
  });

  test("the create graph orders the stack", () => {
    const next = (step: string) =>
      (workflow.wireFn(step, { "red/event": "create" }) ?? []).slice(1);
    expect(next("clickstack/start")).toEqual(["clickstack/infrastructure"]);
    expect(next("clickstack/infrastructure")).toEqual(["clickstack/ssh-config"]);
    expect(next("clickstack/ssh-config")).toEqual(["clickstack/dns"]);
    expect(next("clickstack/dns")).toEqual(["clickstack/ansible"]);
    expect(next("clickstack/ansible")).toEqual(["clickstack/acceptance"]);
  });

  test("delete removes the config block before the destroy and the key after it", () => {
    const next = (step: string) =>
      (workflow.wireFn(step, { "red/event": "delete" }) ?? []).slice(1);
    expect(next("clickstack/start")).toEqual(["clickstack/ansible"]);
    expect(next("clickstack/ansible")).toEqual(["clickstack/dns"]);
    expect(next("clickstack/dns")).toEqual(["clickstack/ssh-config"]);
    expect(next("clickstack/ssh-config")).toEqual(["clickstack/infrastructure"]);
    expect(next("clickstack/infrastructure")).toEqual(["clickstack/ssh-cleanup"]);
    expect(next("clickstack/ssh-cleanup")).toEqual([]);
  });
});
