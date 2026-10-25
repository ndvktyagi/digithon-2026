-- Folders selected for scanning (newline-separated); NULL scans the whole repository
ALTER TABLE repository_connector ADD COLUMN include_paths TEXT;
