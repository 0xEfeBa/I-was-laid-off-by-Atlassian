import { expect, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { bearer } from "./users";

export const BROKER_HEADERS = { "x-broker-api-version": "2.17" };

export function tenantName(): string {
  return `e2e-${randomUUID().slice(0, 8)}`;
}

export function spec(name: string, hosts: string[], backend = "echo:8180"): Record<string, unknown> {
  return {
    apiVersion: "tri-edge/v1",
    name,
    hosts,
    backends: [{ name: "main", address: backend }],
    routes: [
      { path: "/private", backend: "main", auth: "any" },
      { path: "/", backend: "main" },
    ],
  };
}

export async function createToken(request: APIRequestContext, accessToken: string, scopes: string[]): Promise<string> {
  const response = await request.post("/v1/tokens", {
    headers: bearer(accessToken),
    data: { name: `e2e-${scopes.join("-")}`, scopes, expiresInDays: 1 },
  });
  expect(response.status()).toBe(201);
  const token = ((await response.json()) as { value: string }).value;
  await expect
    .poll(async () => (await request.get("/api/tenants", { headers: bearer(token) })).status(), { timeout: 10_000 })
    .toBe(200);
  return token;
}

export async function lastOperation(request: APIRequestContext, token: string, name: string) {
  return request.get(`/v2/service_instances/${name}/last_operation`, {
    headers: { ...bearer(token), ...BROKER_HEADERS },
  });
}

export async function waitForOperation(request: APIRequestContext, token: string, name: string): Promise<string> {
  let description = "";
  await expect
    .poll(
      async () => {
        const body = (await (await lastOperation(request, token, name)).json()) as { state: string; description?: string };
        description = body.description ?? "";
        return body.state;
      },
      { timeout: 30_000, intervals: [250] },
    )
    .not.toBe("in progress");
  return description;
}

export async function waitUntilLive(request: APIRequestContext, token: string, name: string, version: number) {
  await expect
    .poll(
      async () => {
        const response = await request.get(`/api/tenants/${name}`, { headers: bearer(token) });
        const body = (await response.json()) as { applied: Record<string, number> };
        return body.applied.eu;
      },
      { timeout: 30_000, intervals: [250] },
    )
    .toBe(version);
}

export async function viaHost(request: APIRequestContext, host: string, path = "/", headers: Record<string, string> = {}) {
  return request.get(path, { headers: { host, ...headers } });
}
