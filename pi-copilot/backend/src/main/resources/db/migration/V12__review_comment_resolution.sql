-- Review comments are marked resolved when the next improvement round addresses them
ALTER TABLE review_line_comment ADD COLUMN resolved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE review_line_comment ADD COLUMN resolved_in_round INTEGER;
ALTER TABLE review_line_comment ADD COLUMN resolution_note TEXT;
ALTER TABLE review_line_comment ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;
