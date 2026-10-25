-- Comments a reviewer attached to individual diff lines when reviewing an implementation round
CREATE TABLE review_line_comment (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id UUID NOT NULL REFERENCES implementation_run(id) ON DELETE CASCADE,
    path VARCHAR(500) NOT NULL,
    line_number INTEGER NOT NULL,
    -- NEW: line in the changed file; OLD: line in the original (removed lines)
    side VARCHAR(3) NOT NULL,
    line_text TEXT,
    body TEXT NOT NULL,
    author VARCHAR(100),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
CREATE INDEX idx_review_line_comment_run ON review_line_comment(run_id);
