-- Sjakkportal baseline schema.
-- Conventions: BIGINT identity keys, TIMESTAMPTZ everywhere (stored in UTC),
-- a `version` column on every row that is updated concurrently (JPA @Version),
-- and CHECK constraints for every enum so bad data cannot arrive via SQL either.

-- ---------------------------------------------------------------------------
-- Users & authentication
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username             VARCHAR(32)  NOT NULL,
    display_name         VARCHAR(64)  NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    role                 VARCHAR(16)  NOT NULL DEFAULT 'PLAYER',
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Bumped on password change/reset; access tokens carrying an older value are rejected.
    token_version        INTEGER      NOT NULL DEFAULT 0,
    rating               INTEGER      NOT NULL DEFAULT 1200,
    rated_games          INTEGER      NOT NULL DEFAULT 0,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN', 'PLAYER')),
    CONSTRAINT ck_app_user_username CHECK (username ~ '^[a-z0-9_.-]{3,32}$'),
    CONSTRAINT ck_app_user_rating CHECK (rating BETWEEN 100 AND 4000),
    CONSTRAINT ck_app_user_rated_games CHECK (rated_games >= 0)
);
CREATE UNIQUE INDEX ux_app_user_username ON app_user (username);

CREATE TABLE invite_code (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(64) NOT NULL,
    note       VARCHAR(200),
    created_by BIGINT      NOT NULL REFERENCES app_user (id),
    max_uses   INTEGER,                 -- NULL = unlimited
    use_count  INTEGER     NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ,             -- NULL = never expires
    revoked_at TIMESTAMPTZ,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_invite_code_format CHECK (code ~ '^[A-Z0-9-]{6,64}$'),
    CONSTRAINT ck_invite_code_max_uses CHECK (max_uses IS NULL OR max_uses >= 1),
    -- Last line of defence against over-redemption under concurrency.
    CONSTRAINT ck_invite_code_not_overused CHECK (use_count >= 0 AND (max_uses IS NULL OR use_count <= max_uses))
);
CREATE UNIQUE INDEX ux_invite_code_code ON invite_code (code);

CREATE TABLE invite_redemption (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invite_code_id BIGINT      NOT NULL REFERENCES invite_code (id),
    user_id        BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    redeemed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- An account is created by exactly one invite.
    CONSTRAINT ux_invite_redemption_user UNIQUE (user_id)
);
CREATE INDEX ix_invite_redemption_code ON invite_redemption (invite_code_id, redeemed_at);

CREATE TABLE refresh_token (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  CHAR(64)    NOT NULL,   -- SHA-256 hex; the raw token is never stored
    family_id   UUID        NOT NULL,   -- rotation chain, revoked as a whole on reuse
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id, expires_at);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);

