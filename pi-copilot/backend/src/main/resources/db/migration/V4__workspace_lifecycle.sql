-- Current lifecycle stage of each workspace (see WorkspaceStage)
ALTER TABLE workspace ADD COLUMN lifecycle_stage VARCHAR(30) NOT NULL DEFAULT 'CONNECT';
ALTER TABLE workspace ADD COLUMN stage_updated_at TIMESTAMP WITH TIME ZONE;
