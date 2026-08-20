CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_app_user_email_lower ON app_user (LOWER(email));

CREATE TABLE workspace (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(80) NOT NULL UNIQUE,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE workspace_membership (
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX ix_membership_user_workspace ON workspace_membership (user_id, workspace_id);

CREATE TABLE project (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    project_key VARCHAR(12) NOT NULL,
    description TEXT,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (workspace_id, project_key),
    UNIQUE (workspace_id, id)
);

CREATE TABLE sprint (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    goal TEXT,
    starts_on DATE,
    ends_on DATE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workspace_id, project_id) REFERENCES project(workspace_id, id) ON DELETE CASCADE,
    UNIQUE (workspace_id, id),
    CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on)
);

CREATE UNIQUE INDEX ux_sprint_one_active_per_project
    ON sprint (workspace_id, project_id)
    WHERE status = 'ACTIVE';

CREATE TABLE workflow_column (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    category VARCHAR(16) NOT NULL CHECK (category IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'DONE')),
    position INTEGER NOT NULL CHECK (position >= 0),
    wip_limit INTEGER CHECK (wip_limit > 0),
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (workspace_id, project_id) REFERENCES project(workspace_id, id) ON DELETE CASCADE,
    UNIQUE (workspace_id, project_id, position),
    UNIQUE (workspace_id, id)
);

CREATE TABLE task (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    sprint_id UUID,
    column_id UUID NOT NULL,
    task_number BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL CHECK (BTRIM(title) <> ''),
    description TEXT,
    priority VARCHAR(16) NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    story_points INTEGER CHECK (story_points >= 0 AND story_points <= 100),
    position BIGINT NOT NULL CHECK (position >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workspace_id, project_id) REFERENCES project(workspace_id, id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, sprint_id) REFERENCES sprint(workspace_id, id),
    FOREIGN KEY (workspace_id, column_id) REFERENCES workflow_column(workspace_id, id),
    UNIQUE (workspace_id, project_id, task_number),
    UNIQUE (workspace_id, id)
);

CREATE INDEX ix_task_board ON task (workspace_id, project_id, column_id, position, id);
CREATE INDEX ix_task_sprint ON task (workspace_id, sprint_id, updated_at DESC);
CREATE INDEX ix_task_title_search ON task (workspace_id, LOWER(title));

CREATE TABLE activity (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_activity_workspace_time ON activity (workspace_id, occurred_at DESC, id DESC);
CREATE INDEX ix_activity_aggregate ON activity (workspace_id, aggregate_type, aggregate_id, occurred_at DESC);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_outbox_pending ON outbox_event (next_attempt_at, occurred_at)
    WHERE published_at IS NULL;

CREATE TABLE idempotency_record (
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(120) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, idempotency_key),
    CHECK ((response_status IS NULL) = (response_body IS NULL))
);

CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at);