CREATE TABLE password_reset_token (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash CHAR(64)    NOT NULL,
    created_by BIGINT      NOT NULL REFERENCES app_user (id),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_password_reset_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_password_reset_token_user ON password_reset_token (user_id, expires_at);

CREATE TABLE audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id   BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    action     VARCHAR(48) NOT NULL,
    target     VARCHAR(100),
    detail     VARCHAR(500),
    trace_id   VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Tournaments
-- ---------------------------------------------------------------------------
CREATE TABLE tournament (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(1000),
    format        VARCHAR(24)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    rated         BOOLEAN      NOT NULL DEFAULT TRUE,
    total_rounds  INTEGER,                 -- NULL for arena
    current_round INTEGER      NOT NULL DEFAULT 0,
    starts_at     TIMESTAMPTZ,
    ends_at       TIMESTAMPTZ,             -- arena window end
    created_by    BIGINT       NOT NULL REFERENCES app_user (id),
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tournament_format CHECK (format IN
        ('ROUND_ROBIN', 'DOUBLE_ROUND_ROBIN', 'SWISS', 'SINGLE_ELIMINATION', 'DOUBLE_ELIMINATION', 'ARENA')),
    CONSTRAINT ck_tournament_status CHECK (status IN ('DRAFT', 'ACTIVE', 'FINISHED', 'CANCELLED')),
    CONSTRAINT ck_tournament_rounds CHECK (total_rounds IS NULL OR total_rounds >= 1),
    CONSTRAINT ck_tournament_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_tournament_arena_window CHECK (format <> 'ARENA' OR status = 'DRAFT'
        OR (starts_at IS NOT NULL AND ends_at IS NOT NULL))
);
CREATE INDEX ix_tournament_status ON tournament (status, starts_at DESC);

CREATE TABLE tournament_participant (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tournament_id BIGINT        NOT NULL REFERENCES tournament (id) ON DELETE CASCADE,
    user_id       BIGINT        NOT NULL REFERENCES app_user (id),
    seed          INTEGER       NOT NULL,
    seed_rating   INTEGER       NOT NULL,
    score         NUMERIC(6, 1) NOT NULL DEFAULT 0,
    streak        INTEGER       NOT NULL DEFAULT 0,   -- arena win streak
    withdrawn     BOOLEAN       NOT NULL DEFAULT FALSE,
    eliminated    BOOLEAN       NOT NULL DEFAULT FALSE,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ux_tournament_participant UNIQUE (tournament_id, user_id),
    CONSTRAINT ux_tournament_participant_seed UNIQUE (tournament_id, seed),
    CONSTRAINT ck_tournament_participant_score CHECK (score >= 0)
);
CREATE INDEX ix_tournament_participant_standings ON tournament_participant (tournament_id, score DESC);
CREATE INDEX ix_tournament_participant_user ON tournament_participant (user_id);

CREATE TABLE tournament_round (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tournament_id BIGINT      NOT NULL REFERENCES tournament (id) ON DELETE CASCADE,
    bracket       VARCHAR(12) NOT NULL DEFAULT 'MAIN',
    round_number  INTEGER     NOT NULL,
    status        VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    CONSTRAINT ux_tournament_round UNIQUE (tournament_id, bracket, round_number),
    CONSTRAINT ck_tournament_round_bracket CHECK (bracket IN ('MAIN', 'WINNERS', 'LOSERS', 'GRAND_FINAL')),
    CONSTRAINT ck_tournament_round_status CHECK (status IN ('PENDING', 'ACTIVE', 'FINISHED')),
    CONSTRAINT ck_tournament_round_number CHECK (round_number >= 1)
);

-- ---------------------------------------------------------------------------
-- Games
-- ---------------------------------------------------------------------------
CREATE TABLE game (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    white_id            BIGINT      NOT NULL REFERENCES app_user (id),
    black_id            BIGINT      NOT NULL REFERENCES app_user (id),
    status              VARCHAR(12) NOT NULL DEFAULT 'FINISHED',
    result              VARCHAR(12),
    termination         VARCHAR(24),
    source              VARCHAR(16) NOT NULL DEFAULT 'OTB',
    external_id         VARCHAR(100),          -- Chess.com / Lichess game id for imports
    client_request_id   UUID,                  -- Idempotency-Key: a double-clicked submit is one game
    rated               BOOLEAN     NOT NULL DEFAULT TRUE,
    featured            BOOLEAN     NOT NULL DEFAULT FALSE,   -- shown in TV mode
    time_control        VARCHAR(32),
    pgn                 TEXT,
    initial_fen         VARCHAR(100),
    current_fen         VARCHAR(100),
    ply_count           INTEGER     NOT NULL DEFAULT 0,
    opening_name        VARCHAR(120),
    comment             VARCHAR(1000),
    white_rating_before INTEGER,
    black_rating_before INTEGER,
    white_rating_delta  INTEGER,
    black_rating_delta  INTEGER,
    created_by          BIGINT      NOT NULL REFERENCES app_user (id),
    played_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_game_distinct_players CHECK (white_id <> black_id),
    CONSTRAINT ck_game_status CHECK (status IN ('SCHEDULED', 'LIVE', 'FINISHED', 'ABORTED')),
    CONSTRAINT ck_game_result CHECK (result IS NULL OR result IN ('WHITE_WIN', 'BLACK_WIN', 'DRAW')),
    CONSTRAINT ck_game_result_matches_status CHECK ((status = 'FINISHED') = (result IS NOT NULL)),
    CONSTRAINT ck_game_termination CHECK (termination IS NULL OR termination IN
        ('CHECKMATE', 'RESIGNATION', 'TIMEOUT', 'STALEMATE', 'THREEFOLD_REPETITION', 'FIFTY_MOVE_RULE',
         'INSUFFICIENT_MATERIAL', 'AGREEMENT', 'FORFEIT', 'UNKNOWN')),
    CONSTRAINT ck_game_source CHECK (source IN ('OTB', 'LIVE_ENTRY', 'PGN_IMPORT', 'CHESS_COM', 'LICHESS')),
    CONSTRAINT ck_game_ply_count CHECK (ply_count >= 0),
    CONSTRAINT ck_game_pgn_size CHECK (pgn IS NULL OR length(pgn) <= 100000)
);
-- Composite indexes for the hot queries: a player's game list, head-to-head, TV mode.
CREATE INDEX ix_game_white_played ON game (white_id, played_at DESC);
CREATE INDEX ix_game_black_played ON game (black_id, played_at DESC);
CREATE INDEX ix_game_pairing ON game (white_id, black_id, played_at DESC);
CREATE INDEX ix_game_status_featured ON game (status, featured, played_at DESC);
-- Importing the same online game twice must be impossible.
CREATE UNIQUE INDEX ux_game_external ON game (source, external_id) WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX ux_game_client_request ON game (created_by, client_request_id) WHERE client_request_id IS NOT NULL;
CREATE INDEX ix_game_created_by ON game (created_by);

CREATE TABLE game_move (
    game_id   BIGINT       NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    ply       INTEGER      NOT NULL,
    san       VARCHAR(10)  NOT NULL,
    uci       VARCHAR(5)   NOT NULL,
    fen_after VARCHAR(100) NOT NULL,
    played_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, ply),
    CONSTRAINT ck_game_move_ply CHECK (ply >= 1)
);

