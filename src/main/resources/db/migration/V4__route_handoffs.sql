ALTER TABLE audit_events DROP CONSTRAINT audit_events_target_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_target_type_check
    CHECK (target_type IN ('WORKSPACE', 'MEMBER', 'INVITE', 'DEVICE', 'MARKER', 'ROUTE_HANDOFF'));

CREATE TABLE route_handoffs (
    route_handoff_id uuid PRIMARY KEY DEFAULT uuidv7(),
    workspace_id uuid NOT NULL REFERENCES workspaces(workspace_id) ON DELETE RESTRICT,
    publisher_member_id uuid NOT NULL REFERENCES workspace_members(member_id) ON DELETE RESTRICT,
    publisher_user_id uuid NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    publisher_token_id uuid NOT NULL REFERENCES access_tokens(token_id) ON DELETE RESTRICT,
    route_type text NOT NULL CHECK (route_type IN ('NORMAL', 'CAPITAL')),
    origin_system_id integer NOT NULL CHECK (origin_system_id > 0),
    destination_system_id integer NOT NULL CHECK (destination_system_id > 0),
    intent jsonb NOT NULL,
    resolved_snapshot jsonb NOT NULL,
    map_metadata jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK (jsonb_typeof(intent) = 'object'),
    CHECK (jsonb_typeof(resolved_snapshot) = 'object'),
    CHECK (jsonb_typeof(map_metadata) = 'object'),
    CHECK (expires_at > created_at)
);

CREATE INDEX route_handoffs_workspace_created_idx
    ON route_handoffs (workspace_id, created_at DESC, route_handoff_id DESC);
CREATE INDEX route_handoffs_expires_idx ON route_handoffs (expires_at);
