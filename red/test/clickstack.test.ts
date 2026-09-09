import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { StepError, type Opts } from "red/workflow";
import * as ssh from "../src/ssh.ts";
import * as sshConfig from "../src/ssh-config.ts";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import * as compute from "../src/compute.ts";
import * as workflow from "../src/workflow.ts";

const fixtureFile = join(import.meta.dir, "../../test/fixtures/colors.yml");
const optoutFile = join(import.meta.dir, "../../test/fixtures/optout.yml");
const doFixtureFile = join(import.meta.dir, "../../test/fixtures/colors-digitalocean.yml");
const doOptoutFile = join(import.meta.dir, "../../test/fixtures/optout-digitalocean.yml");

function readFixture(path: string, overrides: Opts): Opts {
  const text = readFileSync(path, "utf8").replaceAll("WORKDIR", ".colors");
  return { ...(Bun.YAML.parse(text) as Opts), ...overrides };
}

const fixture = (overrides: Opts = {}) => readFixture(fixtureFile, overrides);
const optout = (overrides: Opts = {}) => readFixture(optoutFile, overrides);
const doFixture = (overrides: Opts = {}) => readFixture(doFixtureFile, overrides);
const doOptout = (overrides: Opts = {}) => readFixture(doOptoutFile, overrides);

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

describe("library compute", () => {
  test("all fixtures validate and use one library node", () => {
    for(const f of [fixture,optout,doFixture,doOptout]) expect(validate.stateErrors(f())).toEqual([]);
    expect(compute.topology).toEqual([{role:null,count:1}]);
    expect(compute.requirements(fixture()).legacy_state_keys).toEqual(['clickstack-fixture/clickstack-infrastructure.tfstate']);
  });
  test("invalid compute inputs fail before execution", () => {
    for(const update of [{'provider-compute':'unsupported'},{'vultr-plan':null},{'vultr-ssh-sources':[]},{'vultr-http-sources':['bad']}]) expect(validate.stateErrors(fixture(update)).length).toBeGreaterThan(0);
  });
  test("compute credentials are deferred to library state inspection", () => {
    const errors=validate.secretErrors(fixture()).join('\n');
    expect(errors).toContain('COLORS_PAR_CLOUDFLARE_API_TOKEN');
    expect(errors).not.toContain('COLORS_PAR_VULTR_API_KEY');
    expect(validate.tofuEnv(fixture(),'provider-compute')).toEqual({});
  });
  test("failed lifecycle diagnostics and observed node identity survive", () => {
    expect(compute.attach(fixture(),{status:'error',errors:['legacy compute state requires migration']})['red/err']).toBe('legacy compute state requires migration');
    const result=compute.attach(fixture(),{status:'present',cluster:{nodes:[{ip:'203.0.113.7',user:'ubuntu'}]},key:{private_key_path:'/tmp/explicit'}});
    expect(result.user).toBe('ubuntu');expect(result['ssh-private-key-path']).toBe('/tmp/explicit');
    expect(compute.attach(fixture(),{status:'destroyed'})['clickstack/already-destroyed']).toBe(true);
    expect(()=>compute.node({cluster:{nodes:[]}})).toThrow();
  });
  test("offline start needs no credentials", async()=> {
    for(const f of [fixture,optout,doFixture,doOptout]) expect((await workflow.startStep(f({'red/event':'build'}),{}))['red/exit']).toBe(0);
  });
  test("managed build and external SSH identities are deterministic",()=> {
    expect(ssh.withMachineKey(fixture({'red/event':'build'}))['ssh-private-key-path']).toBe('/home/build-placeholder/.ssh/clickstack-fixture');
    expect(ssh.withMachineKey(optout({'red/event':'build'}))).toEqual(optout({'red/event':'build'}));
    expect(ssh.identityArgs(optout())[1]).toBe('/home/build-placeholder/.ssh/operator-key');
  });
});
describe("tools", () => {
  test("a delete without compute skips the remote cleanup", async () => {
    // No address in state means no host to clean up, and the inventory would
    // otherwise fall back to 192.0.2.10.
    const work = mkdtempSync(join(tmpdir(), "clickstack-red-tools"));
    try {
      const result = await tools.ansibleStep(fixture({ workdir: work, "red/event": "delete" }));
      expect(result["red/exit"]).toBe(1);
      expect(result["red/err"]).toBe("compute node unavailable");
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
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
