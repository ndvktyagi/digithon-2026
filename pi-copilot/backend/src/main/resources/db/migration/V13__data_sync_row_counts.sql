-- Data Sync reports verification per row (a row is verified when all its PI values decrypt correctly)
ALTER TABLE data_sync_run ADD COLUMN rows_verified BIGINT NOT NULL DEFAULT 0;
ALTER TABLE data_sync_run ADD COLUMN rows_failed BIGINT NOT NULL DEFAULT 0;
