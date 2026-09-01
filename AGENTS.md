# EVE Shared Map Server development guidance

- Treat security and protocol compatibility as release requirements, not optional hardening.
- Never commit or log credentials, secret-file contents, authorization headers, request bodies, invites, or tokens.
- Change the PostgreSQL schema only through versioned, forward-only Flyway migrations. Never run automatic `clean` or `repair` at startup.
- Preserve the frozen Shared Map Protocol v1 contract. A protocol change requires an explicit design checkpoint.
- Stay inside the currently authorized phase. Phase 3 includes the independent Shared Marker server domain, compact
  SDE allowlist, Marker CRUD, optimistic locking, audit, and ordinary idempotency replay. It still has no Map client,
  UI, DPAPI, polling, WebSocket/SSE, Shared Wormholes/Routes, AI/MCP access, or production deployment.
- Add or update tests for every behavior change and run `./gradlew --no-daemon --console=plain clean check` before committing.
- Do not edit a production server or production database manually. Deployment changes require reviewed, reproducible artifacts and documented rollback.
