# I was laid off by Atlassian

No, not me.

In May 2026, Vasilios Syrakis was laid off by Atlassian after almost eight years on their edge team. Instead of quietly moving on, he recorded a 38-minute video walking through the systems he had built: a self-service broker that let any team spin up their own load balancer, a control plane that kept thousands of Envoy proxies configured, and a sidecar that handled authentication and rate limiting for Jira, Confluence and Bitbucket.

I watched it twice and wanted to build it myself. This repo is that attempt, and the name is a nod to the video that started it.

## What I'm building

A small-scale version of that edge platform. I kept it to the parts that make the idea work and dropped everything around them, so each claim below is backed by a test you can rerun:

- A team registers a service through an Open Service Broker API, and it goes live behind Envoy in seconds without anyone touching the proxy.
- A bad config never reaches the proxies. Every version is checked by a real Envoy binary first, and any earlier version can be rolled back to.
- One tenant can't hurt another or the platform, by accident or on purpose: no stealing hosts, no pointing backends at internal services, no injecting config.
- The edge decides who you are. The sidecar checks sessions and access tokens, and identity headers sent by clients never reach a backend.
- Every user and tenant lives in a home region, and if a whole region goes down no acknowledged write is lost and its users are served from the region that took over.

## How it fits together

| Service | What it does |
| :--- | :--- |
| identity | Sign-up, login, short-lived JWTs, refresh cookies and personal access tokens |
| edge-sidecar | Answers Envoy's ext_authz checks from memory and strips forged identity headers |
| broker | Open Service Broker API for creating, updating, rolling back and deleting tenants |
| control-plane | Turns tenants into Envoy configuration, validates it and pushes it live over xDS |
| echo | A tiny backend that echoes requests, used as a tenant in tests |

## Three regions

Each region runs the full stack: Envoy, the sidecar, the control plane, identity, the broker and echo. Data is split by home region: a user who signs up in Singapore lives in the `ap` database, a tenant lives where its owner lives.

```
        Germany (eu)              US (us)                Singapore (ap)
   eu database  (primary)   us database  (primary)   ap database  (primary)
   us database  (standby)   ap database  (standby)   eu database  (standby)
   ap database  (standby)   eu database  (standby)   us database  (standby)
   etcd ◀──────────────────▶ etcd ◀────────────────▶ etcd
```

- Each database is a Patroni cluster with a standby on both other continents. A write is acknowledged only after one of them has it.
- When a region dies, Patroni promotes the standby that has every acknowledged write, and the sidecars start sending that region's users to wherever its database now lives.
- Reads that don't belong to anyone, like serving tenant traffic, are answered by the nearest region.

`deploy/hetzner/regions.sh up` builds this on three servers joined by WireGuard, and `deploy/hetzner/regions.sh test` powers off Germany in the middle of a run. The real three-continent numbers will go here once it has been run.

## Results

**Tenant lifecycle.** A tenant is created, serves traffic through Envoy, gets a new host, rolls back and is deleted in about six seconds end to end (`tests/e2e`).

**Region loss.** Three PostgreSQL servers stand in for three regions. Writes run nonstop, the primary is killed mid-write, and the most up-to-date standby is promoted (`tests/region-loss`):

| Replication | Acknowledged writes | Lost after the region died |
| :--- | ---: | ---: |
| Synchronous, `ANY 1 (us, ap)` | 429 | 0 |
| Asynchronous, for comparison | 100 | 100 |

**Load.** k6 sends traffic through the gateway, 80% to a public route and 20% to a route that needs a token, so every request goes through the auth sidecar. Everything runs on one laptop inside a 2-CPU Docker VM, k6 included (`tests/load`):

| Rate | p95 latency | Failed requests |
| :--- | ---: | ---: |
| 100 requests/s for 2 minutes | 18–40 ms | 0.09–0.15% |
| 200 requests/s for 2 minutes | 700 ms | 2.2% (the VM runs out of CPU) |

That is about 360,000 requests an hour on a laptop. These numbers are from warmed-up services; in the first minute after a restart p95 climbs to around 230 ms while the JVMs warm up.

## Running it

```bash
scripts/dev.sh up
scripts/dev.sh app
pnpm --filter @tri-edge/e2e test
./gradlew :tests:region-loss:test
scripts/dev.sh load
```

## Stack

Java 21 and Spring Boot for the services, Envoy at the edge with a Java xDS control plane, PostgreSQL with Patroni for automatic failover, etcd for leader election and global name reservations, WireGuard between regions, Hetzner servers on three continents, Playwright for end-to-end tests and k6 for load.

## Credit

All credit for the original design goes to Vasilios Syrakis and the Atlassian edge team. Go watch his video, it's worth it.
