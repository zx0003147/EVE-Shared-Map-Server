# EVE Shared Map Server

EVE Shared Map Server is the collaboration backend for EVE Static Map Planner. Version 0.3.0 provides the identity,
Workspace, membership, invite, device-token, authentication, authorization, audit, idempotency, complete Shared
Marker API, and the optional bounded Route Handoff feature. The Map desktop repository is not a dependency of this
server and remains usable without it.

## Quick Install (self-hosted)

You need only:

- an Ubuntu 24.04 LTS x86_64 VPS with at least 2 GiB RAM and 20 GiB free disk;
- a domain you can edit;
- SSH access with `sudo`.

From a published self-hosted release checkout, run:

```sh
git clone --branch v0.3.0 --depth 1 https://github.com/zx0003147/EVE-Shared-Map-Server.git
cd EVE-Shared-Map-Server
sudo ./install.sh
```

The installer asks for the Shared Marker hostname, Web Map hostname, certificate email, Workspace name, and first
Admin display name. It then shows the two DNS A records to create and waits until both point to the VPS. Everything
else—official Docker Engine installation, PostgreSQL, Caddy HTTPS, file-mounted secrets, the verified Planner Web
artifact, exact Server images, Flyway, health checks, exact-origin CORS, and first-Admin bootstrap—is automated.
If a run is interrupted after it creates the protected installation marker, rerun `sudo ./install.sh --resume`; the
resume path reuses the same manifest, environment, secrets, database, and staged release and never treats an unknown
existing deployment as installer-owned.

When installation finishes, save the one-time Admin invite immediately. It is deliberately neither logged nor
stored. Open the displayed Web Map URL and use the Shared Marker URL with that invite.

Common operations are intentionally short:

```sh
sudo eve-map status
sudo eve-map update
sudo eve-map restart
sudo eve-map logs
sudo eve-map diagnostics
sudo eve-map backup
sudo eve-map web-pack /path/to/EVE-Web-Pack
sudo eve-map version
```

`update` always creates the existing encrypted backup first and never removes the PostgreSQL volume. `web-pack`
publishes the versioned gzip before atomically replacing `data/manifest.json`; it does not restart PostgreSQL or the
Shared Marker service. `diagnostics` never reads secret files and redacts invites, device tokens, Authorization, DB
password, token-pepper, passphrase, and generic secret assignments.

The installer creates a protected local backup copy so backups work immediately. For real disaster recovery, mount
storage whose failure domain is outside the VPS at `/opt/eve-shared-map/backups-offsite`; a directory on the same VPS
is not an off-site backup. Firewall and SSH hardening remain provider-specific—allow the proven SSH port plus TCP
80/443, and never expose 5432 or 8080. See [Production deployment](docs/PRODUCTION-DEPLOYMENT.md) for those advanced
operator responsibilities and [VPS acceptance](docs/SELF-HOSTED-ACCEPTANCE.md) before inviting real users.

The source repository intentionally contains no generated Planner Web bundle. `install.sh` consumes the locked
`self-hosted-release.json` asset from the latest published Server release. That manifest pins the official Web ZIP,
its SHA-256, and exact public GHCR image digests; use `--manifest-url` only for an approved alternate release channel.

## Developer prerequisites

- JDK 25 LTS
- Docker Desktop or Docker Engine with Docker Compose
- PowerShell on Windows, or a POSIX shell on Linux/macOS

The Gradle Wrapper is included; a system Gradle installation is not required.

## Local configuration

Copy the development environment template and create separate database-password and token-pepper secret files.
Never commit either file.

```powershell
Copy-Item .env.example .env
New-Item -ItemType Directory -Force .secrets
Set-Content -NoNewline .secrets/dev-db-password.txt 'choose-a-local-development-password'
$pepperBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($pepperBytes)
[IO.File]::WriteAllText((Join-Path (Get-Location) '.secrets/dev-token-pepper.txt'), [Convert]::ToBase64String($pepperBytes))
[Array]::Clear($pepperBytes, 0, $pepperBytes.Length)
```

