import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";
import { onceSsh } from "./once.ts";

export const profilePar = parName("profile");

// Every key desired state must carry. `vultr-ssh-keys` is deliberately absent:
// per the SSH Keypair Standard its *absence* selects keygen mode, where the
// package owns the keypair, and requiring it would make conforming deployments
// invalid.
export const required = [
  "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
  "compute-prevent-destroy", "clickstack-host", "clickstack-admin-email",
  "clickstack-hyperdx-image", "clickstack-otel-collector-image",
  "clickstack-clickhouse-image", "clickstack-mongo-image", "clickstack-caddy-image",
  "vultr-name", "vultr-region", "vultr-plan", "vultr-os-id",
  "vultr-ssh-sources", "vultr-http-sources",
  "r2-bucket", "r2-endpoint",
];

export const imageKeys = [
  "clickstack-hyperdx-image", "clickstack-otel-collector-image",
  "clickstack-clickhouse-image", "clickstack-mongo-image", "clickstack-caddy-image",
];

const hostRe = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
const emailRe = /^[^@\s]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
const imageRe = /^[^\s:@]+(?:\/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64})$/;

export function missing(value: unknown): boolean {
  return value === null || value === undefined ||
    (typeof value === "string" && value.trim() === "");
}

// Whether this deployment owns its machine keypair. Delegates to ONCE, the
// standard's reference implementation, so one rule decides it everywhere.
export function keygen(opts: Opts): boolean {
  return onceSsh.keygen(opts);
}

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of required) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (opts["provider-compute"] !== "vultr") {
    errors.push(":provider-compute must be vultr");
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (!["local", "s3", "r2"].includes(String(opts["provider-backend"]))) {
    errors.push(":provider-backend must be local, s3, or r2");
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") {
    errors.push(":compute-prevent-destroy must be true or false");
  }
  if (!missing(opts["clickstack-host"]) && !hostRe.test(String(opts["clickstack-host"]))) {
    errors.push(":clickstack-host must be a fully qualified hostname");
  }
  if (!missing(opts["clickstack-admin-email"]) &&
      !emailRe.test(String(opts["clickstack-admin-email"]))) {
    errors.push(":clickstack-admin-email must be an email address");
  }
  for (const key of imageKeys) {
    const value = opts[key];
    if (!missing(value) && !imageRe.test(String(value))) {
      errors.push(`:${key} must carry an explicit image tag or digest`);
    }
  }
  const osId = opts["vultr-os-id"];
  if (!(missing(osId) || (typeof osId === "number" && Number.isInteger(osId)))) {
    errors.push(":vultr-os-id must be Vultr's numeric operating-system id");
  }
  return errors;
}

export function backendSecrets(opts: Opts): string[] {
  return providers["provider-backend"]?.[String(opts["provider-backend"])]?.secrets ?? [];
}

// Credentials a real create or delete needs. The HyperDX ingestion key is not
// here: it is generated on the server and never supplied by the operator.
export function secretErrors(opts: Opts): string[] {
  const keys = [...new Set(["vultr-api-key", "cloudflare-api-token", ...backendSecrets(opts)])];
  return keys.filter((key) => missing(opts[key]))
    .map((key) => `required credential is not set: ${parName(key)}`);
}

export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  switch (slot) {
    case "provider-compute":
      return { "vultr-api-key": "VULTR_API_KEY" };
    case "provider-dns":
      return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend":
      return providers["provider-backend"]?.[String(opts["provider-backend"])]?.tofuEnv ?? {};
    default:
      return {};
  }
}
