package no.sjakkportal.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import no.sjakkportal.chess.ChessRules;
import no.sjakkportal.chess.MoveOutcome;
import no.sjakkportal.chess.ParsedGame;
import no.sjakkportal.chess.Termination;
import no.sjakkportal.common.AuditLog;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.text.Sanitizer;
import no.sjakkportal.rating.GameResult;
import no.sjakkportal.rating.RatingService;
import no.sjakkportal.rating.TimeCategory;
import no.sjakkportal.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Game lifecycle.
 *
 * <pre>
 *   registered by a player ──► PENDING ──confirm (opponent/admin)──► FINISHED (rated)
 *                                  └────reject / withdraw─────────► REJECTED
 *   registered by an admin ─────────────────────────────────────► FINISHED (rated)
 *   live entry: LIVE ──finish or checkmate──► PENDING or FINISHED as above;  LIVE ──abort──► ABORTED
 * </pre>
 *
 * Only the two players or an admin may change a game. Ratings move exactly once, at confirmation.
 */
@Service
public class GameService {

    public record SubmitCommand(long whiteId, long blackId, GameResult result, String pgn, Termination termination,
                                Instant playedAt, Boolean rated, String comment, TimeCategory category, String timeControl,
                                UUID idempotencyKey) {
    }

    public record ImportCommand(long whiteId, long blackId, GameResult result, ParsedGame parsed, String source, String externalId,
                                TimeCategory category, String timeControl, Instant playedAt) {
    }

    /** A list entry: everything except the moves and PGN. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record GameListItem(long id, String status, String result, String termination, String category, String timeControl,
                               boolean rated, String source, GameView.Player white, GameView.Player black, int plyCount,
                               Instant playedAt, Integer whiteRatingDelta, Integer blackRatingDelta, Long tournamentPairingId) {
    }

    public record LiveCommand(long whiteId, long blackId, Boolean rated, TimeCategory category, String timeControl) {
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record MoveResponse(int ply, String san, String uci, String fen, boolean check, boolean gameOver, String status,
                               String result, String termination) {
    }

    private final GameRepository games;
    private final RatingService ratingService;
    private final JdbcClient jdbc;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;

    public GameService(GameRepository games, RatingService ratingService, JdbcClient jdbc, AuditLog auditLog,
                       ApplicationEventPublisher events) {
        this.games = games;
        this.ratingService = ratingService;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.events = events;
    }

    /** Ratings, tournament progression and live updates, in that order, for a game that just became official. */
    private void finalized(Game game) {
        ratingService.apply(game);
        events.publishEvent(new GameFinishedEvent(game));
    }

    private void changed(Game game) {
        if (game.status != GameStatus.LIVE) {
            stopClock(game.id);
        }
        events.publishEvent(new GameChangedEvent(game.id));
    }

    // ------------------------------------------------------------------------------------------
    // Registering a finished game
    // ------------------------------------------------------------------------------------------

    @Transactional
    public GameView submit(CurrentUser actor, SubmitCommand command) {
        return submit(actor, command, null);
    }

