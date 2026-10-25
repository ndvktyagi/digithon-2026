-- Data Sync: encrypts the existing plain PI values into the <column>_enc columns added by the
-- approved implementation, then verifies every encrypted value decrypts back to the plain one.
CREATE TABLE data_sync_run (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    implementation_run_id UUID NOT NULL REFERENCES implementation_run(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    phase VARCHAR(20),
    rows_encrypted BIGINT NOT NULL DEFAULT 0,
    values_encrypted BIGINT NOT NULL DEFAULT 0,
    values_verified BIGINT NOT NULL DEFAULT 0,
    verify_failures BIGINT NOT NULL DEFAULT 0,
    tables_json TEXT,
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
CREATE INDEX idx_data_sync_run_ws ON data_sync_run(workspace_id, created_at DESC);
