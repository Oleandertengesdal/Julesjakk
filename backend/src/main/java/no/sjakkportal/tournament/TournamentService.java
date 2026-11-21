package no.sjakkportal.tournament;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.sjakkportal.chess.Termination;
import no.sjakkportal.common.AuditLog;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.text.Sanitizer;
import no.sjakkportal.game.Game;
import no.sjakkportal.game.GameFinishedEvent;
import no.sjakkportal.game.GameService;
import no.sjakkportal.game.GameView;
import no.sjakkportal.rating.EloCalculator;
import no.sjakkportal.rating.GameResult;
import no.sjakkportal.rating.TimeCategory;
import no.sjakkportal.security.CurrentUser;
import no.sjakkportal.tournament.TournamentViews.Detail;
import no.sjakkportal.tournament.TournamentViews.Summary;
import no.sjakkportal.tournament.engine.BracketMatch;
import no.sjakkportal.tournament.engine.Color;
import no.sjakkportal.tournament.engine.EliminationBracket;
import no.sjakkportal.tournament.engine.Pairing;
import no.sjakkportal.tournament.engine.RoundRobinScheduler;
import no.sjakkportal.tournament.engine.SwissPairer;
import no.sjakkportal.tournament.engine.SwissPlayer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tournament lifecycle: DRAFT (sign-up) → ACTIVE (rounds are played) → FINISHED, or CANCELLED.
 *
 * <p>Every pairing gets a scheduled game. Its result is reported and confirmed like any other game;
 * when the game becomes official, {@link #onGameFinished} records the pairing result and, once the
 * round is complete, creates the next round. Every state change takes the tournament row lock first,
 * so concurrent confirmations of the last games of a round create the next round exactly once.
 *
 * <p>The pairing logic itself lives in the pure classes of the {@code engine} package; this class only
 * loads state, calls the engine and stores what it decides.
 */
@Service
public class TournamentService {

    private static final int MAX_SWISS_ROUNDS = 30;
    private static final String PLAYOFF_TIME_CONTROL = "5+3";

    public record CreateCommand(String name, String description, TournamentFormat format, TimeCategory category,
                                String timeControl, Boolean rated, DrawResolution drawResolution, Integer rounds,
                                Instant startsAt, Instant endsAt) {
    }

    public record ArenaGameCommand(long whiteId, long blackId, GameResult result, String pgn, Termination termination) {
    }

    /** Raw rows. Column names map to component names (snake_case to camelCase). */
    record TournamentRow(long id, String name, String description, String format, String status, boolean rated,
                         String category, String timeControl, String drawResolution, Integer totalRounds, int currentRound,
                         Timestamp startsAt, Timestamp endsAt, long createdBy, Timestamp createdAt) {

        TournamentFormat formatEnum() {
            return TournamentFormat.valueOf(format);
        }
    }

    record ParticipantRow(long id, long userId, int seed, int seedRating) {
    }

    record RoundRow(long id, String bracket, int roundNumber, String status) {
    }

    record PairingRow(long id, long roundId, int boardNumber, Long whiteParticipantId, Long blackParticipantId, Long gameId,
                      String result, Long advancingParticipantId) {
    }

    record GameRow(long id, long whiteId, long blackId, String status, String result, String stage, String category,
                   boolean rated, Timestamp playedAt) {
    }

    private final JdbcClient jdbc;
    private final GameService gameService;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final TournamentReader reader;

    public TournamentService(JdbcClient jdbc, GameService gameService, AuditLog auditLog, ApplicationEventPublisher events,
                             TournamentReader reader) {
        this.jdbc = jdbc;
        this.gameService = gameService;
        this.auditLog = auditLog;
        this.events = events;
        this.reader = reader;
    }

    // ------------------------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------------------------

    @Transactional
    public Detail create(CurrentUser admin, CreateCommand command) {
        String name = Sanitizer.plainText(command.name(), 100);
        TournamentFormat format = command.format();
        if (name == null || name.isBlank() || format == null) {
            throw new ChessException(ErrorCode.VALIDATION_FAILED);
        }
        TimeCategory category = TimeCategory.resolve(command.category(), command.timeControl());
        boolean knockout = format.isKnockout();
        boolean invalid = knockout != (command.drawResolution() != null)
                || (command.rounds() != null && (format != TournamentFormat.SWISS || command.rounds() < 1
                        || command.rounds() > MAX_SWISS_ROUNDS))
                || (format == TournamentFormat.ARENA && (command.startsAt() == null || command.endsAt() == null))
                || (command.startsAt() != null && command.endsAt() != null && !command.endsAt().isAfter(command.startsAt()));
        if (invalid) {
            throw new ChessException(ErrorCode.TOURNAMENT_INVALID_CONFIG);
        }
        long id = jdbc.sql("""
                        INSERT INTO tournament (name, description, format, rated, category, time_control, draw_resolution,
                            total_rounds, starts_at, ends_at, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(name, Sanitizer.plainText(command.description(), 1000), format.name(),
                        command.rated() == null || command.rated(), category.name(), TimeCategory.normalize(command.timeControl()),
                        command.drawResolution() == null ? null : command.drawResolution().name(), command.rounds(),
                        timestamp(command.startsAt()), timestamp(command.endsAt()), admin.id())
                .query(Long.class).single();
        auditLog.record(admin.id(), "TOURNAMENT_CREATED", "tournament:" + id, null);
        return reader.detail(id);
    }

    @Transactional(readOnly = true)
    public List<Summary> list() {
        return reader.list();
    }

    @Transactional(readOnly = true)
    public Detail detail(long tournamentId) {
        return reader.detail(tournamentId);
    }

    @Transactional
    public Detail addParticipant(long tournamentId, long userId) {
        TournamentRow tournament = lock(tournamentId);
        requireStatus(tournament, "DRAFT");
        boolean exists = jdbc.sql("SELECT count(*) FROM app_user WHERE id = ? AND enabled").param(userId)
                .query(Integer.class).single() == 1;
        if (!exists) {
            throw new ChessException(ErrorCode.USER_NOT_FOUND);
        }
        // The tournament lock is held, so concurrent sign-ups take seed numbers one at a time.
        int nextSeed = jdbc.sql("SELECT COALESCE(max(seed), 0) + 1 FROM tournament_participant WHERE tournament_id = ?")
                .param(tournamentId).query(Integer.class).single();
        int inserted = jdbc.sql("""
                        INSERT INTO tournament_participant (tournament_id, user_id, seed, seed_rating) VALUES (?, ?, ?, ?)
                        ON CONFLICT (tournament_id, user_id) DO NOTHING""")
                .params(tournamentId, userId, nextSeed, ratingOf(userId, tournament.category())).update();
        if (inserted == 0) {
            throw new ChessException(ErrorCode.TOURNAMENT_ALREADY_REGISTERED);
        }
        changed(tournamentId);
        return reader.detail(tournamentId);
    }

    @Transactional
    public Detail removeParticipant(long tournamentId, long userId) {
        TournamentRow tournament = lock(tournamentId);
        requireStatus(tournament, "DRAFT");
        int removed = jdbc.sql("DELETE FROM tournament_participant WHERE tournament_id = ? AND user_id = ?")
                .params(tournamentId, userId).update();
        if (removed == 0) {
            throw new ChessException(ErrorCode.TOURNAMENT_NOT_PARTICIPANT);
        }
        changed(tournamentId);
        return reader.detail(tournamentId);
    }

    @Transactional
    public Detail start(CurrentUser admin, long tournamentId) {
        TournamentRow tournament = lock(tournamentId);
        requireStatus(tournament, "DRAFT");
        List<ParticipantRow> participants = participants(tournamentId);
        if (participants.size() < 2) {
            throw new ChessException(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS);
        }
        reseed(tournament, participants);
        participants = participants(tournamentId);
        List<Long> seededUsers = participants.stream().map(ParticipantRow::userId).toList();

        TournamentFormat format = tournament.formatEnum();
        Integer totalRounds = switch (format) {
            case ROUND_ROBIN, DOUBLE_ROUND_ROBIN -> RoundRobinScheduler.schedule(seededUsers, format == TournamentFormat.DOUBLE_ROUND_ROBIN).size();
            case SWISS -> tournament.totalRounds() != null ? tournament.totalRounds() : SwissPairer.recommendedRounds(participants.size());
            default -> null;
        };
        jdbc.sql("UPDATE tournament SET status = 'ACTIVE', total_rounds = ?, current_round = ?, version = version + 1, "
                        + "starts_at = COALESCE(starts_at, now()) WHERE id = ?")
                .params(totalRounds, format == TournamentFormat.ARENA ? 0 : 1, tournamentId).update();
        TournamentRow active = load(tournamentId);
        switch (format) {
            case ROUND_ROBIN, DOUBLE_ROUND_ROBIN, SWISS -> createRound(active, 1);
            case SINGLE_ELIMINATION, DOUBLE_ELIMINATION -> advanceBracket(active);
            case ARENA -> { }
        }
        auditLog.record(admin.id(), "TOURNAMENT_STARTED", "tournament:" + tournamentId, null);
        changed(tournamentId);
        return reader.detail(tournamentId);
    }

    @Transactional
    public Detail cancel(CurrentUser admin, long tournamentId) {
        TournamentRow tournament = lock(tournamentId);
        if (!tournament.status().equals("DRAFT") && !tournament.status().equals("ACTIVE")) {
            throw new ChessException(ErrorCode.TOURNAMENT_INVALID_STATE);
        }
        jdbc.sql("""
                UPDATE game SET status = 'ABORTED', version = version + 1
                WHERE status IN ('SCHEDULED', 'LIVE') AND tournament_pairing_id IN (
                    SELECT p.id FROM tournament_pairing p JOIN tournament_round r ON r.id = p.round_id WHERE r.tournament_id = ?)""")
                .param(tournamentId).update();
        jdbc.sql("UPDATE tournament SET status = 'CANCELLED', version = version + 1 WHERE id = ?").param(tournamentId).update();
        auditLog.record(admin.id(), "TOURNAMENT_CANCELLED", "tournament:" + tournamentId, null);
        changed(tournamentId);
        return reader.detail(tournamentId);
    }

    /** Ends an arena. Other formats finish by themselves when their last game is confirmed. */
    @Transactional
    public Detail finishArena(CurrentUser admin, long tournamentId) {
        TournamentRow tournament = lock(tournamentId);
        requireStatus(tournament, "ACTIVE");
        if (tournament.formatEnum() != TournamentFormat.ARENA) {
            throw new ChessException(ErrorCode.TOURNAMENT_INVALID_STATE);
        }
        jdbc.sql("UPDATE tournament SET status = 'FINISHED', version = version + 1 WHERE id = ?").param(tournamentId).update();
        auditLog.record(admin.id(), "TOURNAMENT_FINISHED", "tournament:" + tournamentId, null);
        changed(tournamentId);
        return reader.detail(tournamentId);
    }

    // ------------------------------------------------------------------------------------------
    // Arena
    // ------------------------------------------------------------------------------------------

    @Transactional
    public GameView arenaGame(CurrentUser actor, long tournamentId, ArenaGameCommand command) {
        TournamentRow tournament = lock(tournamentId);
        if (tournament.formatEnum() != TournamentFormat.ARENA) {
            throw new ChessException(ErrorCode.TOURNAMENT_INVALID_STATE);
        }
        requireStatus(tournament, "ACTIVE");
        if (!actor.isAdmin() && actor.id() != command.whiteId() && actor.id() != command.blackId()) {
            throw new ChessException(ErrorCode.GAME_NOT_PARTICIPANT);
        }
        Instant now = Instant.now();
        if (now.isBefore(tournament.startsAt().toInstant()) || !now.isBefore(tournament.endsAt().toInstant())) {
            throw new ChessException(ErrorCode.TOURNAMENT_ARENA_CLOSED);
        }
        Map<Long, ParticipantRow> byUser = byUser(participants(tournamentId));
        ParticipantRow white = byUser.get(command.whiteId());
        ParticipantRow black = byUser.get(command.blackId());
        if (white == null || black == null) {
            throw new ChessException(ErrorCode.TOURNAMENT_NOT_PARTICIPANT);
        }
        long roundId = roundId(tournamentId, "MAIN", 1);
        long pairingId = insertPairing(roundId, nextBoard(roundId), white.id(), black.id());
        GameView game = gameService.submit(actor, new GameService.SubmitCommand(command.whiteId(), command.blackId(), command.result(),
                command.pgn(), command.termination(), null, tournament.rated(), null, TimeCategory.valueOf(tournament.category()),
                tournament.timeControl(), null), pairingId);
        jdbc.sql("UPDATE tournament_pairing SET game_id = ? WHERE id = ?").params(game.id(), pairingId).update();
        changed(tournamentId);
        return game;
    }

    // ------------------------------------------------------------------------------------------
    // Progression
    // ------------------------------------------------------------------------------------------

    /** Runs inside the transaction that made the game official. */
    @EventListener
    public void onGameFinished(GameFinishedEvent event) {
        Game game = event.game();
        if (game.getTournamentPairingId() == null) {
            return;
        }
        long tournamentId = jdbc.sql("""
                        SELECT r.tournament_id FROM tournament_pairing p JOIN tournament_round r ON r.id = p.round_id WHERE p.id = ?""")
                .param(game.getTournamentPairingId()).query(Long.class).single();
        TournamentRow tournament = lock(tournamentId);
        if (!tournament.status().equals("ACTIVE")) {
            return;
        }
        PairingRow pairing = pairing(game.getTournamentPairingId());
        switch (tournament.formatEnum()) {
            case ARENA -> decide(pairing, game.getResult().name(), null);
            case ROUND_ROBIN, DOUBLE_ROUND_ROBIN, SWISS -> {
                decide(pairing, game.getResult().name(), null);
                completeRoundIfDone(tournament);
            }
            case SINGLE_ELIMINATION, DOUBLE_ELIMINATION -> {
                resolveKnockoutGame(tournament, pairing, game);
                advanceBracket(load(tournamentId));
            }
        }
        changed(tournamentId);
    }

    /**
     * An admin is correcting or annulling a tournament game. Knockout results that decided a match
     * cannot change (the bracket has moved on); a paired round-robin or Swiss game can have its result
     * corrected but not be annulled (its round would have a hole); arena games may do either.
     */
    @EventListener
    public void onGameCorrected(no.sjakkportal.game.GameCorrectedEvent event) {
        Game game = event.game();
        if (game.getTournamentPairingId() == null) {
            return;
        }
        long tournamentId = jdbc.sql("""
                        SELECT r.tournament_id FROM tournament_pairing p JOIN tournament_round r ON r.id = p.round_id WHERE p.id = ?""")
                .param(game.getTournamentPairingId()).query(Long.class).single();
        TournamentRow tournament = lock(tournamentId);
        TournamentFormat format = tournament.formatEnum();
        boolean knockout = format == TournamentFormat.SINGLE_ELIMINATION || format == TournamentFormat.DOUBLE_ELIMINATION;
        if (knockout || (event.annulled() && format != TournamentFormat.ARENA)) {
            throw new ChessException(ErrorCode.GAME_CORRECTION_NOT_ALLOWED);
        }
        jdbc.sql("UPDATE tournament_pairing SET result = ?, version = version + 1 WHERE id = ? AND game_id = ?")
                .params(event.annulled() ? null : event.newResult().name(), game.getTournamentPairingId(), game.getId()).update();
        changed(tournamentId);
    }

    private void completeRoundIfDone(TournamentRow tournament) {
        RoundRow round = jdbc.sql("SELECT id, bracket, round_number, status FROM tournament_round "
                        + "WHERE tournament_id = ? AND round_number = ? AND bracket = 'MAIN'")
                .params(tournament.id(), tournament.currentRound()).query(RoundRow.class).single();
        int open = jdbc.sql("SELECT count(*) FROM tournament_pairing WHERE round_id = ? AND result IS NULL")
                .param(round.id()).query(Integer.class).single();
        if (open > 0) {
            return;
        }
        jdbc.sql("UPDATE tournament_round SET status = 'FINISHED', finished_at = now() WHERE id = ?").param(round.id()).update();
        if (tournament.currentRound() < tournament.totalRounds()) {
            int next = tournament.currentRound() + 1;
            jdbc.sql("UPDATE tournament SET current_round = ?, version = version + 1 WHERE id = ?").params(next, tournament.id()).update();
            createRound(load(tournament.id()), next);
        } else {
            finish(tournament.id());
        }
    }

    private void createRound(TournamentRow tournament, int number) {
        List<ParticipantRow> participants = participants(tournament.id());
        Map<Long, ParticipantRow> byUser = byUser(participants);
        TournamentFormat format = tournament.formatEnum();
        List<Pairing> pairings;
        if (format.isRoundRobin()) {
            List<Long> seeded = participants.stream().map(ParticipantRow::userId).toList();
            pairings = RoundRobinScheduler.schedule(seeded, format == TournamentFormat.DOUBLE_ROUND_ROBIN).get(number - 1);
        } else {
            pairings = SwissPairer.pair(swissState(tournament.id(), participants));
        }
        long roundId = roundId(tournament.id(), "MAIN", number);
        jdbc.sql("UPDATE tournament_round SET status = 'ACTIVE', started_at = now() WHERE id = ?").param(roundId).update();
        int board = 1;
        // Real games first, the bye last, so board numbers read naturally.
        List<Pairing> ordered = new ArrayList<>(pairings);
        ordered.sort(Comparator.comparing(Pairing::isBye));
        for (Pairing pairing : ordered) {
            ParticipantRow white = byUser.get(pairing.white());
            if (pairing.isBye()) {
                long pairingId = insertPairing(roundId, board++, white.id(), null);
                decide(pairing(pairingId), "BYE", white.id());
                continue;
            }
            ParticipantRow black = byUser.get(pairing.black());
            long pairingId = insertPairing(roundId, board++, white.id(), black.id());
            scheduleGame(tournament, pairingId, pairing.white(), pairing.black(), "MAIN");
        }
        // A round made only of byes (cannot happen with 2+ players, but never leave the tournament stuck).
        completeRoundIfAllDecided(tournament, roundId);
    }

    private void completeRoundIfAllDecided(TournamentRow tournament, long roundId) {
        int open = jdbc.sql("SELECT count(*) FROM tournament_pairing WHERE round_id = ? AND result IS NULL")
                .param(roundId).query(Integer.class).single();
        if (open == 0) {
            completeRoundIfDone(load(tournament.id()));
        }
    }

    /** Swiss state per player, rebuilt from the decided pairings of earlier rounds. */
    private List<SwissPlayer> swissState(long tournamentId, List<ParticipantRow> participants) {
        Map<Long, Long> userOf = new HashMap<>();
        participants.forEach(p -> userOf.put(p.id(), p.userId()));
        Map<Long, Double> score = new HashMap<>();
        Map<Long, List<Long>> opponents = new HashMap<>();
        Map<Long, List<Color>> colours = new HashMap<>();
        Map<Long, Boolean> hadBye = new HashMap<>();
        for (ParticipantRow p : participants) {
            score.put(p.userId(), 0.0);
            opponents.put(p.userId(), new ArrayList<>());
            colours.put(p.userId(), new ArrayList<>());
        }
        for (PairingRow pairing : decidedPairings(tournamentId)) {
            long white = userOf.get(pairing.whiteParticipantId());
            if (pairing.blackParticipantId() == null) {
                score.merge(white, 1.0, Double::sum);
                hadBye.put(white, true);
                continue;
            }
            long black = userOf.get(pairing.blackParticipantId());
            GameResult result = GameResult.valueOf(pairing.result());
            score.merge(white, result.whiteScore(), Double::sum);
            score.merge(black, result.blackScore(), Double::sum);
            opponents.get(white).add(black);
            opponents.get(black).add(white);
            colours.get(white).add(Color.WHITE);
            colours.get(black).add(Color.BLACK);
        }
        return participants.stream().map(p -> new SwissPlayer(p.userId(), p.seedRating(), score.get(p.userId()),
                opponents.get(p.userId()), colours.get(p.userId()), hadBye.getOrDefault(p.userId(), false))).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Knockout
    // ------------------------------------------------------------------------------------------

    /**
     * Decides what a finished knockout game means: someone advances, or the tie-break rule
     * schedules the next game of the same pairing.
     */
    private void resolveKnockoutGame(TournamentRow tournament, PairingRow pairing, Game game) {
        if (pairing.advancingParticipantId() != null) {
            return;
        }
        Map<Long, Long> participantOf = new HashMap<>();
        participants(tournament.id()).forEach(p -> participantOf.put(p.userId(), p.id()));
        GameResult result = game.getResult();
        Long winnerUser;
        if (game.getStage().equals("PLAYOFF")) {
            // A blitz playoff is a two-game match: only the total after both games counts.
            winnerUser = playoffWinner(pairing, game);
        } else {
            winnerUser = switch (result) {
                case WHITE_WIN -> game.getWhiteId();
                case BLACK_WIN -> game.getBlackId();
                case DRAW -> game.getStage().equals("ARMAGEDDON") ? game.getBlackId() : null;   // draw odds for Black
            };
        }
        if (winnerUser != null) {
            long pairingWhiteUser = userOfParticipant(pairing.whiteParticipantId());
            decide(pairing, winnerUser == pairingWhiteUser ? "WHITE_WIN" : "BLACK_WIN", participantOf.get(winnerUser));
            return;
        }
        // Level: the next tie-break game, colours reversed from the game just played.
        DrawResolution rule = DrawResolution.valueOf(tournament.drawResolution());
        long nextWhite = game.getBlackId();
        long nextBlack = game.getWhiteId();
        String stage;
        if (game.getStage().equals("PLAYOFF")) {
            stage = playoffGames(pairing.id()).size() < 2 ? "PLAYOFF" : "ARMAGEDDON";
        } else {
            stage = switch (rule) {
                case REPLAY -> "MAIN";
                case BLITZ_PLAYOFF -> "PLAYOFF";
                case ARMAGEDDON -> "ARMAGEDDON";
            };
        }
        if (stage.equals("ARMAGEDDON")) {
            // The higher seed takes Black and its draw odds.
            int whiteSeed = seedOf(tournament.id(), nextWhite);
            int blackSeed = seedOf(tournament.id(), nextBlack);
            if (whiteSeed < blackSeed) {
                long swap = nextWhite;
                nextWhite = nextBlack;
                nextBlack = swap;
            }
        }
        scheduleGame(tournament, pairing.id(), nextWhite, nextBlack, stage);
    }

    /** After both blitz playoff games: the player with more points, or null if they are still level. */
    private Long playoffWinner(PairingRow pairing, Game justFinished) {
        List<GameRow> playoff = playoffGames(pairing.id());
        if (playoff.size() < 2) {
            return null;
        }
        Map<Long, Double> points = new HashMap<>();
        for (GameRow game : playoff) {
            GameResult result = GameResult.valueOf(game.result());
            points.merge(game.whiteId(), result.whiteScore(), Double::sum);
            points.merge(game.blackId(), result.blackScore(), Double::sum);
        }
        long a = justFinished.getWhiteId();
        long b = justFinished.getBlackId();
        int compare = Double.compare(points.getOrDefault(a, 0.0), points.getOrDefault(b, 0.0));
        return compare == 0 ? null : compare > 0 ? a : b;
    }

    private List<GameRow> playoffGames(long pairingId) {
        return jdbc.sql("""
                        SELECT id, white_id, black_id, status, result, stage, category, rated, played_at FROM game
                        WHERE tournament_pairing_id = ? AND stage = 'PLAYOFF' AND status = 'FINISHED' ORDER BY id""")
                .param(pairingId).query(GameRow.class).list();
    }

    /**
     * Rebuilds the bracket from the seeds and the decided pairings, then creates pairings for every
     * match that has become playable. Byes resolve inside the bracket and never get a pairing.
     */
    private void advanceBracket(TournamentRow tournament) {
        List<ParticipantRow> participants = participants(tournament.id());
        Map<Long, Long> userOf = new HashMap<>();
        Map<Long, ParticipantRow> byUser = byUser(participants);
        participants.forEach(p -> userOf.put(p.id(), p.userId()));
        EliminationBracket bracket = reader.rebuildBracket(tournament, participants);

        Map<Integer, Long> existing = new HashMap<>();
        jdbc.sql("""
                        SELECT p.board_number, p.id FROM tournament_pairing p JOIN tournament_round r ON r.id = p.round_id
                        WHERE r.tournament_id = ?""")
                .param(tournament.id()).query((rs, row) -> existing.put(rs.getInt(1), rs.getLong(2))).list();
        int highestRound = tournament.currentRound();
        for (BracketMatch match : bracket.readyMatches()) {
            if (existing.containsKey(match.id())) {
                continue;
            }
            long roundId = roundId(tournament.id(), match.side().name(), match.round());
            jdbc.sql("UPDATE tournament_round SET status = 'ACTIVE', started_at = COALESCE(started_at, now()) WHERE id = ?")
                    .param(roundId).update();
            long pairingId = insertPairing(roundId, match.id(), byUser.get(match.playerA()).id(), byUser.get(match.playerB()).id());
            scheduleGame(tournament, pairingId, match.playerA(), match.playerB(), "MAIN");
            highestRound = Math.max(highestRound, match.round());
        }
        jdbc.sql("UPDATE tournament SET current_round = ?, version = version + 1 WHERE id = ?").params(highestRound, tournament.id()).update();
        jdbc.sql("""
                UPDATE tournament_round r SET status = 'FINISHED', finished_at = COALESCE(finished_at, now())
                WHERE r.tournament_id = ? AND r.status <> 'FINISHED'
                  AND EXISTS (SELECT 1 FROM tournament_pairing p WHERE p.round_id = r.id)
                  AND NOT EXISTS (SELECT 1 FROM tournament_pairing p WHERE p.round_id = r.id AND p.advancing_participant_id IS NULL)""")
                .param(tournament.id()).update();
        if (bracket.isFinished()) {
            finish(tournament.id());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private void scheduleGame(TournamentRow tournament, long pairingId, long whiteUser, long blackUser, String stage) {
        TimeCategory category = TimeCategory.valueOf(tournament.category());
        String timeControl = tournament.timeControl();
        boolean rated = tournament.rated();
        if (stage.equals("PLAYOFF")) {
            category = TimeCategory.BLITZ;
            timeControl = PLAYOFF_TIME_CONTROL;
        } else if (stage.equals("ARMAGEDDON")) {
            // Unequal time and draw odds make it a poor measure of strength: never rated.
            category = TimeCategory.BLITZ;
            timeControl = null;
            rated = false;
        }
        long gameId = gameService.createScheduled(pairingId, whiteUser, blackUser, category, timeControl, rated, stage,
                tournament.createdBy());
        jdbc.sql("UPDATE tournament_pairing SET game_id = ?, version = version + 1 WHERE id = ?").params(gameId, pairingId).update();
    }

    private void decide(PairingRow pairing, String result, Long advancingParticipant) {
        jdbc.sql("""
                        UPDATE tournament_pairing SET result = ?, advancing_participant_id = ?, decided_at = now(), version = version + 1
                        WHERE id = ?""")
                .params(result, advancingParticipant, pairing.id()).update();
    }

    private void finish(long tournamentId) {
        jdbc.sql("UPDATE tournament SET status = 'FINISHED', ends_at = COALESCE(ends_at, now()), version = version + 1 WHERE id = ?")
                .param(tournamentId).update();
    }

    /** Orders players by their rating in the tournament's category (then by user id) and stores the seeds. */
    private void reseed(TournamentRow tournament, List<ParticipantRow> participants) {
        List<ParticipantRow> withRatings = participants.stream()
                .map(p -> new ParticipantRow(p.id(), p.userId(), p.seed(), ratingOf(p.userId(), tournament.category())))
                .sorted(Comparator.comparingInt(ParticipantRow::seedRating).reversed().thenComparingLong(ParticipantRow::userId))
                .toList();
        // Two passes so the unique (tournament, seed) constraint never sees a half-renumbered table.
        jdbc.sql("UPDATE tournament_participant SET seed = -seed WHERE tournament_id = ?").param(tournament.id()).update();
        int seed = 1;
        for (ParticipantRow p : withRatings) {
            jdbc.sql("UPDATE tournament_participant SET seed = ?, seed_rating = ?, version = version + 1 WHERE id = ?")
                    .params(seed++, p.seedRating(), p.id()).update();
        }
    }

    private int ratingOf(long userId, String category) {
        return jdbc.sql("SELECT rating FROM player_rating WHERE user_id = ? AND category = ?").params(userId, category)
                .query(Integer.class).optional().orElse(EloCalculator.INITIAL_RATING);
    }

    private int seedOf(long tournamentId, long userId) {
        return jdbc.sql("SELECT seed FROM tournament_participant WHERE tournament_id = ? AND user_id = ?")
                .params(tournamentId, userId).query(Integer.class).single();
    }

    private long userOfParticipant(long participantId) {
        return jdbc.sql("SELECT user_id FROM tournament_participant WHERE id = ?").param(participantId).query(Long.class).single();
    }

    private long roundId(long tournamentId, String bracket, int number) {
        jdbc.sql("""
                        INSERT INTO tournament_round (tournament_id, bracket, round_number) VALUES (?, ?, ?)
                        ON CONFLICT (tournament_id, bracket, round_number) DO NOTHING""")
                .params(tournamentId, bracket, number).update();
        return jdbc.sql("SELECT id FROM tournament_round WHERE tournament_id = ? AND bracket = ? AND round_number = ?")
                .params(tournamentId, bracket, number).query(Long.class).single();
    }

    private int nextBoard(long roundId) {
        return jdbc.sql("SELECT COALESCE(max(board_number), 0) + 1 FROM tournament_pairing WHERE round_id = ?")
                .param(roundId).query(Integer.class).single();
    }

    private long insertPairing(long roundId, int board, Long whiteParticipant, Long blackParticipant) {
        return jdbc.sql("""
                        INSERT INTO tournament_pairing (round_id, board_number, white_participant_id, black_participant_id)
                        VALUES (?, ?, ?, ?) RETURNING id""")
                .params(roundId, board, whiteParticipant, blackParticipant).query(Long.class).single();
    }

    private PairingRow pairing(long pairingId) {
        return jdbc.sql("""
                        SELECT id, round_id, board_number, white_participant_id, black_participant_id, game_id, result,
                            advancing_participant_id FROM tournament_pairing WHERE id = ?""")
                .param(pairingId).query(PairingRow.class).single();
    }

    private List<PairingRow> decidedPairings(long tournamentId) {
        return jdbc.sql("""
                        SELECT p.id, p.round_id, p.board_number, p.white_participant_id, p.black_participant_id, p.game_id,
                            p.result, p.advancing_participant_id
                        FROM tournament_pairing p JOIN tournament_round r ON r.id = p.round_id
                        WHERE r.tournament_id = ? AND p.result IS NOT NULL ORDER BY r.round_number, p.board_number""")
                .param(tournamentId).query(PairingRow.class).list();
    }

    List<ParticipantRow> participants(long tournamentId) {
        return jdbc.sql("SELECT id, user_id, seed, seed_rating FROM tournament_participant WHERE tournament_id = ? ORDER BY seed")
                .param(tournamentId).query(ParticipantRow.class).list();
    }

    private static Map<Long, ParticipantRow> byUser(List<ParticipantRow> participants) {
        Map<Long, ParticipantRow> map = new LinkedHashMap<>();
        participants.forEach(p -> map.put(p.userId(), p));
        return map;
    }

    private TournamentRow lock(long tournamentId) {
        return jdbc.sql("SELECT * FROM tournament WHERE id = ? FOR UPDATE").param(tournamentId).query(TournamentRow.class)
                .optional().orElseThrow(() -> new ChessException(ErrorCode.TOURNAMENT_NOT_FOUND));
    }

    private TournamentRow load(long tournamentId) {
        return jdbc.sql("SELECT * FROM tournament WHERE id = ?").param(tournamentId).query(TournamentRow.class).single();
    }

    private static void requireStatus(TournamentRow tournament, String status) {
        if (!tournament.status().equals(status)) {
            throw new ChessException(ErrorCode.TOURNAMENT_INVALID_STATE);
        }
    }

    private void changed(long tournamentId) {
        events.publishEvent(new TournamentChangedEvent(tournamentId));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Optional<Instant> instant(Timestamp timestamp) {
        return Optional.ofNullable(timestamp).map(Timestamp::toInstant);
    }
}
