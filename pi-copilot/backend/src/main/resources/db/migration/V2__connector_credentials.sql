-- GitHub connector: API endpoint (github.com or Enterprise), selected branch, encrypted token
ALTER TABLE repository_connector
    ADD COLUMN api_base_url VARCHAR(300),
    ADD COLUMN branch VARCHAR(150),
    ADD COLUMN encrypted_token TEXT;

-- Database connector: credentials entered directly (password encrypted at rest)
ALTER TABLE database_connector
    ADD COLUMN username VARCHAR(150),
    ADD COLUMN encrypted_password TEXT,
    ALTER COLUMN username_secret_reference DROP NOT NULL,
    ALTER COLUMN password_secret_reference DROP NOT NULL;
