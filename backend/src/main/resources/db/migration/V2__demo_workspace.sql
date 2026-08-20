INSERT INTO app_user (id, email, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'owner@v-core.local', 'Demo Owner');

INSERT INTO workspace (id, name, slug, created_by)
VALUES (
    '00000000-0000-0000-0000-000000000100',
    'V-Core Product Lab',
    'v-core-product-lab',
    '00000000-0000-0000-0000-000000000001'
);

INSERT INTO workspace_membership (workspace_id, user_id, role)
VALUES (
    '00000000-0000-0000-0000-000000000100',
    '00000000-0000-0000-0000-000000000001',
    'OWNER'
);

INSERT INTO project (id, workspace_id, name, project_key, description)
VALUES (
    '00000000-0000-0000-0000-000000000200',
    '00000000-0000-0000-0000-000000000100',
    'Sprint 24 Command Center',
    'VC',
    'Portfolio workspace demonstrating conflict-safe delivery workflows.'
);

INSERT INTO sprint (id, workspace_id, project_id, name, goal, status)
VALUES (
    '00000000-0000-0000-0000-000000000300',
    '00000000-0000-0000-0000-000000000100',
    '00000000-0000-0000-0000-000000000200',
    'Sprint 24',
    'Ship a reliable collaboration workflow',
    'ACTIVE'
);

INSERT INTO workflow_column (id, workspace_id, project_id, name, category, position, wip_limit)
VALUES
    ('00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000200', 'Backlog', 'BACKLOG', 0, NULL),
    ('00000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000200', 'In progress', 'IN_PROGRESS', 1, 3),
    ('00000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000200', 'Review', 'IN_PROGRESS', 2, 2),
    ('00000000-0000-0000-0000-000000000404', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000200', 'Done', 'DONE', 3, NULL);
