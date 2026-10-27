-- Finalize: removes the plain-text code paths from the protected application (a merged cleanup
-- pull request), then drops the plain-text PI columns from the target databases.
CREATE TABLE finalize_run (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    implementation_run_id UUID NOT NULL REFERENCES implementation_run(id),
    code_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    branch_name VARCHAR(255),
    commit_sha VARCHAR(64),
    pr_number INTEGER,
    pr_url VARCHAR(500),
    merge_status VARCHAR(20),
    merge_sha VARCHAR(64),
    merge_message TEXT,
    changed_files TEXT,
    warnings TEXT,
    columns_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    dropped_columns TEXT,
    dropped_at TIMESTAMP WITH TIME ZONE,
    dropped_by VARCHAR(100),
    log TEXT,
    error_message VARCHAR(2000),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
CREATE INDEX idx_finalize_run_ws ON finalize_run(workspace_id, created_at DESC);
