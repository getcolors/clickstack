import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime } from "red/runtime";
import type { Opts } from "red/workflow";
import { failed } from "red/workflow";
import { registrableDomain } from "package-once-red";
import * as sshConfig from "./ssh-config.ts";
import * as validate from "./validate.ts";

import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import ansibleCleanup from "../resources/tools/ansible/cleanup.yml" with { type: "text" };
import ansibleCompose from "../resources/tools/ansible/compose.yml" with { type: "text" };
import ansibleCaddyfile from "../resources/tools/ansible/Caddyfile" with { type: "text" };
import ansibleSetup from "../resources/tools/ansible/setup.sh" with { type: "text" };
import ansibleSmoke from "../resources/tools/ansible/smoke.sh" with { type: "text" };
import dnsMainTf from "../resources/tools/dns/main.tf" with { type: "text" };
import infrastructureMainTf from "../resources/tools/infrastructure/main.tf" with { type: "text" };

export const infrastructureTool = "clickstack-infrastructure";
export const dnsTool = "clickstack-dns";
export const ansibleTool = "clickstack-ansible";
export const ansibleLocalTool = "clickstack-ansible-local";
export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "clickstack" });
}

const template = (name: string, content: string): Template => ({ name, content });

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

export function cidrs(opts: Opts, key: string): string[] {
  const value = opts[key];
  const parts = Array.isArray(value) ? value : String(value ?? "").split(/[,\s]+/);
  return parts.map((part) => String(part).trim()).filter((part) => part.length > 0);
}

export function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  const mapping: Record<string, string> = Object.assign(
    {},
    ...[...slots, "provider-backend"].map((slot) => validate.tofuEnv(opts, slot)),
  );
  const env: Record<string, string> = {};
  for (const [key, envVar] of Object.entries(mapping)) {
    const value = String(opts[key] ?? "");
    if (value.length > 0) env[envVar] = value;
  }
  return Object.keys(env).length > 0 ? env : undefined;
}

export const backendCredentialEnv = (opts: Opts) => credentialEnv(opts);

export function fallbackParams(opts: Opts): Record<string, unknown> {
  return { ip: "192.0.2.10", user: "root", sudoer: "root", name: opts.profile };
}

export function outputParams(result: Opts): Record<string, unknown> | undefined {
  const params = (result["tofu/outputs"] as Record<string, unknown> | undefined)?.params;
  return params && typeof params === "object" ? params as Record<string, unknown> : undefined;
}

// ---------------------------------------------------------------- compute

export function infrastructureData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "ssh-sources-hcl": tofu.hclList(cidrs(opts, "vultr-ssh-sources")),
    "http-sources-hcl": tofu.hclList(cidrs(opts, "vultr-http-sources")),
  };
}

export async function infrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const specs = [spec(template("infrastructure/main.tf", infrastructureMainTf),
                      `${dir}/main.tf`, infrastructureData(opts))];
  const result = await tofu.tofuWithSpec(opts, specs,
    { dir, env: credentialEnv(opts, "provider-compute") });
  if (failed(result)) return result;
  if (opts["red/event"] === "build") return { ...result, ...fallbackParams(opts) };
  if (opts["red/event"] === "delete") return result;
  return { ...result, ...fallbackParams(opts), ...outputParams(result) };
}

// -------------------------------------------------------------------- dns

// The Cloudflare zone the UI host belongs to (its registrable domain).
export function zone(opts: Opts): string | undefined {
  return registrableDomain(opts["clickstack-host"]);
}

export function dnsJson(opts: Opts): string {
  return tofu.constructsJson([
    tofu.construct("resource", "cloudflare_dns_record", "clickstack", {
      zone_id: "${data.cloudflare_zone.zone.id}",
      name: opts["clickstack-host"], content: opts.ip, type: "A",
      proxied: true, ttl: 1,
    }),
  ]);
}

export async function dnsStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, dnsTool);
  const data: Opts = {
    ...opts,
    ip: opts.ip ?? fallbackParams(opts).ip,
    "clickstack-zone": zone(opts),
  };
  const specs = [
    spec(template("dns/main.tf", dnsMainTf), `${dir}/main.tf`, data),
    rawSpec(`${dir}/record.tf.json`, dnsJson(data)),
  ];
  return tofu.tofuWithSpec(opts, specs, { dir, env: credentialEnv(opts, "provider-dns") });
}

// ---------------------------------------------------------- ansible (local)

// Only what a `build` genuinely knows. The address, the user and the alias are
// run-time facts and reach the play as extra-vars instead, so the rendered
// playbook carries no IP and is identical on every workstation (SSH Config
// Standard §6).
export function ansibleLocalData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "ssh-config-identity-file": sshConfig.identityFile(opts),
  };
}

