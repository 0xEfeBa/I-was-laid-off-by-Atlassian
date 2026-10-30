import http from "k6/http";
import { check, fail, sleep } from "k6";

const GATEWAY = __ENV.GATEWAY_URL || "http://envoy:8080";
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || "2m";
const PASSWORD = "correct horse battery staple";
const PLATFORM = { host: "localhost" };
const JSON_HEADERS = { "content-type": "application/json", ...PLATFORM };
const BROKER = { "x-broker-api-version": "2.17", ...PLATFORM };

export const options = {
  scenarios: {
    traffic: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{route:public}": ["p(95)<200"],
    "http_req_duration{route:private}": ["p(95)<200"],
  },
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
};

function post(path, body, headers = {}) {
  return http.post(`${GATEWAY}${path}`, JSON.stringify(body), { headers: { ...JSON_HEADERS, ...headers } });
}

function waitFor(predicate, what) {
  for (let i = 0; i < 120; i++) {
    if (predicate()) {
      return;
    }
    sleep(0.5);
  }
  fail(`timed out waiting for ${what}`);
}

export function setup() {
  const suffix = Math.random().toString(36).slice(2, 10);
  const username = `load-${suffix}`;
  post("/v1/signup", { username, email: `${username}@example.com`, password: PASSWORD });
  const session = post("/v1/login", { username, password: PASSWORD }).json("accessToken");
  const token = post(
    "/v1/tokens",
    { name: "load", scopes: ["edge:read", "edge:write"], expiresInDays: 1 },
    { authorization: `Bearer ${session}` },
  ).json("value");
  const auth = { authorization: `Bearer ${token}`, ...PLATFORM };
  waitFor(() => http.get(`${GATEWAY}/api/tenants`, { headers: auth }).status === 200, "the token");

  const name = `load-${suffix}`;
  const host = `${name}.localhost`;
  const created = http.put(
    `${GATEWAY}/v2/service_instances/${name}?accepts_incomplete=true`,
    JSON.stringify({
      service_id: "tri-edge-tenant",
      plan_id: "standard",
      parameters: {
        apiVersion: "tri-edge/v1",
        name,
        hosts: [host],
        backends: [{ name: "main", address: "echo:8180" }],
        routes: [
          { path: "/private", backend: "main", auth: "any" },
          { path: "/", backend: "main" },
        ],
      },
    }),
    { headers: { ...JSON_HEADERS, ...BROKER, ...auth } },
  );
  if (created.status !== 202) {
    fail(`tenant was not accepted: ${created.status} ${created.body}`);
  }
  waitFor(() => {
    const detail = http.get(`${GATEWAY}/api/tenants/${name}`, { headers: auth });
    return detail.status === 200 && detail.json("applied.eu") === 1;
  }, "the tenant to go live");
  return { name, host, token };
}

export default function (data) {
  if (Math.random() < 0.8) {
    const response = http.get(`${GATEWAY}/items/${__ITER}`, { headers: { host: data.host }, tags: { route: "public" } });
    check(response, { "public 200": (r) => r.status === 200 });
  } else {
    const response = http.get(`${GATEWAY}/private`, {
      headers: { host: data.host, authorization: `Bearer ${data.token}` },
      tags: { route: "private" },
    });
    check(response, { "private 200": (r) => r.status === 200 });
  }
}

export function teardown(data) {
  http.del(
    `${GATEWAY}/v2/service_instances/${data.name}?service_id=tri-edge-tenant&plan_id=standard&accepts_incomplete=true`,
    null,
    { headers: { ...BROKER, authorization: `Bearer ${data.token}` } },
  );
}