Runtime configuration:

| Variable | Required/default | Purpose |
| --- | --- | --- |
| `SHARED_MAP_BIND_HOST` | `0.0.0.0` | Listener bind address |
| `SHARED_MAP_PORT` | `8080` | Listener port, 1–65535 |
| `SHARED_MAP_DATABASE_URL` | required | PostgreSQL JDBC URL |
| `SHARED_MAP_DATABASE_USER` | required unless file is set | PostgreSQL application user for local/development use |
| `SHARED_MAP_DATABASE_USER_FILE` | alternative | Readable non-empty PostgreSQL username file; production uses this instead of the ordinary variable |
| `SHARED_MAP_DATABASE_PASSWORD_FILE` | required | Readable non-empty database secret file |
| `SHARED_MAP_TOKEN_PEPPER_FILE` | required | Readable non-empty HMAC pepper secret file |
| `SHARED_MAP_ENVIRONMENT` | `development` | Environment label |
| `SHARED_MAP_LOG_LEVEL` | `INFO` | TRACE, DEBUG, INFO, WARN, or ERROR |
| `SHARED_MAP_ALLOWED_ORIGINS` | empty | Comma-separated exact Web client origins; remote origins must use HTTPS |

Secrets are not accepted as ordinary environment values, command-line arguments, or Gradle properties.

`SHARED_MAP_ALLOWED_ORIGINS` enables browser CORS without changing Desktop behavior. Use origins only, with no path,
query, fragment, credentials, or wildcard. Plain HTTP is accepted only for `localhost` and `127.0.0.1` development;
production examples must use exact HTTPS origins:

```text
SHARED_MAP_ALLOWED_ORIGINS=https://map.example.com,https://map-backup.example.com
```

The server allows `OPTIONS`, the Protocol v1 REST mutation methods, and only the headers required by existing
clients. It does not enable credentialed cookies. A disallowed browser origin receives `403`; a Desktop request
without `Origin` is unchanged.

## Start PostgreSQL

```powershell
docker compose -f docker-compose.dev.yml up -d postgres
```

For direct Gradle commands, load the local runtime configuration:

```powershell
$env:SHARED_MAP_DATABASE_URL = 'jdbc:postgresql://localhost:54329/eve_shared_map'
$env:SHARED_MAP_DATABASE_USER = 'eve_shared_map'
$env:SHARED_MAP_DATABASE_PASSWORD_FILE = (Resolve-Path .secrets/dev-db-password.txt).Path
$env:SHARED_MAP_TOKEN_PEPPER_FILE = (Resolve-Path .secrets/dev-token-pepper.txt).Path
$env:SHARED_MAP_ENVIRONMENT = 'development'
$env:SHARED_MAP_LOG_LEVEL = 'INFO'
```

## Bootstrap the first Admin

Bootstrap is a one-shot operator CLI, not an HTTP endpoint. It atomically creates the first user, Workspace, ADMIN
membership, and short-lived single-use invite. It refuses to run after any Workspace exists.

```powershell
.\gradlew.bat run --args='bootstrap-admin --display-name Coord_A --workspace-name "Alliance Strategic Map" --invite-ttl 1h'
```

The final `inviteToken=esm_inv_...` line is written once to the invoking terminal. Deliver it securely. It is not
stored as plaintext and cannot be shown again. Expected bootstrap errors return a nonzero exit code without a stack
trace or secret values.

With the Compose server image, the equivalent operator workflow is:

```powershell
docker compose -f docker-compose.dev.yml --profile server run --rm server bootstrap-admin --display-name Coord_A --workspace-name "Alliance Strategic Map" --invite-ttl 1h
```

## Invite and device-token flow

1. An Admin creates a user identity and Workspace membership.
2. The Admin creates an invite for that exact membership.
3. The recipient calls `POST /api/v1/auth/exchange-invite` with the invite and a device name.
4. The server consumes the invite and returns one 90-day `esm_dev_...` bearer token.
5. Later requests use `Authorization: Bearer <device-token>`.

