-- Scans separate plain PI from columns that already hold ciphertext (e.g. the "<column>_enc" twins
-- PI Co-Pilot adds); only plain PI can be selected for encryption.
ALTER TABLE pi_finding ADD COLUMN encryption_state VARCHAR(20) NOT NULL DEFAULT 'PLAIN';
ALTER TABLE pi_finding ADD COLUMN encrypted_column VARCHAR(200);

-- Reclassify findings from earlier scans: an "<x>_enc" column next to a found "<x>" column is its encrypted twin
UPDATE pi_finding enc
SET encryption_state = 'ENCRYPTED', approved = FALSE, suggested_action = 'NONE',
    reasoning = 'Encrypted copy of "' || left(enc.column_name, length(enc.column_name) - 4) || '"; it holds ciphertext, not plain PI.'
WHERE enc.column_name LIKE '%\_enc'
  AND EXISTS (SELECT 1 FROM pi_finding p
              WHERE p.scan_job_id = enc.scan_job_id
                AND p.database_connector_id IS NOT DISTINCT FROM enc.database_connector_id
                AND p.db_schema = enc.db_schema AND p.table_name = enc.table_name
                AND p.column_name = left(enc.column_name, length(enc.column_name) - 4));

UPDATE pi_finding p
SET encrypted_column = p.column_name || '_enc'
WHERE p.encryption_state = 'PLAIN'
  AND EXISTS (SELECT 1 FROM pi_finding e
              WHERE e.encryption_state = 'ENCRYPTED' AND e.scan_job_id = p.scan_job_id
                AND e.database_connector_id IS NOT DISTINCT FROM p.database_connector_id
                AND e.db_schema = p.db_schema AND e.table_name = p.table_name
                AND e.column_name = p.column_name || '_enc');
