-- Final Review approval merges the round's branch into the connector's base branch
ALTER TABLE implementation_run ADD COLUMN merge_status VARCHAR(20);
ALTER TABLE implementation_run ADD COLUMN merge_sha VARCHAR(64);
ALTER TABLE implementation_run ADD COLUMN merge_message TEXT;
ALTER TABLE implementation_run ADD COLUMN merged_at TIMESTAMP WITH TIME ZONE;
