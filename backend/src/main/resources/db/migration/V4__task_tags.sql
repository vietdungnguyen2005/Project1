CREATE TABLE tag (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL CHECK (BTRIM(name) <> ''),
    color VARCHAR(16),
    UNIQUE (workspace_id, id)
);

CREATE UNIQUE INDEX ux_tag_workspace_name_lower ON tag (workspace_id, LOWER(name));

CREATE TABLE task_tag (
    workspace_id UUID NOT NULL,
    task_id UUID NOT NULL,
    tag_id UUID NOT NULL,
    PRIMARY KEY (workspace_id, task_id, tag_id),
    FOREIGN KEY (workspace_id, task_id) REFERENCES task(workspace_id, id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, tag_id) REFERENCES tag(workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_task_tag_tag ON task_tag (workspace_id, tag_id, task_id);
