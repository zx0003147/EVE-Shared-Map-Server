# EVE Shared Map Server 0.1.0

The first production release provides the optional collaboration backend for EVE Static Map Planner.

## Included

- Workspace identity and isolated membership model
- single-use invites and membership-scoped device tokens
- Viewer, Editor, and Admin authorization
- complete Shared Marker snapshot and CRUD API with optimistic conflict protection
- mutation idempotency, rate limiting, and append-only audit events
- PostgreSQL 18.6 persistence with forward-only Flyway migrations
- compact SDE solar-system allowlist and protocol metadata
- non-root, read-only Server container
- Caddy automatic HTTPS production topology
- encrypted, checksummed daily/monthly backups and guarded restore tooling

## Security and privacy

Credentials are stored only as peppered HMAC digests and are never logged. Production secrets are file-mounted. The
Token HMAC pepper is separate disaster-recovery state and must be preserved with the database. Shared Marker notes
are readable by the server and database administrators; this release is not end-to-end encrypted.

## Compatibility

- Shared Map protocol: 1
- Supported Map release target: 1.2.0 after production acceptance
- Shared Markers remain optional; the Map's local-first features work without this server
- Shared Markers are not exposed to AI/MCP in this release
