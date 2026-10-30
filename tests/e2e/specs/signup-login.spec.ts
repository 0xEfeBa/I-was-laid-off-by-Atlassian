import { ORIGIN, bearer, expect, logIn, signUp, test } from "../fixtures/users";

test("a new user can sign up, log in and see their profile", async ({ request }) => {
  const { username } = await signUp(request);
  const token = await logIn(request, username);

  const me = await request.get("/v1/users/me", { headers: bearer(token) });

  expect(me.status()).toBe(200);
  expect(await me.json()).toMatchObject({ username, role: "USER", homeRegion: "eu" });
  expect(me.headers()["x-served-by-region"]).toBe("eu");
});

test("the same username cannot be taken twice", async ({ request }) => {
  const { username } = await signUp(request);

  const again = await request.post("/v1/signup", {
    data: { username, email: `${username}-2@example.com`, password: "correct horse battery staple" },
  });

  expect(again.status()).toBe(409);
});

test("personal access tokens are shown once and can be revoked", async ({ request, user }) => {
  const created = await request.post("/v1/tokens", {
    headers: bearer(user.accessToken),
    data: { name: "laptop", scopes: ["git:read", "git:write"], expiresInDays: 30 },
  });
  expect(created.status()).toBe(201);
  const { token, value } = (await created.json()) as { token: { id: string }; value: string };
  expect(value).toMatch(/^tep_/);

  const list = await request.get("/v1/tokens", { headers: bearer(user.accessToken) });
  expect(await list.json()).toEqual([expect.objectContaining({ id: token.id, name: "laptop" })]);
  expect(JSON.stringify(await list.json())).not.toContain(value);

  expect((await request.delete(`/v1/tokens/${token.id}`, { headers: bearer(user.accessToken) })).status()).toBe(204);
  expect(await (await request.get("/v1/tokens", { headers: bearer(user.accessToken) })).json()).toEqual([]);
});

test("the refresh cookie renews the session and logout ends it", async ({ request, user: _user }) => {
  const refreshed = await request.post("/v1/token/refresh", { headers: { origin: ORIGIN } });
  expect(refreshed.status()).toBe(200);
  const { accessToken } = (await refreshed.json()) as { accessToken: string };
  expect((await request.get("/v1/users/me", { headers: bearer(accessToken) })).status()).toBe(200);

  expect((await request.post("/v1/logout", { headers: { origin: ORIGIN } })).status()).toBe(204);
  expect((await request.post("/v1/token/refresh", { headers: { origin: ORIGIN } })).status()).toBe(401);
});
