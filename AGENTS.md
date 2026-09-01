# EVE Shared Map Server development guidance

- Treat security and protocol compatibility as release requirements, not optional hardening.
- Never commit or log credentials, secret-file contents, authorization headers, request bodies, invites, or tokens.
- Change the PostgreSQL schema only through versioned, forward-only Flyway migrations. Never run automatic `clean` or `repair` at startup.
- Preserve the frozen Shared Map Protocol v1 contract. A protocol change requires an explicit design checkpoint.
- Stay inside the currently authorized phase. Phase 1 has no auth, users, Workspaces, members, invites, tokens, Shared Marker CRUD, audit business events, idempotency, or SDE allowlist.
- Add or update tests for every behavior change and run `./gradlew --no-daemon --console=plain clean check` before committing.
- Do not edit a production server or production database manually. Deployment changes require reviewed, reproducible artifacts and documented rollback.