    /** @param tournamentPairingId set when the game belongs to a tournament pairing (arena games) */
    @Transactional
    public GameView submit(CurrentUser actor, SubmitCommand command, Long tournamentPairingId) {
        requirePlayers(actor, command.whiteId(), command.blackId());
        TimeCategory category = TimeCategory.resolve(command.category(), command.timeControl());

        Game game = new Game(command.whiteId(), command.blackId(), category, TimeCategory.normalize(command.timeControl()),
                command.rated() == null || command.rated(), "OTB", actor.id());
        game.clientRequestId = command.idempotencyKey();
        game.tournamentPairingId = tournamentPairingId;
        game.result = command.result();
        game.termination = command.termination();
        game.comment = Sanitizer.plainText(command.comment(), 1000);
        if (command.playedAt() != null) {
            game.playedAt = command.playedAt();
        }
        ParsedGame parsed = null;
        if (command.pgn() != null && !command.pgn().isBlank()) {
            parsed = ChessRules.parsePgn(command.pgn());
            Optional<GameResult> fromPgn = parsed.declaredResult().or(parsed::boardOutcome);
            if (fromPgn.isPresent() && fromPgn.get() != command.result()) {
                throw new ChessException(ErrorCode.PGN_RESULT_MISMATCH);
            }
            game.pgn = parsed.normalizedPgn();
            game.initialFen = parsed.initialFen();
            game.currentFen = parsed.finalFen();
            game.plyCount = parsed.plyCount();
            // What the board shows beats what the form says.
            parsed.termination().ifPresent(termination -> game.termination = termination);
        }
        settle(game, actor);
        // Flushing here surfaces a duplicate Idempotency-Key before any rating is touched.
        games.saveAndFlush(game);
        if (parsed != null) {
            insertMoves(game.id, parsed);
        }
        if (game.status == GameStatus.FINISHED) {
            finalized(game);
            auditLog.record(actor.id(), "GAME_REGISTERED_BY_ADMIN", "game:" + game.id, null);
        }
        changed(game);
        return view(game);
    }

    /** An imported online game: final at once and never rated (imports are for analysis). */
    @Transactional
    public GameView importFinished(CurrentUser actor, ImportCommand command) {
        Game game = new Game(command.whiteId(), command.blackId(), command.category(), command.timeControl(), false,
                command.source(), actor.id());
        game.externalId = command.externalId();
        game.result = command.result();
        game.termination = command.parsed().termination().orElse(Termination.UNKNOWN);
        game.playedAt = command.playedAt();
        game.pgn = command.parsed().normalizedPgn();
        game.initialFen = command.parsed().initialFen();
        game.currentFen = command.parsed().finalFen();
        game.plyCount = command.parsed().plyCount();
        game.registeredBy = actor.id();
        game.finishedAt = command.playedAt();
        game.confirm(actor.id());
        games.saveAndFlush(game);
        insertMoves(game.id, command.parsed());
        changed(game);
        return view(game);
    }

    private static final String LIST_COLUMNS = """
            g.id, g.status, g.result, g.termination, g.category, g.time_control, g.rated, g.source, g.ply_count, g.played_at,
            g.white_rating_delta, g.black_rating_delta, g.tournament_pairing_id,
            w.id AS w_id, w.username AS w_username, w.display_name AS w_name,
            b.id AS b_id, b.username AS b_username, b.display_name AS b_name""";

