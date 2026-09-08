# EVE Shared Map Server 0.2.0

This release completes the public self-hosted distribution for the optional EVE Static Map Planner collaboration
backend.

## Included

- Browser Shared Marker support with exact-origin CORS validation.
- Static hosting topology for the Planner Web client behind Caddy HTTPS.
- Guided Ubuntu 24.04 LTS x86_64 installer with guarded resume.
- `eve-map` management CLI for status, update, restart, logs, diagnostics, backup, version, and Web Pack updates.
- Release-manifest-locked Web and Server updates with checksum validation and rollback controls.
- Public, immutable Server and Ops images published through GHCR.

## Compatibility

- Self-hosted distribution: `1.0.0`
- Compatible Web Map: `1.8.0`
- Server: `0.2.0`
- Shared Map protocol: `1`
- Flyway schema: `3`
- Packaged universe allowlist: `sde-3466501`

## Fresh self-hosted requirements

- Ubuntu 24.04 LTS on x86_64
- A VPS with SSH and `sudo`
- A domain with permission to create two DNS A records

The installer configures Docker, PostgreSQL, Caddy, the verified Web artifact, exact Server images, and initial Admin
bootstrap. Firewall and SSH hardening remain provider-specific operator responsibilities.
