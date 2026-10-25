-- Review comments can also target a whole file (side FILE, line 0)
ALTER TABLE review_line_comment ALTER COLUMN side TYPE VARCHAR(4);
