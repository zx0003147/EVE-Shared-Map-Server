# EVE Shared Map Server 0.3.1

This hotfix release completes Route Handoff deletion and tightens active-membership lifecycle behavior while keeping
the existing database and Shared Map protocol fully compatible.

## Fixed

- Adds the Protocol v1 `DELETE /workspaces/{workspaceId}/route-handoffs/{routeHandoffId}` endpoint.
- Allows a Route Handoff publisher to delete their own handoff and an Admin to delete any handoff in the Workspace;
  Viewers and non-publisher Editors remain denied without disclosing cross-Workspace or missing resources.
- Makes deletion idempotent and records a safe audit event without exposing route or credential secrets.
- Filters revoked Workspace memberships from active member management and preserves revoked Device Access Token
  rejection, device revocation, authorization isolation, and the last-Admin protection.
- Resolves the installed `eve-map` script through its real symlink target so `sudo eve-map version` and
  `sudo eve-map status` work from `/usr/local/sbin/eve-map`.

## Compatibility

- Safe in-place upgrade from Server `0.3.0` and self-hosted distribution `1.1.0`.
- Self-hosted distribution: `1.1.1`.
- Compatible Planner Web: `1.9.1`.
- Shared Map protocol remains version `1`.
- Flyway remains schema `4`; this release adds no migration and does not execute a new database migration.
- Packaged universe allowlist remains `sde-3466501`.
