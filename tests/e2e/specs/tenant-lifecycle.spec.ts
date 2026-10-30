import { bearer, expect, test } from "../fixtures/users";
import {
  BROKER_HEADERS,
  createToken,
  lastOperation,
  spec,
  tenantName,
  viaHost,
  waitForOperation,
  waitUntilLive,
} from "../fixtures/tenants";

test("a tenant goes live, changes, rolls back and disappears", async ({ request, user }) => {
  const token = await createToken(request, user.accessToken, ["edge:read", "edge:write"]);
  const headers = { ...bearer(token), ...BROKER_HEADERS };
  const name = tenantName();
  const host = `${name}.localhost`;
  const extraHost = `www-${name}.localhost`;

  expect((await viaHost(request, host)).status()).toBe(404);

  const created = await request.put(`/v2/service_instances/${name}?accepts_incomplete=true`, {
    headers,
    data: { service_id: "tri-edge-tenant", plan_id: "standard", parameters: spec(name, [host]) },
  });
  expect(created.status()).toBe(202);
  expect(await waitForOperation(request, token, name)).toBe("Version 1 is active");
  await waitUntilLive(request, token, name, 1);

  const open = await viaHost(request, host, "/hello", { "x-user-id": "forged" });
  expect(open.status()).toBe(200);
  expect(open.headers()["x-served-by-region"]).toBe("eu");
  const echoed = (await open.json()) as { path: string; headers: Record<string, string> };
  expect(echoed.path).toBe("/hello");
  expect(echoed.headers["x-user-id"]).toBeUndefined();

  expect((await viaHost(request, host, "/private")).status()).toBe(401);
  const signedIn = await viaHost(request, host, "/private", bearer(user.accessToken));
  expect(signedIn.status()).toBe(200);
  expect(((await signedIn.json()) as { headers: Record<string, string> }).headers["x-user-id"]).toBe(user.id);
  expect((await viaHost(request, host, "/internal/metrics")).status()).toBe(404);

  const updated = await request.patch(`/v2/service_instances/${name}?accepts_incomplete=true`, {
    headers,
    data: { service_id: "tri-edge-tenant", parameters: spec(name, [host, extraHost]) },
  });
  expect(updated.status()).toBe(202);
  expect(await waitForOperation(request, token, name)).toBe("Version 2 is active");
  await waitUntilLive(request, token, name, 2);
  expect((await viaHost(request, extraHost)).status()).toBe(200);

  const rollback = await request.post(`/api/tenants/${name}/rollback`, { headers: bearer(token), data: { version: 1 } });
  expect(rollback.status()).toBe(202);
  expect(await waitForOperation(request, token, name)).toBe("Version 3 is active");
  await waitUntilLive(request, token, name, 3);
  expect((await viaHost(request, extraHost)).status()).toBe(404);

  const deleted = await request.delete(
    `/v2/service_instances/${name}?service_id=tri-edge-tenant&plan_id=standard&accepts_incomplete=true`,
    { headers },
  );
  expect(deleted.status()).toBe(202);
  await expect.poll(async () => (await lastOperation(request, token, name)).status(), { timeout: 30_000 }).toBe(410);
  await expect.poll(async () => (await viaHost(request, host)).status(), { timeout: 30_000 }).toBe(404);
});

test("the control plane rejects tenants that point at platform services", async ({ request, user }) => {
  const token = await createToken(request, user.accessToken, ["edge:read", "edge:write"]);
  const name = tenantName();

  const created = await request.put(`/v2/service_instances/${name}?accepts_incomplete=true`, {
    headers: { ...bearer(token), ...BROKER_HEADERS },
    data: {
      service_id: "tri-edge-tenant",
      plan_id: "standard",
      parameters: spec(name, [`${name}.localhost`], "identity:8130"),
    },
  });
  expect(created.status()).toBe(202);

  expect(await waitForOperation(request, token, name)).toContain("which tenants may not use");
  expect((await viaHost(request, `${name}.localhost`)).status()).toBe(404);
});

test("tenant changes need a token with the write scope", async ({ request, user }) => {
  const readOnly = await createToken(request, user.accessToken, ["edge:read"]);
  const name = tenantName();

  const denied = await request.put(`/v2/service_instances/${name}?accepts_incomplete=true`, {
    headers: { ...bearer(readOnly), ...BROKER_HEADERS },
    data: { service_id: "tri-edge-tenant", plan_id: "standard", parameters: spec(name, [`${name}.localhost`]) },
  });
  expect(denied.status()).toBe(403);
  expect((await request.get("/api/tenants", { headers: bearer(readOnly) })).status()).toBe(200);
});
