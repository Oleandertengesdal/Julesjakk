-- The chess clock of a live game, as last reported by the phone running it (docs/ARCHITECTURE.md#chess-clock).
-- The phone keeps time; the server stores the latest snapshot so the TV and game page can count down too.
CREATE TABLE game_clock (
    game_id      BIGINT      PRIMARY KEY REFERENCES game (id) ON DELETE CASCADE,
    initial_ms   INTEGER     NOT NULL,
    increment_ms INTEGER     NOT NULL,
    white_ms     INTEGER     NOT NULL,
    black_ms     INTEGER     NOT NULL,
    running      VARCHAR(5),                 -- whose clock is ticking; NULL when paused, not started or stopped
    sequence     INTEGER     NOT NULL,       -- set by the phone; a snapshot older than the stored one is refused
    updated_by   BIGINT      NOT NULL REFERENCES app_user (id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_game_clock_running CHECK (running IS NULL OR running IN ('WHITE', 'BLACK')),
    CONSTRAINT ck_game_clock_times CHECK (white_ms BETWEEN 0 AND 86400000 AND black_ms BETWEEN 0 AND 86400000),
    CONSTRAINT ck_game_clock_control CHECK (initial_ms > 0 AND increment_ms >= 0),
    CONSTRAINT ck_game_clock_sequence CHECK (sequence >= 0)
);
CREATE INDEX ix_game_clock_updated_by ON game_clock (updated_by);
