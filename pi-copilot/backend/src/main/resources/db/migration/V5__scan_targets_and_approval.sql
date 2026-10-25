-- Scans run across one or more database connectors in a user-chosen order,
-- and a completed scan is approved table-by-table before implementation.

ALTER TABLE scan_job ADD COLUMN name VARCHAR(150);
ALTER TABLE scan_job ADD COLUMN tables_with_pi INTEGER;
ALTER TABLE scan_job ADD COLUMN approval_state VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE scan_job ADD COLUMN approved_by VARCHAR(100);
ALTER TABLE scan_job ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE scan_target (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    scan_job_id UUID NOT NULL REFERENCES scan_job(id) ON DELETE CASCADE,
    -- Kept nullable so a connector can be deleted without losing scan history
    connector_id UUID REFERENCES database_connector(id) ON DELETE SET NULL,
    connector_type VARCHAR(30) NOT NULL,
    label VARCHAR(300) NOT NULL,
    execution_order INTEGER NOT NULL,
    schemas VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    tables_scanned INTEGER NOT NULL DEFAULT 0,
    columns_scanned INTEGER NOT NULL DEFAULT 0,
    findings_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(2000),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
CREATE INDEX idx_scan_target_job ON scan_target(scan_job_id, execution_order);

ALTER TABLE pi_finding ADD COLUMN database_connector_id UUID REFERENCES database_connector(id) ON DELETE SET NULL;
ALTER TABLE pi_finding ADD COLUMN approved BOOLEAN NOT NULL DEFAULT FALSE;
