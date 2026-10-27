-- Finalize starts from the release outcome: SUCCEEDED (clean up, merge, drop the plain columns) or FAILED
-- (close the release pull request, optionally drop the encrypted columns).
ALTER TABLE finalize_run ADD COLUMN release_outcome VARCHAR(20);
UPDATE finalize_run SET release_outcome = 'SUCCEEDED' WHERE code_status <> 'PENDING';
