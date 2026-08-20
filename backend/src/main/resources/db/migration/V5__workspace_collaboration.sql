ALTER TABLE task
    ADD COLUMN assignee_id UUID REFERENCES app_user(id);

CREATE INDEX ix_task_assignee ON task (workspace_id, assignee_id, updated_at DESC);

CREATE TABLE workspace_invitation (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    email VARCHAR(320) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    invited_by UUID NOT NULL REFERENCES app_user(id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_workspace_pending_invitation
    ON workspace_invitation (workspace_id, LOWER(email))
    WHERE status = 'PENDING';

CREATE TABLE task_comment (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    task_id UUID NOT NULL,
    author_id UUID NOT NULL REFERENCES app_user(id),
    body VARCHAR(2000) NOT NULL CHECK (BTRIM(body) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workspace_id, task_id) REFERENCES task(workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_task_comment_time
    ON task_comment (workspace_id, task_id, created_at, id);

INSERT INTO app_user (id, email, display_name)
VALUES ('00000000-0000-0000-0000-000000000002', 'member@v-core.local', 'Mai Tran');

INSERT INTO workspace_membership (workspace_id, user_id, role)
VALUES (
    '00000000-0000-0000-0000-000000000100',
    '00000000-0000-0000-0000-000000000002',
    'MEMBER'
);
