CREATE TABLE auth_account (
    username VARCHAR(100) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    user_id UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OPERATOR', 'VIEWER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE auth_login_window (
    client_key VARCHAR(128) PRIMARY KEY,
    attempts INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_login_window_expiry ON auth_login_window(expires_at);

CREATE TABLE spring_session (
    primary_id CHAR(36) PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session(session_id);
CREATE INDEX spring_session_ix2 ON spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);

-- A dedicated principal for explicitly enabled read-only public demo access.
INSERT INTO app_user(id,email,display_name) VALUES ('00000000-0000-0000-0000-000000000003','viewer@v-core.local','Demo Viewer');
INSERT INTO workspace_membership(workspace_id,user_id,role) VALUES ('00000000-0000-0000-0000-000000000100','00000000-0000-0000-0000-000000000003','VIEWER');