Invite and device secrets contain 256 random bits. PostgreSQL stores only HMAC-SHA-256 digests keyed by the external
server pepper and a short non-secret operator prefix. A membership or device revocation takes effect on the next
request because every authorization decision resolves current server-side state.

Invite creation requires `Idempotency-Key`. Its first success returns the raw invite once. A same-key retry does not
create another invite and returns HTTP 409 `IDEMPOTENCY_RESPONSE_NOT_REPLAYABLE` with safe metadata. Revoke the
identified invite and create a replacement with a new key. Ordinary non-secret mutations retain normal 24-hour
response replay.

## Workspace roles

- `VIEWER`: authenticate and read self/Workspace data, Shared Markers, and Route Handoffs.
- `EDITOR`: Viewer capabilities plus Shared Marker create/update/delete and Route Handoff publish.
- `ADMIN`: Editor capabilities plus member, invite, role, membership, and device administration.

The final active Admin cannot be downgraded or removed.

## API v1 endpoints

- `GET /health`
- `GET /api/v1/meta`
- `POST /api/v1/auth/exchange-invite`
- `GET /api/v1/me`
- `GET /api/v1/me/devices`
- `DELETE /api/v1/me/devices/{tokenId}`
- `GET /api/v1/workspaces`
- `GET /api/v1/workspaces/{workspaceId}`
- `GET|POST /api/v1/workspaces/{workspaceId}/markers`
- `PATCH /api/v1/workspaces/{workspaceId}/markers/{markerId}`
- `DELETE /api/v1/workspaces/{workspaceId}/markers/{markerId}?expectedVersion={version}`
- `GET|POST /api/v1/workspaces/{workspaceId}/route-handoffs`
- `GET|POST /api/v1/workspaces/{workspaceId}/members`
- `PATCH|DELETE /api/v1/workspaces/{workspaceId}/members/{memberId}`
- `GET /api/v1/workspaces/{workspaceId}/members/{memberId}/devices`
- `DELETE /api/v1/workspaces/{workspaceId}/members/{memberId}/devices/{tokenId}`
- `GET /api/v1/workspaces/{workspaceId}/invites`
- `POST /api/v1/workspaces/{workspaceId}/members/{memberId}/invites`
- `DELETE /api/v1/workspaces/{workspaceId}/invites/{inviteId}`

All authenticated mutations require a canonical UUID `Idempotency-Key`, except invite exchange. Marker create,
update/delete and Route Handoff publish use ordinary replayable 24-hour idempotency; marker update/delete also
require optimistic-lock versions. `/api/v1/meta` keeps protocol major 1 and advertises `route-handoffs` separately
from `shared-markers` plus the packaged universe build. Older clients and older Servers remain feature-compatible.

Route Handoffs are independent Workspace rows, not Shared Marker rows and not Web Pack data. A publish stores route
intent and its resolved snapshot, validates systems/path/edge vocabulary/range, writes only safe audit metadata,
expires after seven days, and trims each Workspace to its newest 20 records. Viewer may read; Editor/Admin may
publish. No Device Token, local database, Personal Ansiblex, or unrelated Desktop state is accepted.

## Solar-system allowlist

The server validates every marker `systemId` against a compact immutable resource generated from the official CCP
EVE Online JSONL SDE. The current packaged resource contains 8,490 canonical IDs from build `3466501` and exposes
`universeBuild: sde-3466501` through `/api/v1/meta`. It does not contain the full SDE, connect to the Map database,
call ESI, or use the network at runtime. Missing, empty, duplicate, count-mismatched, or metadata-free resources fail
server startup.

Generation provenance, hashes, the deterministic update command, and verification steps are documented in
[`docs/SOLAR-SYSTEM-ALLOWLIST.md`](docs/SOLAR-SYSTEM-ALLOWLIST.md).

## Run the server

```powershell
.\gradlew.bat run
```

`/health` reports database readiness. Database failure returns HTTP 503 without connection details or exception text.

## Build and test

```powershell
.\gradlew.bat --no-daemon --console=plain clean build
```

