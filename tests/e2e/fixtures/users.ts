import { test as base, expect, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";

export const PASSWORD = "correct horse battery staple";
export const ORIGIN = "http://localhost:5173";

export interface TestUser {
  id: string;
  username: string;
  accessToken: string;
}

export async function signUp(request: APIRequestContext): Promise<{ id: string; username: string }> {
  const username = `e2e-${randomUUID().slice(0, 12)}`;
  const response = await request.post("/v1/signup", {
    data: { username, email: `${username}@example.com`, password: PASSWORD },
  });
  expect(response.status()).toBe(201);
  const body = (await response.json()) as { id: string; username: string };
  return body;
}

export async function logIn(request: APIRequestContext, username: string): Promise<string> {
  const response = await request.post("/v1/login", { data: { username, password: PASSWORD } });
  expect(response.status()).toBe(200);
  const body = (await response.json()) as { accessToken: string };
  return body.accessToken;
}

export const test = base.extend<{ user: TestUser }>({
  user: async ({ request }, use) => {
    const { id, username } = await signUp(request);
    const accessToken = await logIn(request, username);
    await use({ id, username, accessToken });
  },
});

export { expect };

export function bearer(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}
