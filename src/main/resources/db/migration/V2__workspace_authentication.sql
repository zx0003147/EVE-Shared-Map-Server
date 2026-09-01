CREATE TABLE users (
    user_id uuid PRIMARY KEY DEFAULT uuidv7(),
    display_name varchar(80) NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (char_length(display_name) BETWEEN 1 AND 80),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK ((status = 'ACTIVE' AND revoked_at IS NULL) OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
);

CREATE INDEX users_status_idx ON users (status);

CREATE TABLE workspaces (
    workspace_id uuid PRIMARY KEY DEFAULT uuidv7(),
    name varchar(80) NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (char_length(name) BETWEEN 1 AND 80),
    CHECK (updated_at >= created_at)
);

CREATE TABLE workspace_members (
    member_id uuid PRIMARY KEY DEFAULT uuidv7(),
    workspace_id uuid NOT NULL REFERENCES workspaces(workspace_id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    role text NOT NULL CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    revoked_at timestamptz,
    UNIQUE (workspace_id, user_id),
    CHECK (updated_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX workspace_members_active_role_idx
    ON workspace_members (workspace_id, revoked_at, role);

CREATE TABLE invites (
    invite_id uuid PRIMARY KEY DEFAULT uuidv7(),
    member_id uuid NOT NULL REFERENCES workspace_members(member_id) ON DELETE RESTRICT,
    secret_hash bytea NOT NULL UNIQUE,
    secret_prefix varchar(16) NOT NULL,
    created_by_member_id uuid NOT NULL REFERENCES workspace_members(member_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    CHECK (char_length(secret_prefix) BETWEEN 8 AND 16),
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR used_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX invites_member_created_idx ON invites (member_id, created_at DESC);
CREATE INDEX invites_expires_idx ON invites (expires_at);
CREATE INDEX invites_prefix_idx ON invites (secret_prefix);

CREATE TABLE access_tokens (
    token_id uuid PRIMARY KEY DEFAULT uuidv7(),
    member_id uuid NOT NULL REFERENCES workspace_members(member_id) ON DELETE RESTRICT,
    token_hash bytea NOT NULL UNIQUE,
    token_prefix varchar(16) NOT NULL,
    device_name varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz,
    revoked_by_user_id uuid REFERENCES users(user_id) ON DELETE RESTRICT,
    CHECK (char_length(token_prefix) BETWEEN 8 AND 16),
    CHECK (char_length(device_name) BETWEEN 1 AND 80),
    CHECK (expires_at > created_at),
    CHECK (last_used_at IS NULL OR last_used_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX access_tokens_member_active_idx ON access_tokens (member_id, revoked_at);
CREATE INDEX access_tokens_expires_idx ON access_tokens (expires_at);
CREATE INDEX access_tokens_prefix_idx ON access_tokens (token_prefix);

CREATE TABLE audit_events (
    event_id uuid PRIMARY KEY DEFAULT uuidv7(),
    workspace_id uuid NOT NULL REFERENCES workspaces(workspace_id) ON DELETE RESTRICT,
    actor_user_id uuid REFERENCES users(user_id) ON DELETE RESTRICT,
    actor_display_name varchar(80),
    action text NOT NULL,
    target_type text NOT NULL CHECK (target_type IN ('WORKSPACE', 'MEMBER', 'INVITE', 'DEVICE', 'MARKER')),
    target_id uuid,
    system_id integer,
    timestamp timestamptz NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX audit_events_workspace_timestamp_idx ON audit_events (workspace_id, timestamp DESC);
CREATE INDEX audit_events_actor_timestamp_idx ON audit_events (actor_user_id, timestamp DESC);
CREATE INDEX audit_events_target_idx ON audit_events (target_type, target_id);

CREATE FUNCTION reject_audit_event_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events are append-only';
END;
$$;

CREATE TRIGGER audit_events_append_only
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TABLE idempotency_records (
    token_id uuid NOT NULL REFERENCES access_tokens(token_id) ON DELETE RESTRICT,
    idempotency_key uuid NOT NULL,
    request_fingerprint bytea NOT NULL,
    state text NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status integer,
    response_body jsonb,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (token_id, idempotency_key),
    CHECK (expires_at > created_at),
    CHECK (
        (state = 'IN_PROGRESS' AND response_status IS NULL AND response_body IS NULL)
        OR
        (state = 'COMPLETED' AND response_status IS NOT NULL)
    )
);

CREATE INDEX idempotency_records_expires_idx ON idempotency_records (expires_at);