The suite includes unit, Ktor HTTP, real PostgreSQL 18.6 Testcontainers, V1→V2→V3→V4 migration upgrade, Marker
concurrency, optimistic locking, Route Handoff authorization/isolation/validation/idempotency/expiry/bounds/audit,
allowlist, security, secret-storage, and end-to-end Marker lifecycle tests. Release acceptance requires Docker and
must have zero skipped PostgreSQL tests.

## Docker workflow

```powershell
docker build -t eve-shared-map-server:0.3.0 .
docker compose -f docker-compose.dev.yml --profile server up --build -d
docker compose -f docker-compose.dev.yml --profile server down
```

The development database binds only to `127.0.0.1:54329`. The server binds only to `127.0.0.1:8080` in the dev
topology, runs as numeric non-root user `10001`, and supports a read-only root filesystem with a bounded `/tmp`
tmpfs. PostgreSQL state remains in the named dev volume unless the operator explicitly adds `-v` when stopping.

## Migrations, audit, and logging

Flyway runs synchronously before Ktor becomes ready. `V1__skeleton.sql` is the Phase 1 baseline;
`V2__workspace_authentication.sql` creates only Phase 2 identity/auth tables, and
`V3__shared_markers.sql` adds the frozen Shared Marker table, constraints, and indexes. `V4__route_handoffs.sql`
adds the bounded, expiring Workspace Route Handoff resource. Flyway clean and automatic
repair remain disabled.

Audit events are append-only and contain event-time actor identity plus safe metadata. Marker rows are hard deleted,
while `MARKER_CREATED`, `MARKER_UPDATED`, and `MARKER_DELETED` audit events remain. `ROUTE_HANDOFF_PUBLISHED` stores
only request ID, route type, origin/destination, and resolved-system count. Audit never contains complete marker notes,
route bodies, bearer tokens, invite secrets, hashes, Authorization headers, database passwords, or request bodies.

Logs are JSON Lines on stdout. Access logs contain bounded request ID, method, route template, status, and duration;
they omit query strings, headers, cookies, and request/response bodies.

## Production deployment

Production uses the separate [`docker-compose.prod.yml`](docker-compose.prod.yml) topology: Caddy is the only service
with host ports, the Server runs as UID/GID 10001 with a read-only root filesystem, and PostgreSQL has no host port.
Images are pinned to explicit release tags through `.env.production`; production must not deploy `latest` or build an
arbitrary checkout on the VPS.

The production bundle includes file-mounted secrets, bounded Docker log rotation, persistent PostgreSQL/Caddy
volumes, encrypted checksummed backups, 30-daily/12-monthly retention, guarded restore tooling, a systemd backup
timer, and schema-aware update/rollback guidance. See
[`docs/PRODUCTION-DEPLOYMENT.md`](docs/PRODUCTION-DEPLOYMENT.md) before operating a public instance.

For a self-hosted release, build and publish the two immutable `0.3.0` images from an approved clean commit, copy
`docker-compose.prod.yml`, `.env.production.example`, and `ops/` to the host, create an untracked `.env.production`,
generate the file-mounted secrets, and start the exact image tags through Docker Compose. Caddy obtains and renews the
public certificate; PostgreSQL and the Ktor application remain on internal Docker networks. Run the production
readiness validation and configure the documented encrypted off-site backup schedule before inviting users. The full
commands, permissions, health checks, rollback procedure, and restore drill are in the deployment runbook.

In EVE Static Map Planner 1.9.0, enter the Shared Marker origin, for example `https://markers.example.com`, under Shared Map
preferences and exchange a Workspace invite. `/api/v1` is appended by the client. No particular hosted domain is
required: operators may use any correctly configured HTTPS origin.

For the Web client, also set `SHARED_MAP_ALLOWED_ORIGINS` to the exact HTTPS origin that serves the Web map,
then recreate the server container. Web and API must both be HTTPS in production; browsers will block an HTTPS Web
page from calling a remote HTTP API. Ktor remains behind the existing Caddy TLS reverse proxy. The current protocol
uses 30-second REST polling and has no WebSocket or SSE endpoint.
