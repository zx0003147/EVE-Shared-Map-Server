# EVE Shared Map Server 0.3.0

This release adds bounded Workspace Route Handoffs while preserving Shared Map Protocol v1 and the existing Shared
Marker API.

## Included

- Adds Flyway `V4__route_handoffs.sql` for the independent Workspace Route Handoff resource.
- Allows Viewer, Editor, and Admin members to read current Route Handoffs.
- Allows Editor and Admin members to publish Normal or Capital route intent plus an exact resolved snapshot.
- Advertises support through the Protocol v1 `route-handoffs` feature flag; the protocol major remains `1`.
- Expires Route Handoffs after seven days and bounds every Workspace to its newest 20 entries.
- Preserves existing Shared Marker routes, authentication, authorization, idempotency, audit, CORS, and clients.
- Updates production and self-hosted validation for Server 0.3.0, Planner Web 1.9.0, and Flyway schema 4.

## Compatibility

- Self-hosted distribution: `1.1.0`
- Compatible Web Map: `1.9.0`
- Server: `0.3.0`
- Shared Map protocol: `1`
- Required feature for Route Handoff: `route-handoffs`
- Flyway schema: `4`
- Packaged universe allowlist: `sde-3466501`

Existing Shared Marker clients remain compatible. Clients that do not recognize `route-handoffs` continue using
Protocol v1 without the optional resource.
