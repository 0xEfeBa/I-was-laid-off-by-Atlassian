const gateway = process.env.GATEWAY_URL ?? "http://localhost:8080";
const READY_IN_A_ROW = 5;

async function status(path: string, headers: Record<string, string> = {}): Promise<number> {
  try {
    return (await fetch(`${gateway}${path}`, { headers })).status;
  } catch {
    return 0;
  }
}

async function ready(): Promise<boolean> {
  const identity = await status("/.well-known/jwks.json");
  const sidecar = await status("/v1/users/me", { authorization: "Bearer warm.up.token" });
  return identity === 200 && sidecar === 401;
}

export default async function globalSetup(): Promise<void> {
  const deadline = Date.now() + 120_000;
  let inARow = 0;
  while (Date.now() < deadline) {
    inARow = (await ready()) ? inARow + 1 : 0;
    if (inARow === READY_IN_A_ROW) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Gateway at ${gateway} did not become ready; run scripts/dev.sh app`);
}