    /**
     * Games newest first, optionally for one player. Keyset paging: pass the id of the last game of the
     * previous page as {@code before}.
     */
    @Transactional(readOnly = true)
    public List<GameListItem> list(Long userId, Long before, int limit) {
        return jdbc.sql("SELECT " + LIST_COLUMNS + """
                         FROM game g JOIN app_user w ON w.id = g.white_id JOIN app_user b ON b.id = g.black_id
                        WHERE g.status IN ('LIVE', 'PENDING', 'FINISHED')
                          AND (CAST(:user AS BIGINT) IS NULL OR g.white_id = :user OR g.black_id = :user)
                          AND (CAST(:before AS BIGINT) IS NULL OR (g.played_at, g.id) <
                               (SELECT p.played_at, p.id FROM game p WHERE p.id = :before))
                        ORDER BY g.played_at DESC, g.id DESC LIMIT :limit""")
                .param("user", userId).param("before", before).param("limit", limit)
                .query((rs, row) -> new GameListItem(rs.getLong("id"), rs.getString("status"), rs.getString("result"),
                        rs.getString("termination"), rs.getString("category"), rs.getString("time_control"), rs.getBoolean("rated"),
                        rs.getString("source"), new GameView.Player(rs.getLong("w_id"), rs.getString("w_username"), rs.getString("w_name")),
                        new GameView.Player(rs.getLong("b_id"), rs.getString("b_username"), rs.getString("b_name")), rs.getInt("ply_count"),
                        rs.getTimestamp("played_at").toInstant(), (Integer) rs.getObject("white_rating_delta"),
                        (Integer) rs.getObject("black_rating_delta"), (Long) rs.getObject("tournament_pairing_id")))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<GameView> findByIdempotencyKey(long userId, UUID key) {
        return games.findByCreatedByAndClientRequestId(userId, key).map(this::view);
    }

    /** An admin's word is final; a player's report waits for the other side. */
    private static void settle(Game game, CurrentUser actor) {
        game.registeredBy = actor.id();
        game.finishedAt = Instant.now();
        if (actor.isAdmin()) {
            game.confirm(actor.id());
        } else {
            game.status = GameStatus.PENDING;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Confirmation
    // ------------------------------------------------------------------------------------------

    @Transactional
    public GameView confirm(CurrentUser actor, long gameId) {
        Game game = lockForChange(actor, gameId);
        if (game.status != GameStatus.PENDING) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE);
        }
        if (!actor.isAdmin() && game.registeredBy != null && game.registeredBy == actor.id()) {
            throw new ChessException(ErrorCode.GAME_SELF_CONFIRMATION);
        }
        game.confirm(actor.id());
        // The entity update must reach the database before the rating rows reference the game as finished.
        games.saveAndFlush(game);
        finalized(game);
        if (actor.isAdmin()) {
            auditLog.record(actor.id(), "GAME_CONFIRMED_BY_ADMIN", "game:" + game.id, null);
        }
        changed(game);
        return view(game);
    }

    @Transactional
    public GameView reject(CurrentUser actor, long gameId, String reason) {
        Game game = lockForChange(actor, gameId);
        if (game.status != GameStatus.PENDING) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE);
        }
        game.rejectionReason = Sanitizer.plainText(reason, 300);
        if (isScheduledTournamentGame(game)) {
            // A disputed tournament game still has to be played out: it goes back to waiting for a result.
            game.status = GameStatus.SCHEDULED;
            game.result = null;
            game.termination = null;
            game.registeredBy = null;
            game.finishedAt = null;
        } else {
            game.status = GameStatus.REJECTED;
        }
        changed(game);
        return view(game);
    }

    /** Games created by a tournament schedule (not arena games, which players register ad hoc). */
    private boolean isScheduledTournamentGame(Game game) {
        if (game.tournamentPairingId == null) {
            return false;
        }
        String format = jdbc.sql("""
                        SELECT t.format FROM tournament_pairing p JOIN tournament_round r ON r.id = p.round_id
                        JOIN tournament t ON t.id = r.tournament_id WHERE p.id = ?""")
                .param(game.tournamentPairingId).query(String.class).optional().orElse("ARENA");
        return !format.equals("ARENA");
    }

    // ------------------------------------------------------------------------------------------
    // Scheduled games (tournaments)
    // ------------------------------------------------------------------------------------------

    /** A game that will be played: created by a tournament for a pairing, waiting for a result. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public long createScheduled(long pairingId, long whiteId, long blackId, TimeCategory category, String timeControl,
                                boolean rated, String stage, long createdBy) {
        Game game = new Game(whiteId, blackId, category, timeControl, rated, "OTB", createdBy);
        game.status = GameStatus.SCHEDULED;
        game.stage = stage;
        game.tournamentPairingId = pairingId;
        games.saveAndFlush(game);
        changed(game);
        return game.id;
    }

    /** Reports the result of a scheduled game, through the same confirmation rules as any other game. */
    @Transactional
    public GameView report(CurrentUser actor, long gameId, GameResult result, String pgn, Termination termination) {
        Game game = lockForChange(actor, gameId);
        if (game.status != GameStatus.SCHEDULED) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE);
        }
        game.result = result;
        game.termination = termination;
        ParsedGame parsed = null;
        if (pgn != null && !pgn.isBlank()) {
            parsed = ChessRules.parsePgn(pgn);
            Optional<GameResult> fromPgn = parsed.declaredResult().or(parsed::boardOutcome);
            if (fromPgn.isPresent() && fromPgn.get() != result) {
                throw new ChessException(ErrorCode.PGN_RESULT_MISMATCH);
            }
            jdbc.sql("DELETE FROM game_move WHERE game_id = ?").param(gameId).update();
            game.pgn = parsed.normalizedPgn();
            game.initialFen = parsed.initialFen();
            game.currentFen = parsed.finalFen();
            game.plyCount = parsed.plyCount();
            parsed.termination().ifPresent(t -> game.termination = t);
        }
        game.playedAt = Instant.now();
        settle(game, actor);
        games.saveAndFlush(game);
        if (parsed != null) {
            insertMoves(game.id, parsed);
        }
        if (game.status == GameStatus.FINISHED) {
            finalized(game);
            auditLog.record(actor.id(), "GAME_REPORTED_BY_ADMIN", "game:" + game.id, null);
        }
        changed(game);
        return view(game);
    }

    /** Starts move-by-move entry of a scheduled game, so spectators and TV mode can follow it. */
    @Transactional
    public GameView startScheduled(CurrentUser actor, long gameId) {
        Game game = lockForChange(actor, gameId);
        if (game.status != GameStatus.SCHEDULED) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE);
        }
        game.status = GameStatus.LIVE;
        game.initialFen = ChessRules.START_FEN;
        game.currentFen = ChessRules.START_FEN;
        game.playedAt = Instant.now();
        changed(game);
        return view(game);
    }

    /** Marks a game as the one TV mode shows; at most one game is featured at a time. */
    @Transactional
    public GameView feature(CurrentUser admin, long gameId, boolean featured) {
        Game game = games.lockById(gameId).orElseThrow(() -> new ChessException(ErrorCode.GAME_NOT_FOUND));
        if (featured) {
            jdbc.sql("UPDATE game SET featured = FALSE, version = version + 1 WHERE featured AND id <> ?").param(gameId).update();
        }
        game.featured = featured;
        changed(game);
        return view(game);
    }

    /** Games waiting for this player's confirmation: reported by the opponent, not by the player. */
    @Transactional(readOnly = true)
    public List<GameView> pendingFor(long userId) {
        return views(jdbc.sql("""
                        SELECT id FROM game WHERE status = 'PENDING' AND (white_id = :me OR black_id = :me)
                          AND registered_by IS DISTINCT FROM :me ORDER BY played_at""")
                .param("me", userId).query(Long.class).list());
    }

    @Transactional(readOnly = true)
    public List<GameView> allPending() {
        return views(jdbc.sql("SELECT id FROM game WHERE status = 'PENDING' ORDER BY played_at").query(Long.class).list());
    }

    // ------------------------------------------------------------------------------------------
    // Live entry
    // ------------------------------------------------------------------------------------------

    @Transactional
    public GameView startLive(CurrentUser actor, LiveCommand command) {
        requirePlayers(actor, command.whiteId(), command.blackId());
        Game game = new Game(command.whiteId(), command.blackId(), TimeCategory.resolve(command.category(), command.timeControl()),
                TimeCategory.normalize(command.timeControl()), command.rated() == null || command.rated(), "LIVE_ENTRY", actor.id());
        game.status = GameStatus.LIVE;
        game.initialFen = ChessRules.START_FEN;
        game.currentFen = ChessRules.START_FEN;
        return view(games.saveAndFlush(game));
    }

    @Transactional
    public MoveResponse move(CurrentUser actor, long gameId, String move, int expectedPly) {
        Game game = lockForChange(actor, gameId);
        if (game.status != GameStatus.LIVE) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE);
        }
        // The client says which position it is looking at. If the board has moved on, it must reload
        // rather than have its move applied to a position it never saw.
        if (expectedPly != game.plyCount) {
            throw new ChessException(ErrorCode.GAME_CONCURRENT_UPDATE);
        }
        MoveOutcome outcome = ChessRules.applyMove(game.initialFen, sanMoves(gameId), move);
        int ply = game.plyCount + 1;
        jdbc.sql("INSERT INTO game_move (game_id, ply, san, uci, fen_after) VALUES (?, ?, ?, ?, ?)")
                .params(gameId, ply, outcome.san(), outcome.uci(), outcome.fenAfter()).update();
        game.plyCount = ply;
        game.currentFen = outcome.fenAfter();

        boolean gameOver = outcome.boardOutcome().isPresent();
        if (gameOver) {
            finishLive(game, actor, outcome.boardOutcome().get(), outcome.termination().orElse(Termination.UNKNOWN));
        }
        changed(game);
        return new MoveResponse(ply, outcome.san(), outcome.uci(), outcome.fenAfter(), outcome.check(), gameOver,
                game.status.name(), game.result == null ? null : game.result.name(),
                game.termination == null ? null : game.termination.name());
    }

    @Transactional
    public GameView finish(CurrentUser actor, long gameId, GameResult result, Termination termination) {
        Game game = lockForChange(actor, gameId);
        if (game.status != GameStatus.LIVE) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE);
        }
        // A claim the position can settle is checked against the position.
        boolean verifiable = termination == Termination.CHECKMATE || termination == Termination.STALEMATE
                || termination == Termination.INSUFFICIENT_MATERIAL;
        if (verifiable) {
            Optional<ParsedGame> played = replay(game);
            boolean matches = played.isPresent() && played.get().termination().filter(t -> t == termination).isPresent()
                    && played.get().boardOutcome().filter(r -> r == result).isPresent();
            if (!matches) {
                throw new ChessException(ErrorCode.PGN_RESULT_MISMATCH,
                        "Stillingen på brettet stemmer ikke med resultatet du har oppgitt.");
            }
        }
        finishLive(game, actor, result, termination == null ? Termination.UNKNOWN : termination);
        changed(game);
        return view(game);
    }

    @Transactional
    public GameView abort(CurrentUser actor, long gameId) {
        Game game = lockForChange(actor, gameId);
        if (game.status != GameStatus.LIVE && game.status != GameStatus.SCHEDULED) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE);
        }
        game.status = GameStatus.ABORTED;
        changed(game);
        return view(game);
    }

    private void finishLive(Game game, CurrentUser actor, GameResult result, Termination termination) {
        game.result = result;
        game.termination = termination;
        settle(game, actor);
        games.saveAndFlush(game);
        replay(game).ifPresent(played -> game.pgn = played.normalizedPgn());
        if (game.status == GameStatus.FINISHED) {
            finalized(game);
            auditLog.record(actor.id(), "GAME_FINISHED_BY_ADMIN", "game:" + game.id, null);
        }
    }

    /** The moves entered so far as a validated game, or empty when there are none. */
    private Optional<ParsedGame> replay(Game game) {
        List<String> moves = sanMoves(game.id);
        if (moves.isEmpty()) {
            return Optional.empty();
        }
        String result = game.result == null ? "*" : switch (game.result) {
            case WHITE_WIN -> "1-0";
            case BLACK_WIN -> "0-1";
            case DRAW -> "1/2-1/2";
        };
        return Optional.of(ChessRules.parsePgn(String.join(" ", moves) + " " + result));
    }

    // ------------------------------------------------------------------------------------------
    // Admin corrections
    // ------------------------------------------------------------------------------------------

    /**
     * Changes the result of a confirmed game and replays every rating in its category, so the
     * outcome is exactly as if the right result had been registered in the first place.
     */
    @Transactional
    public GameView correct(CurrentUser admin, long gameId, GameResult result, Termination termination, String rawReason) {
        String reason = correctionReason(rawReason);
        Game game = lockConfirmed(admin, gameId);
        // A game whose moves end in mate or stalemate has only one possible result.
        replay(game).flatMap(ParsedGame::boardOutcome).filter(outcome -> outcome != result).ifPresent(outcome -> {
            throw new ChessException(ErrorCode.PGN_RESULT_MISMATCH, "Trekkene i partiet ender med et annet resultat.");
        });
        events.publishEvent(new GameCorrectedEvent(game, result));
        GameResult before = game.result;
        game.result = result;
        if (termination != null) {
            game.termination = termination;
        }
        markCorrected(game, admin, reason);
        auditLog.record(admin.id(), "GAME_CORRECTED", "game:" + game.id, before + " -> " + result + ": " + reason);
        return view(game);
    }

    /** Takes a confirmed game out of ratings, standings and statistics, keeping it visible as annulled. */
    @Transactional
    public GameView annul(CurrentUser admin, long gameId, String rawReason) {
        String reason = correctionReason(rawReason);
        Game game = lockConfirmed(admin, gameId);
        events.publishEvent(new GameCorrectedEvent(game, null));
        game.status = GameStatus.ANNULLED;
        game.featured = false;
        // Only a confirmed game may carry rating changes; the replay below rates everything else again.
        game.whiteRatingBefore = null;
        game.whiteRatingDelta = null;
        game.blackRatingBefore = null;
        game.blackRatingDelta = null;
        markCorrected(game, admin, reason);
        auditLog.record(admin.id(), "GAME_ANNULLED", "game:" + game.id, game.result + ": " + reason);
        return view(game);
    }

    private static String correctionReason(String raw) {
        String reason = Sanitizer.plainText(raw == null ? "" : raw, 1000);
        if (reason.isBlank() || reason.length() > 300) {
            throw new ChessException(ErrorCode.VALIDATION_FAILED, "Skriv en kort begrunnelse (høyst 300 tegn).");
        }
        return reason;
    }

    private Game lockConfirmed(CurrentUser admin, long gameId) {
        Game game = lockForChange(admin, gameId);
        if (game.status != GameStatus.FINISHED) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE, "Bare bekreftede partier kan rettes.");
        }
        return game;
    }

    private void markCorrected(Game game, CurrentUser admin, String reason) {
        game.correctedBy = admin.id();
        game.correctedAt = java.time.Instant.now();
        game.correctionReason = reason;
        games.saveAndFlush(game);
        if (game.rated) {
            ratingService.recompute(game.category);
            // the replay wrote the rating columns with SQL; reload them into the entity for the view
            games.flush();
            jdbc.sql("SELECT white_rating_before, white_rating_delta, black_rating_before, black_rating_delta FROM game WHERE id = ?")
                    .param(game.id).query((rs, row) -> {
                        game.whiteRatingBefore = (Integer) rs.getObject(1);
                        game.whiteRatingDelta = (Integer) rs.getObject(2);
                        game.blackRatingBefore = (Integer) rs.getObject(3);
                        game.blackRatingDelta = (Integer) rs.getObject(4);
                        return null;
                    }).list();
        }
        changed(game);
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public GameView get(long gameId) {
        return view(games.findById(gameId).orElseThrow(() -> new ChessException(ErrorCode.GAME_NOT_FOUND)));
    }

    /** Loads a game for a change by a participant or an admin, holding its row lock. */
    Game lockForChange(CurrentUser actor, long gameId) {
        Game game = games.lockById(gameId).orElseThrow(() -> new ChessException(ErrorCode.GAME_NOT_FOUND));
        if (!actor.isAdmin() && !game.hasPlayer(actor.id())) {
            throw new ChessException(ErrorCode.GAME_NOT_PARTICIPANT);
        }
        return game;
    }

    private void requirePlayers(CurrentUser actor, long whiteId, long blackId) {
        if (whiteId == blackId) {
            throw new ChessException(ErrorCode.GAME_SAME_PLAYER);
        }
        if (!actor.isAdmin() && actor.id() != whiteId && actor.id() != blackId) {
            throw new ChessException(ErrorCode.GAME_NOT_PARTICIPANT);
        }
        int found = jdbc.sql("SELECT count(*) FROM app_user WHERE id IN (?, ?) AND enabled").params(whiteId, blackId)
                .query(Integer.class).single();
        if (found != 2) {
            throw new ChessException(ErrorCode.USER_NOT_FOUND);
        }
    }

    private List<String> sanMoves(long gameId) {
        return jdbc.sql("SELECT san FROM game_move WHERE game_id = ? ORDER BY ply").param(gameId).query(String.class).list();
    }

    private void insertMoves(long gameId, ParsedGame parsed) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < parsed.plyCount(); i++) {
            rows.add(new Object[] {gameId, i + 1, parsed.sanMoves().get(i), parsed.uciMoves().get(i), parsed.fens().get(i)});
        }
        for (Object[] row : rows) {
            jdbc.sql("INSERT INTO game_move (game_id, ply, san, uci, fen_after) VALUES (?, ?, ?, ?, ?)").params(row).update();
        }
    }

    private List<GameView> views(List<Long> ids) {
        return games.findAllById(ids).stream().sorted((a, b) -> a.playedAt.compareTo(b.playedAt)).map(this::view).toList();
    }

    GameView view(Game game) {
        Map<Long, GameView.Player> players = new HashMap<>();
        List<Long> ids = new ArrayList<>(List.of(game.whiteId, game.blackId));
        if (game.registeredBy != null) {
            ids.add(game.registeredBy);
        }
        if (game.confirmedBy != null) {
            ids.add(game.confirmedBy);
        }
        if (game.correctedBy != null) {
            ids.add(game.correctedBy);
        }
        jdbc.sql("SELECT id, username, display_name FROM app_user WHERE id IN (:ids)").param("ids", ids)
                .query((rs, row) -> players.put(rs.getLong(1), new GameView.Player(rs.getLong(1), rs.getString(2), rs.getString(3))))
                .list();
        List<GameView.Move> moves = jdbc.sql("SELECT ply, san, uci, fen_after FROM game_move WHERE game_id = ? ORDER BY ply")
                .param(game.id)
                .query((rs, row) -> new GameView.Move(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        return new GameView(game.id, game.status.name(), game.result == null ? null : game.result.name(),
                game.termination == null ? null : game.termination.name(), game.category.name(), game.timeControl, game.rated,
                game.stage, game.tournamentPairingId, game.featured,
                game.source, players.get(game.whiteId), players.get(game.blackId),
                game.registeredBy == null ? null : players.get(game.registeredBy),
                game.confirmedBy == null ? null : players.get(game.confirmedBy), game.rejectionReason, game.plyCount,
                game.currentFen, game.comment, game.playedAt, game.whiteRatingBefore, game.whiteRatingDelta,
                game.blackRatingBefore, game.blackRatingDelta, game.pgn, moves,
                game.correctedAt == null ? null : new GameView.Correction(game.correctionReason, players.get(game.correctedBy), game.correctedAt),
                clock(game.id));
    }

    /** A game that is no longer live has its clock stopped where it stands (see ChessClockService). */
    private void stopClock(long gameId) {
        jdbc.sql("""
                        UPDATE game_clock SET
                            white_ms = CASE WHEN running = 'WHITE'
                                THEN greatest(0, white_ms - (extract(epoch FROM now() - updated_at) * 1000)::int) ELSE white_ms END,
                            black_ms = CASE WHEN running = 'BLACK'
                                THEN greatest(0, black_ms - (extract(epoch FROM now() - updated_at) * 1000)::int) ELSE black_ms END,
                            running = NULL, updated_at = now()
                        WHERE game_id = ? AND running IS NOT NULL""").param(gameId).update();
    }

    private GameView.Clock clock(long gameId) {
        return jdbc.sql("""
                        SELECT initial_ms, increment_ms, white_ms, black_ms, running, sequence, updated_at, now() AS server_now
                        FROM game_clock WHERE game_id = ?""").param(gameId)
                .query((rs, row) -> new GameView.Clock(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                        rs.getInt(6), rs.getTimestamp(7).toInstant(), java.time.Instant.now()))
                .optional().orElse(null);
    }
}
