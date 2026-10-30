import { bearer, expect, signUp, test } from "../fixtures/users";

test("protected routes need a session", async ({ request }) => {
  const response = await request.get("/v1/users/me");

  expect(response.status()).toBe(401);
  expect(response.headers()["content-type"]).toContain("application/problem+json");
});

test("forged or garbage tokens are rejected", async ({ request }) => {
  const forged =
    "eyJhbGciOiJFUzI1NiIsImtpZCI6ImZha2UifQ.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAiLCJyb2xlIjoiQURNSU4ifQ.c2ln";
  expect((await request.get("/v1/users/me", { headers: bearer(forged) })).status()).toBe(401);
  expect((await request.get("/v1/users/me", { headers: bearer("garbage") })).status()).toBe(401);
});

test("identity headers sent by the client are ignored", async ({ request, user }) => {
  const victim = await signUp(request);

  const withoutToken = await request.get("/v1/users/me", {
    headers: { "x-user-id": victim.id, "x-user-role": "ADMIN" },
  });
  expect(withoutToken.status()).toBe(401);

  const withToken = await request.get("/v1/users/me", {
    headers: { ...bearer(user.accessToken), "x-user-id": victim.id, "x-user-role": "ADMIN" },
  });
  expect(withToken.status()).toBe(200);
  expect(await withToken.json()).toMatchObject({ id: user.id, role: "USER" });

  const ban = await request.post(`/v1/admin/users/${victim.id}/ban`, {
    headers: { ...bearer(user.accessToken), "x-user-role": "ADMIN" },
  });
  expect(ban.status()).toBe(403);
});

test("an access token cannot be used where a browser session is required", async ({ request, user }) => {
  const created = await request.post("/v1/tokens", {
    headers: bearer(user.accessToken),
    data: { name: "ci", scopes: ["edge:read"] },
  });
  const { value } = (await created.json()) as { value: string };

  expect((await request.get("/v1/tokens", { headers: bearer(value) })).status()).toBe(401);
});

test("internal and unknown paths are not exposed", async ({ request }) => {
  expect((await request.get("/internal/health")).status()).toBe(404);
  expect((await request.get("/actuator/health")).status()).toBe(404);
  expect((await request.get("/nothing-here")).status()).toBe(404);
});
