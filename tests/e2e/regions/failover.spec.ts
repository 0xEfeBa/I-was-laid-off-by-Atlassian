import { execSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { request as http, type APIRequestContext } from "@playwright/test";
import { PASSWORD, bearer, expect, test } from "../fixtures/users";
import { BROKER_HEADERS, createToken, spec, viaHost, waitForOperation } from "../fixtures/tenants";

const REGIONS = ["eu", "us", "ap"] as const;
type Region = (typeof REGIONS)[number];

interface Resident {
  region: Region;
  username: string;
  token: string;
  tenant: string;
}

function gateway(region: Region): string {
  const url = process.env[`${region.toUpperCase()}_URL`];
  if (!url) {
    throw new Error(`Set ${region.toUpperCase()}_URL to the gateway of ${region}`);
  }
  return url;
}

async function signUpAndLogIn(api: APIRequestContext, region: Region): Promise<{ username: string; session: string }> {
  const username = `geo-${region}-${randomUUID().slice(0, 8)}`;
  const created = await api.post("/v1/signup", {
    data: { username, email: `${username}@example.com`, password: PASSWORD },
  });
  expect(created.status()).toBe(201);
  return { username, session: await logIn(api, username) };
}

async function logIn(api: APIRequestContext, username: string): Promise<string> {
  const response = await api.post("/v1/login", { data: { username, password: PASSWORD } });
  expect(response.status()).toBe(200);
  return ((await response.json()) as { accessToken: string }).accessToken;
}

async function provision(api: APIRequestContext, token: string, name: string): Promise<void> {
  const created = await api.put(`/v2/service_instances/${name}?accepts_incomplete=true`, {
    headers: { ...bearer(token), ...BROKER_HEADERS },
    data: { service_id: "tri-edge-tenant", plan_id: "standard", parameters: spec(name, [`${name}.example.test`]) },
  });
  expect(created.status()).toBe(202);
  expect(await waitForOperation(api, token, name)).toMatch(/is active$/);
}

async function liveIn(api: APIRequestContext, token: string, name: string, regions: readonly Region[]) {
  await expect
    .poll(
      async () => {
        const response = await api.get(`/api/tenants/${name}`, { headers: bearer(token) });
        const body = (await response.json()) as { applied?: Record<string, number> };
        return regions.every((region) => (body.applied?.[region] ?? 0) >= 1);
      },
      { timeout: 120_000, intervals: [1000] },
    )
    .toBe(true);
}

async function tenantNames(api: APIRequestContext, token: string): Promise<string[]> {
  const response = await api.get("/api/tenants", { headers: bearer(token) });
  expect(response.status()).toBe(200);
  return ((await response.json()) as { name: string }[]).map((tenant) => tenant.name);
}

test("every user lives in a home region and nothing is lost when eu goes down", async () => {
  const api = {} as Record<Region, APIRequestContext>;
  for (const region of REGIONS) {
    api[region] = await http.newContext({ baseURL: gateway(region), extraHTTPHeaders: { host: "localhost" } });
  }

  const residents: Resident[] = [];
  for (const region of REGIONS) {
    const { username, session } = await signUpAndLogIn(api[region], region);
    const me = await api[region].get("/v1/users/me", { headers: bearer(session) });
    expect(((await me.json()) as { homeRegion: string }).homeRegion).toBe(region);
    const token = await createToken(api[region], session, ["edge:read", "edge:write"]);
    const tenant = `geo-${region}-${randomUUID().slice(0, 6)}`;
    await provision(api[region], token, tenant);
    await liveIn(api[region], token, tenant, REGIONS);
    residents.push({ region, username, token, tenant });
  }

  for (const resident of residents) {
    for (const region of REGIONS) {
      const served = await viaHost(api[region], `${resident.tenant}.example.test`);
      expect(served.status()).toBe(200);
      expect(served.headers()["x-served-by-region"]).toBe(region);
      expect(await tenantNames(api[region], resident.token)).toContain(resident.tenant);
    }
  }

  const european = residents.find((resident) => resident.region === "eu")!;
  console.log(`powering off eu: ${process.env.KILL_COMMAND}`);
  execSync(process.env.KILL_COMMAND ?? "false", { stdio: "inherit" });
  const lostAt = Date.now();

  await expect
    .poll(async () => (await api.us.post("/v1/login", { data: { username: european.username, password: PASSWORD } })).status(),
      { timeout: 300_000, intervals: [2000] })
    .toBe(200);
  console.log(`eu users could log in again ${Math.round((Date.now() - lostAt) / 1000)} s after eu went down`);

  for (const resident of residents) {
    for (const region of ["us", "ap"] as const) {
      expect(await tenantNames(api[region], resident.token)).toContain(resident.tenant);
      expect((await viaHost(api[region], `${resident.tenant}.example.test`)).status()).toBe(200);
    }
  }

  const afterLoss = `geo-eu2-${randomUUID().slice(0, 6)}`;
  await provision(api.ap, european.token, afterLoss);
  await liveIn(api.ap, european.token, afterLoss, ["us", "ap"]);
  expect((await viaHost(api.us, `${afterLoss}.example.test`)).status()).toBe(200);
  console.log(`a new tenant for an eu user went live in us and ap ${Math.round((Date.now() - lostAt) / 1000)} s after eu went down`);

  for (const region of REGIONS) {
    await api[region].dispose();
  }
});
