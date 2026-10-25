-- Final Review: each implementation run is one review round. "Improve" creates the next round,
-- which resolves the reviewer's comment and pushes to the same branch / pull request.
ALTER TABLE implementation_run ADD COLUMN round_number INTEGER NOT NULL DEFAULT 1;
ALTER TABLE implementation_run ADD COLUMN parent_run_id UUID REFERENCES implementation_run(id);
ALTER TABLE implementation_run ADD COLUMN improvement_request TEXT;
ALTER TABLE implementation_run ADD COLUMN ai_summary TEXT;
ALTER TABLE implementation_run ADD COLUMN review_status VARCHAR(20);
ALTER TABLE implementation_run ADD COLUMN review_comment TEXT;
ALTER TABLE implementation_run ADD COLUMN reviewed_by VARCHAR(100);
ALTER TABLE implementation_run ADD COLUMN reviewed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE implementation_run ADD COLUMN pr_number INTEGER;
ALTER TABLE implementation_run ADD COLUMN pr_url VARCHAR(500);

-- Every file a round changed: the original on the base branch, the content before this round
-- (the previous round's result) and the content this round pushed.
CREATE TABLE implementation_file_change (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id UUID NOT NULL REFERENCES implementation_run(id) ON DELETE CASCADE,
    path VARCHAR(500) NOT NULL,
    change_kind VARCHAR(10) NOT NULL,
    base_content TEXT,
    previous_content TEXT,
    new_content TEXT NOT NULL,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
CREATE INDEX idx_impl_file_change_run ON implementation_file_change(run_id);
