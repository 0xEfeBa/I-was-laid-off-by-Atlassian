# Threat model

This document looks at tri-edge from an attacker's point of view. Every
threat has a mitigation, a way to verify it, and the risk that remains.
Any pull request that adds a component, an endpoint or a data flow
updates this file.

Threats are classified with STRIDE: Spoofing, Tampering, Repudiation,
Information disclosure, Denial of service, Elevation of privilege.

## Assets

| ID | Asset |
| :--- | :--- |
| A1 | User credentials: password hashes, personal access tokens, refresh tokens |
| A2 | JWT signing keys |
| A3 | Tenant configurations that decide where traffic goes |
| A4 | Acknowledged writes, which must survive the loss of a region |
| A5 | Availability of the edge |

## Attackers

| ID | Attacker |
| :--- | :--- |
| S1 | Anonymous user on the internet |
| S2 | Registered user acting in bad faith |
| S3 | Tenant owner trying to reach other tenants or the platform |
| S4 | Compromised third-party dependency or GitHub Action |

## Trust boundaries

| ID | Boundary |
| :--- | :--- |
| B1 | Internet → Envoy |
| B2 | Envoy → edge-sidecar → services |
| B3 | Tenant definition → broker → control plane → Envoy configuration |
| B4 | Tenant authorization hook and backends → edge |
| B5 | Services → data stores |
| B6 | Primary region → standby regions |
| B7 | Build pipeline → artifacts and images |

## Threats

