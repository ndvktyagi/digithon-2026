-- How a scan was started: by a user, by completing the Connect stage, or by the monitoring schedule
ALTER TABLE scan_job ADD COLUMN trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
CREATE INDEX idx_scan_job_trigger ON scan_job(workspace_id, trigger_type, created_at DESC);

-- Recurring PI re-scan per workspace, normally enabled after Finalize
CREATE TABLE monitoring_schedule (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL UNIQUE REFERENCES workspace(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    interval_value INTEGER NOT NULL DEFAULT 1,
    interval_unit VARCHAR(10) NOT NULL DEFAULT 'WEEKS',
    value_pattern_scan BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at TIMESTAMP WITH TIME ZONE,
    last_run_at TIMESTAMP WITH TIME ZONE,
    last_scan_id UUID REFERENCES scan_job(id) ON DELETE SET NULL,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
CREATE INDEX idx_monitoring_due ON monitoring_schedule(enabled, next_run_at);
