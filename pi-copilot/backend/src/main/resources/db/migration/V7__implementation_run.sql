-- One attempt at the Encryption Implementation stage: add encrypted columns to the target
-- databases and push code changes for them to a new branch.
CREATE TABLE implementation_run (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    scan_job_id UUID NOT NULL REFERENCES scan_job(id),
    provider_config_id UUID REFERENCES encryption_provider_config(id) ON DELETE SET NULL,
    repository_connector_id UUID REFERENCES repository_connector(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    db_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    code_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    repository VARCHAR(300),
    base_branch VARCHAR(150),
    branch_name VARCHAR(200),
    commit_sha VARCHAR(64),
    compare_url VARCHAR(500),
    ddl_script TEXT,
    columns_json TEXT,
    changed_files TEXT,
    log TEXT,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(2000),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
CREATE INDEX idx_implementation_run_ws ON implementation_run(workspace_id, created_at DESC);