| ID | Boundary | STRIDE | Threat | Mitigation | Verified by | Residual risk |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| T1 | B1 | I | Error responses leak internal details such as stack traces or connection strings | Unexpected errors return a generic 500 with only a trace_id; details go to server logs | `ProblemDetailsAdviceTest` | None known |
| T2 | B1 | I, E | Health, metrics or internal endpoints are reachable from the internet | Actuator runs on a separate management port that the edge never routes to; every tenant's `/internal/` paths answer 404 at the edge | `ConfigRendererTest`, `negative-auth.spec.ts`, `tenant-lifecycle.spec.ts` | None known |
| T3 | B7 | I | Secrets end up in the repository | Local secrets are generated into a git-ignored `.env`; gitleaks scans the full history on every push | `security.yml` | Secrets pasted into issues or logs |
| T4 | B7 | T | A compromised dependency or GitHub Action runs in the build | Actions pinned to commit SHAs, Trivy scans, Dependabot updates, pnpm blocks very new releases and install scripts | `security.yml` | Unknown zero-day vulnerabilities |
| T5 | B7 | E | A workflow token is abused to modify the repository | Workflows run with read-only permissions and do not keep credentials after checkout | Workflow review | None known |
| T6 | B5 | I | Local development databases are reachable from the local network | All local ports bind to 127.0.0.1 only | `docker-compose.yml` | Development machine only |
| T7 | B5 | I, E | A compromised service reads or changes another service's data, for example password hashes | One database role per service with access to its own schema only; read-only roles get column-level grants that exclude password hashes | `db/tests/grants_test.sql` in CI | A compromised identity service still sees all of `identity` |
| T8 | B5 | T | Other services act on a change that was rolled back, or miss a change that was committed | Events are written to the outbox in the same transaction as the change; writing outside a transaction is refused; readers only read events from transactions older than the oldest one still open | `OutboxWriterTest`, `OutboxOrderingIT` | A very long transaction delays every later event until it finishes |
| T9 | B5 | T | Two workers run the same background job, or a stalled worker overwrites the result of the worker that took over | Jobs are claimed with `FOR UPDATE SKIP LOCKED`; a lock that expires hands the job over, and the attempt number fences out the stalled worker | `JobQueueIT`, `BrokerApiIT` | Side effects outside the database must still be idempotent |
| T10 | B5, B6 | T | An acknowledged write is lost when a region goes down, or a service keeps writing to a database that was demoted | A commit is acknowledged only after at least one other region has it (Patroni quorum mode, strict); Patroni only promotes a standby that has every acknowledged write; services connect with `targetServerType=primary`, so they always write to the current leader | `RegionLossIT`, `RemoteRegionLossIT`, `PrimaryGuardIT` | The three-continent run has not happened yet; automatic failover was only tried locally with two members |
| T11 | B7 | I | Database passwords leak through logs | Connection settings never print their password | `ClusterConnectionTest` | Passwords passed on a command line |
| T12 | B1, B2 | S, E | A client sends its own `x-user-id` or `x-user-role` header to impersonate another user or an admin | The sidecar removes every `x-user-*`, `x-tenant-*`, `x-target-region`, `x-backend-set` and `x-internal-*` header the client sent and sets its own from verified credentials | `AuthorizationServiceTest`, `EnvoyExtAuthzIT`, `negative-auth.spec.ts`, `tenant-lifecycle.spec.ts` | None known |
| T13 | B1 | S | A forged or tampered access token is accepted | Only ES256 tokens are accepted; the key is looked up by id from the published key set; signature, issuer and expiry are checked; unsigned tokens are rejected | `JwtValidatorTest`, `JwksProviderTest`, `negative-auth.spec.ts` | A leaked signing key; mitigated by rotation through the key set |
| T14 | B1 | S | Password guessing or credential stuffing | Argon2id hashes; after five failures each account waits exponentially longer, up to 15 minutes, and the count starts over after an hour without failures; unknown users cost the same time as known ones; the edge allows each client address 20 login and 20 signup attempts a minute | `LoginThrottleIT`, `IdentityApiIT`, `ConfigRendererTest` | Attacks spread over many addresses; the edge limit is counted separately in each region; anyone can keep an account locked for up to 15 minutes at a time by failing its logins |
| T15 | B1 | S | A stolen refresh token is used | Refresh tokens are single-use and rotate on every use; reusing one revokes the whole session family; the cookie is HttpOnly, SameSite=Strict and host-only | `RefreshTokenServiceIT`, `IdentityApiIT` | Theft and use before the owner's next refresh |
| T16 | B1 | T | A malicious site makes the browser refresh or end a session | Cookie endpoints require an allowed `Origin`; the cookie is SameSite=Strict | `IdentityApiIT` | None known |
| T17 | B5 | I | Personal access tokens leak from the database or logs | Only a SHA-256 hash is stored; the token is shown once; responses listing tokens never contain it; tokens carry limited scopes and optional expiry | `IdentityApiIT`, `signup-login.spec.ts` | A token copied by its owner into an unsafe place |
| T18 | B2 | S, E | A request reaches a backend without going through Envoy and sets identity headers itself | Backends are not published outside the private network; only Envoy is | `docker-compose.yml` | Enforced only by the Docker network |
| T19 | B4 | E, D | A tenant's authorization hook adds headers that pick another tenant's backend or region, or stalls every request | Hook headers pass an allowlist limited to the tenant's own regions and backends; hooks get 50 ms; a failing hook closes the route unless the tenant opted to fail open | `HeaderAllowlistTest`, `HookClientTest`, `AuthorizationServiceTest` | None known |
| T20 | B2 | E | A banned user keeps using sessions or tokens | Bans revoke tokens in the database and reach every sidecar through the outbox within a second; sessions of a banned user are refused even before they expire | `AuthPolicyTest`, `IdentityDirectoryIT`, `IdentityApiIT` | Up to one poll interval of delay |
| T21 | B3 | T, E | A tenant spec smuggles extra Envoy configuration, for example a host string that closes a block and opens a new virtual host | Specs must match a strict JSON Schema that refuses unknown fields; Envoy configuration is built as protobuf objects, never from text templates, so every value stays a value | `TenantSpecParserTest`, `ConfigRendererTest` | None known |
| T22 | B3 | S, T | A tenant takes a name or host that belongs to another tenant or to the platform | Names and hosts are reserved together in one etcd transaction; the control plane refuses hosts that another tenant already serves; platform names are reserved | `TenantReservationsIT`, `ValidationServiceTest`, `BrokerApiIT` | A reservation left behind by a crash is held until the janitor frees it, after five minutes |
| T23 | B3, B4 | E | A tenant points its backends or hook at platform services, cloud metadata or raw IP addresses | The control plane refuses platform host names, `*.localhost`, `*.internal` and IP literals as targets | `NamespaceOwnershipCheckTest`, `tenant-lifecycle.spec.ts` | A public DNS name that resolves to an internal address; needs an egress network policy |
| T24 | B3 | D | A broken configuration reaches the proxies and takes the edge down | Every version is checked by the real Envoy binary before it is activated; rejected versions never reach the proxies; NACKs from proxies are recorded; any earlier version can be rolled back to | `ProcessEnvoyValidatorTest`, `EnvoyXdsIT`, `BrokerApiIT`, `tenant-lifecycle.spec.ts` | A valid configuration that routes to the wrong place; rollback is manual |
| T25 | B1, B3 | E | A user reads, changes or deletes someone else's tenant | The broker checks ownership on every call and answers 404 to everyone else; changes need a token with the `edge:write` scope | `BrokerApiIT`, `tenant-lifecycle.spec.ts` | None known |
| T26 | B2 | E, D | The sidecar is down or slow and requests go through unchecked | ext_authz fails closed: after 250 ms Envoy answers 503 instead of passing the request on | `ConfigRendererTest` | A sidecar outage makes every route unavailable |
| T27 | B6 | I, T | Traffic between regions is read or changed on the way: replication, etcd, and requests sent to another region's services | All of it goes through a WireGuard mesh; the firewall only opens SSH, the gateway and WireGuard to the internet, so database, etcd and service ports are unreachable from outside | `deploy/hetzner/regions.sh` firewall rules | Only checked once the servers exist |
| T28 | B2 | S, E | A client sends its own `x-user-home-region` to make a service read or write another region's data | The sidecar strips every `x-user-*` header the client sent and sets the home region from the verified user; services pick the database from that header only | `AuthorizationServiceTest`, `HomeClusterFilterTest`, `IdentityHomeRegionIT`, `BrokerHomeRegionIT` | None known |

## Accepted risks

| Risk | Why it is accepted |
| :--- | :--- |
| Large-scale DDoS | Needs a dedicated provider; out of scope for this project |
| A region is down for a few dozen seconds before failover | Patroni waits for its leader lease to expire before promoting; shorter leases cause false failovers over long distances |
| Writes from far away are slower | A write waits for one other continent; this is the price of losing nothing and is measured by `regions.sh measure` |