CREATE TABLE game_analysis (
    game_id             BIGINT PRIMARY KEY REFERENCES game (id) ON DELETE CASCADE,
    engine              VARCHAR(40)   NOT NULL,
    depth               INTEGER       NOT NULL,
    white_accuracy      NUMERIC(5, 2) NOT NULL,
    black_accuracy      NUMERIC(5, 2) NOT NULL,
    white_acpl          INTEGER       NOT NULL,   -- average centipawn loss
    black_acpl          INTEGER       NOT NULL,
    white_brilliancies  INTEGER       NOT NULL DEFAULT 0,
    black_brilliancies  INTEGER       NOT NULL DEFAULT 0,
    white_inaccuracies  INTEGER       NOT NULL DEFAULT 0,
    black_inaccuracies  INTEGER       NOT NULL DEFAULT 0,
    white_mistakes      INTEGER       NOT NULL DEFAULT 0,
    black_mistakes      INTEGER       NOT NULL DEFAULT 0,
    white_blunders      INTEGER       NOT NULL DEFAULT 0,
    black_blunders      INTEGER       NOT NULL DEFAULT 0,
    -- Per-ply data: [{ply, cp, mate, best, classification}, ...]
    moves               JSONB         NOT NULL,
    submitted_by        BIGINT        NOT NULL REFERENCES app_user (id),
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_game_analysis_accuracy CHECK (white_accuracy BETWEEN 0 AND 100 AND black_accuracy BETWEEN 0 AND 100),
    CONSTRAINT ck_game_analysis_depth CHECK (depth BETWEEN 1 AND 60),
    CONSTRAINT ck_game_analysis_acpl CHECK (white_acpl >= 0 AND black_acpl >= 0)
);

CREATE TABLE rating_history (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    game_id       BIGINT      NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    rating_before INTEGER     NOT NULL,
    rating_after  INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A game can move a player's rating exactly once, whatever the retry/concurrency story.
    CONSTRAINT ux_rating_history_user_game UNIQUE (user_id, game_id)
);
CREATE INDEX ix_rating_history_user_time ON rating_history (user_id, created_at);
CREATE INDEX ix_rating_history_game ON rating_history (game_id);

CREATE TABLE tournament_pairing (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    round_id             BIGINT      NOT NULL REFERENCES tournament_round (id) ON DELETE CASCADE,
    board_number         INTEGER     NOT NULL,
    white_participant_id BIGINT REFERENCES tournament_participant (id),
    black_participant_id BIGINT REFERENCES tournament_participant (id),   -- NULL with a white player = bye
    game_id              BIGINT REFERENCES game (id) ON DELETE SET NULL,
    result               VARCHAR(12),
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ux_tournament_pairing_board UNIQUE (round_id, board_number),
    CONSTRAINT ux_tournament_pairing_game UNIQUE (game_id),
    CONSTRAINT ck_tournament_pairing_distinct CHECK (white_participant_id IS DISTINCT FROM black_participant_id
        OR white_participant_id IS NULL),
    CONSTRAINT ck_tournament_pairing_result CHECK (result IS NULL OR result IN ('WHITE_WIN', 'BLACK_WIN', 'DRAW', 'BYE'))
);
CREATE INDEX ix_tournament_pairing_white ON tournament_pairing (white_participant_id);
CREATE INDEX ix_tournament_pairing_black ON tournament_pairing (black_participant_id);

CREATE TABLE user_badge (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    badge_code VARCHAR(40) NOT NULL,
    game_id    BIGINT REFERENCES game (id) ON DELETE SET NULL,
    awarded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, badge_code)
);
CREATE INDEX ix_user_badge_game ON user_badge (game_id);
