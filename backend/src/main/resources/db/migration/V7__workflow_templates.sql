CREATE TABLE workflow_template (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    template_key VARCHAR(40) NOT NULL,
    revision INTEGER NOT NULL CHECK (revision BETWEEN 1 AND 100000),
    name VARCHAR(120) NOT NULL CHECK (BTRIM(name) <> ''),
    scenario VARCHAR(16) NOT NULL CHECK (scenario IN ('CLIENT_A', 'CLIENT_B', 'CUSTOM')),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'array' AND jsonb_array_length(definition) BETWEEN 2 AND 12),
    created_by UUID NOT NULL REFERENCES app_user(id),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (workspace_id, template_key, revision),
    UNIQUE (workspace_id, id)
);

-- Publishing validates a complete immutable revision; no implicit latest-version pointer.
ALTER TABLE project ADD COLUMN workflow_template_id UUID;
ALTER TABLE project ADD CONSTRAINT fk_project_workflow_template
    FOREIGN KEY (workspace_id, workflow_template_id) REFERENCES workflow_template(workspace_id, id);
CREATE INDEX ix_project_workflow_template ON project(workspace_id, workflow_template_id);
