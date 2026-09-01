# EVE Shared Map Server

EVE Shared Map Server is the collaboration backend for EVE Static Map Planner. Phase 2 provides the identity,
Workspace, membership, invite, device-token, authentication, authorization, audit, and idempotency foundation.

Shared Marker storage and CRUD are deliberately not implemented yet. The Map desktop repository is not a dependency
of this server and remains usable without it.

## Prerequisites

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
| `SHARED_MAP_DATABASE_USER` | required | PostgreSQL application user |
| `SHARED_MAP_DATABASE_PASSWORD_FILE` | required | Readable non-empty database secret file |
| `SHARED_MAP_TOKEN_PEPPER_FILE` | required | Readable non-empty HMAC pepper secret file |
| `SHARED_MAP_ENVIRONMENT` | `development` | Environment label |
| `SHARED_MAP_LOG_LEVEL` | `INFO` | TRACE, DEBUG, INFO, WARN, or ERROR |

Secrets are not accepted as ordinary environment values, command-line arguments, or Gradle properties.

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

- `VIEWER`: authenticate and read self/Workspace data.
- `EDITOR`: Viewer capabilities plus future Shared Marker writes; Marker APIs do not exist in Phase 2.
- `ADMIN`: Editor capabilities plus member, invite, role, membership, and device administration.

The final active Admin cannot be downgraded or removed.

## Implemented Phase 2 endpoints

- `GET /health`
- `GET /api/v1/meta`
- `POST /api/v1/auth/exchange-invite`
- `GET /api/v1/me`
- `GET /api/v1/me/devices`
- `DELETE /api/v1/me/devices/{tokenId}`
- `GET /api/v1/workspaces`
- `GET /api/v1/workspaces/{workspaceId}`
- `GET|POST /api/v1/workspaces/{workspaceId}/members`
- `PATCH|DELETE /api/v1/workspaces/{workspaceId}/members/{memberId}`
- `GET /api/v1/workspaces/{workspaceId}/members/{memberId}/devices`
- `DELETE /api/v1/workspaces/{workspaceId}/members/{memberId}/devices/{tokenId}`
- `GET /api/v1/workspaces/{workspaceId}/invites`
- `POST /api/v1/workspaces/{workspaceId}/members/{memberId}/invites`
- `DELETE /api/v1/workspaces/{workspaceId}/invites/{inviteId}`

All authenticated mutations require a canonical UUID `Idempotency-Key`, except invite exchange. No Marker endpoint is
implemented or advertised. `/api/v1/meta.features` is `members`, `invites`, and `device-revocation` only.

## Run the server

```powershell
.\gradlew.bat run
```

`/health` reports database readiness. Database failure returns HTTP 503 without connection details or exception text.

## Build and test

```powershell
.\gradlew.bat --no-daemon --console=plain clean build
```

The suite includes unit, Ktor HTTP, real PostgreSQL 18.6 Testcontainers, migration upgrade, concurrency, authorization,
security, secret-storage, and end-to-end authentication tests. A Phase 2 acceptance run requires Docker and must have
zero skipped PostgreSQL tests.

## Docker workflow

```powershell
docker build -t eve-shared-map-server:phase2 .
docker compose -f docker-compose.dev.yml --profile server up --build -d
docker compose -f docker-compose.dev.yml --profile server down
```

The development database binds only to `127.0.0.1:54329`. The server binds only to `127.0.0.1:8080` in the dev
topology, runs as numeric non-root user `10001`, and supports a read-only root filesystem with a bounded `/tmp`
tmpfs. PostgreSQL state remains in the named dev volume unless the operator explicitly adds `-v` when stopping.

## Migrations, audit, and logging

Flyway runs synchronously before Ktor becomes ready. `V1__skeleton.sql` is the Phase 1 baseline;
`V2__workspace_authentication.sql` creates only Phase 2 identity/auth tables. Flyway clean and automatic repair remain
disabled.

Audit events are append-only and contain event-time actor identity plus safe metadata. They never contain bearer
tokens, invite secrets, hashes, Authorization headers, database passwords, or request bodies.

Logs are JSON Lines on stdout. Access logs contain bounded request ID, method, route template, status, and duration;
they omit query strings, headers, cookies, and request/response bodies.