export function ansibleLocalSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = ansibleLocalData(opts);
  return [
    spec(template("ansible-local/ansible.cfg", ansibleLocalCfg), `${dir}/ansible.cfg`, data),
    spec(template("ansible-local/inventory.ini", ansibleLocalInventory), `${dir}/inventory.ini`, data),
    spec(template("ansible-local/main.yml", ansibleLocalMain), `${dir}/main.yml`, data),
  ];
}

// Write or remove the `~/.ssh/config` block. The same playbook serves both
// events; `block_state` is what distinguishes them.
export async function ansibleLocalStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleLocalTool);
  const isDelete = opts["red/event"] === "delete";
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.ini",
    playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: sshConfig.hostAlias(opts),
      ip: opts.ip ?? fallbackParams(opts).ip,
      user: opts.user ?? "root",
      block_state: isDelete ? "absent" : "present",
    },
  }, ansibleLocalSpecs(opts));
}

// ---------------------------------------------------------------- ansible

function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  return JSON.stringify(value ?? null);
}

export function inventory(opts: Opts): string {
  return pretty({
    all: {
      children: {
        clickstack: {
          hosts: {
            [String(opts.profile)]: {
              ansible_host: opts.ip ?? "192.0.2.10",
              ansible_user: "root",
            },
          },
        },
      },
    },
  });
}

// Template values for the Ansible stage. `ssh-private-key-path` reaches
// ansible.cfg so convergence uses the deployment's own key in keygen mode,
// where nothing guarantees an agent holds it.
export function ansibleData(opts: Opts): Opts {
  return {
    ...opts,
    ip: opts.ip ?? "192.0.2.10",
    "ssh-keygen": validate.keygen(opts),
  };
}

export function ansibleSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleTool);
  const data = ansibleData(opts);
  const files: Array<[string, string]> = [
    ["ansible.cfg", ansibleCfg],
    ["main.yml", ansibleMain],
    ["cleanup.yml", ansibleCleanup],
    ["compose.yml", ansibleCompose],
    ["Caddyfile", ansibleCaddyfile],
    ["setup.sh", ansibleSetup],
    ["smoke.sh", ansibleSmoke],
  ];
  return [
    ...files.map(([name, content]) =>
      spec(template(`ansible/${name}`, content), `${dir}/${name}`, data)),
    rawSpec(`${dir}/inventory.json`, inventory(data)),
  ];
}

export async function ansibleStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleTool);
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.json",
    playbooks: { create: "main.yml", delete: "cleanup.yml" },
    hostKeyChecking: false,
  }, ansibleSpecs(opts));
}

// ------------------------------------------------------------- acceptance

async function run(args: string[]) {
  return runtime.exec(args, { timeoutMs: 20000 });
}

// True once `args` exits zero, retrying every five seconds.
export async function waitFor(args: string[], attempts: number): Promise<boolean> {
  for (let remaining = attempts; ; remaining -= 1) {
    const result = await run(args);
    if (result.exit === 0) return true;
    if (remaining <= 0) return false;
    await Bun.sleep(5000);
  }
}

// The status code a request returns, as a string, or "000" when the request
// never completed.
export async function httpStatus(args: string[]): Promise<string> {
  return String((await run(args)).out ?? "").trim();
}

// Statuses that prove Caddy routed to the collector rather than swallowing the
// request. A rejected or malformed payload is still proof of a live receiver;
// 404 and the 5xx family are not.
export const endpointWired = new Set(["200", "400", "401", "403", "415", "422"]);

// Public health checks after a real create. The end-to-end ingest proof runs
// on the server, inside the playbook, where the generated ingestion key lives;
// what is checked from here is what a user can actually reach: the UI over
// HTTPS and the OTLP receiver behind it.
export async function acceptanceStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] !== "create") return { ...opts, "red/exit": 0 };
  const base = `https://${opts["clickstack-host"]}`;
  if (!(await waitFor(["curl", "-fsS", "-o", "/dev/null", `${base}/`], 60))) {
    return { ...opts, "red/exit": 1,
      "red/err": "HyperDX UI did not become reachable over HTTPS" };
  }
  const page = await run(["curl", "-fsS", `${base}/`]);
  const otlp = await httpStatus([
    "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
    "-X", "POST", "-H", "content-type: application/json",
    "--data", '{"resourceLogs":[]}', `${base}/v1/logs`,
  ]);
  if (!/hyperdx/i.test(String(page.out ?? ""))) {
    return { ...opts, "red/exit": 1, "red/err": "the HyperDX UI did not render" };
  }
  if (!endpointWired.has(otlp)) {
    return { ...opts, "red/exit": 1,
      "red/err": `the public OTLP endpoint is not wired: /v1/logs returned ${otlp}` };
  }
  return { ...opts, "red/exit": 0,
    "clickstack/acceptance": { ui: "ok", "otlp-status": otlp } };
}
