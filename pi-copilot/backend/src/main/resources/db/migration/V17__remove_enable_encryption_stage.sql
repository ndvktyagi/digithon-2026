-- The Enable Encryption stage is removed: the released branch reads encrypted data directly and a
-- rollback is a redeploy of the base branch, so there is no runtime switch any more.
UPDATE workspace SET lifecycle_stage = 'FINALIZE' WHERE lifecycle_stage = 'ENABLE_ENCRYPTION';
