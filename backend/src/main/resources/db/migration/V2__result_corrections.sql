-- Admins can correct the result of a confirmed game or annul it (docs/ARCHITECTURE.md#result-corrections).
-- An annulled game keeps its registered result for the record but no longer counts anywhere.
ALTER TABLE game DROP CONSTRAINT ck_game_status;
ALTER TABLE game ADD CONSTRAINT ck_game_status
    CHECK (status IN ('SCHEDULED', 'LIVE', 'PENDING', 'REJECTED', 'FINISHED', 'ABORTED', 'ANNULLED'));
ALTER TABLE game DROP CONSTRAINT ck_game_result_matches_status;
ALTER TABLE game ADD CONSTRAINT ck_game_result_matches_status
    CHECK ((status IN ('FINISHED', 'PENDING', 'REJECTED', 'ANNULLED')) = (result IS NOT NULL));

ALTER TABLE game ADD COLUMN corrected_by      BIGINT REFERENCES app_user (id);
ALTER TABLE game ADD COLUMN corrected_at      TIMESTAMPTZ;
ALTER TABLE game ADD COLUMN correction_reason VARCHAR(300);
ALTER TABLE game ADD CONSTRAINT ck_game_correction
    CHECK ((corrected_at IS NULL) = (corrected_by IS NULL) AND (corrected_at IS NULL) = (correction_reason IS NULL));
CREATE INDEX ix_game_corrected_by ON game (corrected_by);
