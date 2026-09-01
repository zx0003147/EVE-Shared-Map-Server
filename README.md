# EVE Shared Map Server

**EVE Shared Map Server is the collaboration backend for EVE Static Map Planner. It will provide authenticated
workspaces and shared strategic map data for authorized Coord/FC users.**

**This repository contains only the server. It is developed and tested locally first, then deployed as a separate
HTTPS service; the EVE Static Map Planner desktop application remains usable without it.**

Phase 1 is intentionally only an infrastructure skeleton. It starts Ktor after PostgreSQL connectivity and Flyway
validation succeed, and exposes `GET /health` plus the empty-feature protocol skeleton at `GET /api/v1/meta`. It does
not contain authentication, Workspace, member, invite, device-token, Shared Marker, audit, idempotency, SDE
allowlist, or Map-client behavior.

## Prerequisites

- JDK 25 LTS (the project toolchain is Java 25)
- Docker Desktop or Docker Engine with Docker Compose, for PostgreSQL and integration tests
- PowerShell on Windows, or a POSIX shell on Linux/macOS

The repository includes Gradle Wrapper 9.7.0; a system Gradle installation is not required.

## Local configuration

Copy the example environment file and create a password secret file. Do not put a real password in a tracked file.

```powershell
Copy-Item .env.example .env
New-Item -ItemType Directory -Force .secrets
Set-Content -NoNewline .secrets/dev-db-password.txt 'choose-a-local-development-password'
```

`.env` and `.secrets/` are ignored by Git. Runtime configuration is validated before the listener opens:

| Variable | Required/default | Purpose |
| --- | --- | --- |
| `SHARED_MAP_BIND_HOST` | `0.0.0.0` | Listener bind address |
| `SHARED_MAP_PORT` | `8080` | Listener port, 1–65535 |
| `SHARED_MAP_DATABASE_URL` | required | PostgreSQL JDBC URL |
| `SHARED_MAP_DATABASE_USER` | required | PostgreSQL application user |
| `SHARED_MAP_DATABASE_PASSWORD_FILE` | required | Readable non-empty mounted secret file |
| `SHARED_MAP_TOKEN_PEPPER_FILE` | optional/reserved | Phase 2 boundary; validated only when set |
| `SHARED_MAP_ENVIRONMENT` | `development` | Environment label |
| `SHARED_MAP_LOG_LEVEL` | `INFO` | TRACE, DEBUG, INFO, WARN, or ERROR |

Secrets are not accepted as Gradle properties and are never intentionally logged.

## Start PostgreSQL and run the server

Start only PostgreSQL:

```powershell
docker compose -f docker-compose.dev.yml up -d postgres
```

Load local runtime settings and run Ktor:

```powershell
$env:SHARED_MAP_DATABASE_URL = 'jdbc:postgresql://localhost:54329/eve_shared_map'
$env:SHARED_MAP_DATABASE_USER = 'eve_shared_map'
$env:SHARED_MAP_DATABASE_PASSWORD_FILE = (Resolve-Path .secrets/dev-db-password.txt).Path
$env:SHARED_MAP_ENVIRONMENT = 'development'
$env:SHARED_MAP_LOG_LEVEL = 'INFO'
.\gradlew.bat run
```

The healthy response is shaped as follows; `serverTime` is the current UTC instant:

```json
{
  "status": "ok",
  "serverVersion": "0.1.0-SNAPSHOT",
  "serverTime": "2026-09-01T11:32:18Z",
  "checks": {"database": "ok"}
}
```

If PostgreSQL becomes unavailable, `/health` returns HTTP 503 with `status` and `checks.database` set to
`unavailable`, without connection details or exception text.

The Phase 1 metadata endpoint advertises Protocol v1 compatibility but no implemented features:

```json
{
  "serverVersion": "0.1.0-SNAPSHOT",
  "protocolVersion": 1,
  "minimumClientProtocolVersion": 1,
  "maximumClientProtocolVersion": 1,
  "features": []
}
```

## Build and test

```powershell
.\gradlew.bat --no-daemon --console=plain clean check
```

Unit and Ktor tests always run. PostgreSQL integration tests use Testcontainers and run automatically when Docker is
available; they are reported as skipped on a machine without a reachable Docker daemon.

## Docker workflow

Build the production-oriented image:

```powershell
docker build -t eve-shared-map-server:phase1 .
```

Start PostgreSQL and the optional server profile from the built context:

```powershell
docker compose -f docker-compose.dev.yml --profile server up --build -d
```

Stop the development environment while retaining its PostgreSQL volume:

```powershell
docker compose -f docker-compose.dev.yml --profile server down
```

Delete the disposable development volume as well (this permanently removes local development database data):

```powershell
docker compose -f docker-compose.dev.yml --profile server down -v
```

The development database binds only to `127.0.0.1:54329`. This Compose file is not a production deployment and
contains no Caddy or VPS configuration.

## Database migrations

Flyway runs synchronously before Ktor is ready. Phase 1 contains only `V1__skeleton.sql`, whose entire operation is
`SELECT 1;`. It creates no business tables. Flyway clean and automatic repair are disabled; future schema changes
must be versioned, forward-only migrations.

## Logging and request IDs

Logs are JSON Lines written to stdout by Logback's built-in JSON encoder. Every request receives a bounded,
validated `X-Request-Id`; a safe caller value is adopted and an unsafe or missing value is replaced with a UUID.
Access logs contain only request ID, method, route template, status, and duration. They do not include query strings,
headers, cookies, request/response bodies, credentials, or secret-file contents.
