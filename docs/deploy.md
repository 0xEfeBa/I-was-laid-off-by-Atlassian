# Releases and deploys

## Images

Every push to `main` that passes `ci` runs `release`. It builds seven images once (identity, edge-sidecar, broker, echo, control-plane, patroni, migrations), tags each with the commit SHA, pushes them to `ghcr.io/0xefeba/tri-edge-<name>`, attaches an SBOM and build provenance, and signs them with cosign using the workflow's GitHub identity. No signing key is stored anywhere.

## Deploying a commit

```bash
VERSION=<commit sha> deploy/hetzner/regions.sh up
```

creates the servers from those images instead of building on each server.

```bash
deploy/hetzner/regions.sh deploy <commit sha>
```

updates running servers:

1. Checks with cosign that every image was signed by the `release` workflow on `main`.
2. Runs the database migrations of that commit on all three clusters.
3. Updates eu, then us, then ap. In each region the two control planes, identity, the broker, the sidecar and the sample backend are replaced one at a time, each must report healthy, and the region's gateway must answer before the next region starts. A failure stops the deploy and leaves the remaining regions on the previous version.

Rolling back is a deploy of the older SHA. Envoy, etcd and the databases are not restarted. Changes to the compose file or other configuration still need `up`.

## Migrations must work with the previous version

Migrations run before any service is replaced, so the old code runs against the new schema for the whole deploy, and a rollback runs it again. A migration may add tables, columns with defaults, or indexes. Renaming or dropping something takes two releases: the first stops using it, the second removes it.

## Known limits

- Identity, the broker and the sidecar run once per region. Replacing one makes its part of that region unavailable for about 30 to 40 seconds; the sidecar answers 503 until it has started and fetched the signing keys. Only one region is affected at a time.
- The GHCR packages must be public, or the servers need a read-only token, before `up` or `deploy` can pull them.
