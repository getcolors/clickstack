import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";
import {keyMode} from "colors-compute-red";
import * as compute from "./compute.ts";

export const profilePar = parName("profile");

export const defaultComputeProvider="vultr";

export const required = [
  "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
  "compute-prevent-destroy", "clickstack-host", "clickstack-admin-email",
  "clickstack-hyperdx-image", "clickstack-otel-collector-image",
  "clickstack-clickhouse-image", "clickstack-mongo-image", "clickstack-caddy-image",

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

export function keygen(opts:Opts):boolean {try{return keyMode(opts).mode==='managed';}catch{return true;}}

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

// Every problem with desired state at once: the missing keys (this package's
// and the selected provider's), the package's own checks, then the Compute
// Provider Standard's — selection, the network contract and the provider
// rules — which are ONCE's over `spec`.
export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of required) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (!["s3", "r2"].includes(String(opts["provider-backend"]))) {
    errors.push(":provider-backend must be s3 or r2");
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
  errors.push(...compute.errors(opts));
  return errors;
}

export function backendSecrets(opts: Opts): string[] {
  return providers["provider-backend"]?.[String(opts["provider-backend"])]?.secrets ?? [];
}

// Credentials a real create or delete needs: the selected compute provider's,
// Cloudflare's, and the backend's. The HyperDX ingestion key is not here: it
// is generated on the server and never supplied by the operator.
export function secretErrors(opts: Opts): string[] {
  const keys = [...new Set([
    "cloudflare-api-token",
    ...backendSecrets(opts),
  ])];
  return keys.filter((key) => missing(opts[key]))
    .map((key) => `required credential is not set: ${parName(key)}`);
}

export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  switch (slot) {
    case "provider-dns":
      return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend":
      return providers["provider-backend"]?.[String(opts["provider-backend"])]?.tofuEnv ?? {};
    default:
      return {};
  }
}
