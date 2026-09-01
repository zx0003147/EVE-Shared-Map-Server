CREATE FUNCTION shared_marker_tags_are_valid(candidate text[]) RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
RETURN
    cardinality(candidate) <= 9
    AND NOT EXISTS (
        SELECT 1
        FROM unnest(candidate) AS marker_tag
        WHERE marker_tag IS NULL
           OR marker_tag !~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    )
    AND cardinality(candidate) = (
        SELECT count(DISTINCT marker_tag)
        FROM unnest(candidate) AS marker_tag
    );

CREATE TABLE shared_markers (
    marker_id uuid PRIMARY KEY DEFAULT uuidv7(),
    workspace_id uuid NOT NULL REFERENCES workspaces(workspace_id) ON DELETE RESTRICT,
    system_id integer NOT NULL CHECK (system_id > 0),
    name varchar(80) NOT NULL,
    color text NOT NULL CHECK (color IN ('RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE', 'PURPLE', 'WHITE')),
    tags text[] NOT NULL DEFAULT ARRAY[]::text[],
    notes varchar(2000),
    created_by_user_id uuid NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    updated_by_user_id uuid NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT shared_markers_workspace_system_unique UNIQUE (workspace_id, system_id),
    CHECK (char_length(name) BETWEEN 1 AND 80),
    CHECK (notes IS NULL OR char_length(notes) <= 2000),
    CHECK (shared_marker_tags_are_valid(tags)),
    CHECK (updated_at >= created_at)
);

CREATE INDEX shared_markers_workspace_updated_idx
    ON shared_markers (workspace_id, updated_at DESC);
