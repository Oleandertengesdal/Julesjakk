package no.sjakkportal.tournament;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.rating.GameResult;
import no.sjakkportal.tournament.TournamentService.ParticipantRow;
import no.sjakkportal.tournament.TournamentService.TournamentRow;
import no.sjakkportal.tournament.TournamentViews.BracketMatchView;
import no.sjakkportal.tournament.TournamentViews.Detail;
import no.sjakkportal.tournament.TournamentViews.GameSummary;
import no.sjakkportal.tournament.TournamentViews.PairingView;
import no.sjakkportal.tournament.TournamentViews.Participant;
import no.sjakkportal.tournament.TournamentViews.RoundView;
import no.sjakkportal.tournament.TournamentViews.StandingView;
import no.sjakkportal.tournament.TournamentViews.Summary;
import no.sjakkportal.tournament.TournamentViews.UserRef;
import no.sjakkportal.tournament.engine.ArenaScoring;
import no.sjakkportal.tournament.engine.BracketMatch;
import no.sjakkportal.tournament.engine.EliminationBracket;
import no.sjakkportal.tournament.engine.PlayedGame;
import no.sjakkportal.tournament.engine.StandingRow;
import no.sjakkportal.tournament.engine.StandingsCalculator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Builds tournament views from the database. Read-only; never changes state. */
@Component
public class TournamentReader {

    private record PairingRow(long id, long roundId, int boardNumber, Long whiteParticipantId, Long blackParticipantId,
                              Long gameId, String result, Long advancingParticipantId) {
    }

    private record GameRow(long id, Long tournamentPairingId, long whiteId, long blackId, String status, String result,
                           String stage, String category, boolean rated, java.sql.Timestamp playedAt) {
    }

    private final JdbcClient jdbc;

    public TournamentReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Summary> list() {
        return jdbc.sql("""
                        SELECT t.id, t.name, t.format, t.status, t.category, t.time_control, t.starts_at, t.ends_at, t.created_at,
                            (SELECT count(*) FROM tournament_participant p WHERE p.tournament_id = t.id) AS participant_count
                        FROM tournament t ORDER BY t.created_at DESC, t.id DESC""")
                .query((rs, row) -> new Summary(rs.getLong("id"), rs.getString("name"), rs.getString("format"), rs.getString("status"),
                        rs.getString("category"), rs.getString("time_control"), rs.getInt("participant_count"),
                        TournamentService.instant(rs.getTimestamp("starts_at")).orElse(null),
                        TournamentService.instant(rs.getTimestamp("ends_at")).orElse(null), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public Detail detail(long tournamentId) {
        TournamentRow t = jdbc.sql("SELECT * FROM tournament WHERE id = ?").param(tournamentId).query(TournamentRow.class)
                .optional().orElseThrow(() -> new ChessException(ErrorCode.TOURNAMENT_NOT_FOUND));
        TournamentFormat format = t.formatEnum();
        List<ParticipantRow> participantRows = jdbc.sql(
                        "SELECT id, user_id, seed, seed_rating FROM tournament_participant WHERE tournament_id = ? ORDER BY seed")
                .param(tournamentId).query(ParticipantRow.class).list();
        Map<Long, UserRef> users = users(tournamentId);
        Map<Long, Long> userOf = new HashMap<>();
        participantRows.forEach(p -> userOf.put(p.id(), p.userId()));
        List<Participant> participants = participantRows.stream()
                .map(p -> new Participant(p.id(), users.get(p.userId()), p.seed(), p.seedRating())).toList();

        List<PairingRow> pairings = jdbc.sql("""
                        SELECT p.id, p.round_id, p.board_number, p.white_participant_id, p.black_participant_id, p.game_id, p.result,
                            p.advancing_participant_id
                        FROM tournament_pairing p JOIN tournament_round r ON r.id = p.round_id
                        WHERE r.tournament_id = ? ORDER BY p.board_number""")
                .param(tournamentId).query(PairingRow.class).list();
        List<GameRow> games = jdbc.sql("""
                        SELECT g.id, g.tournament_pairing_id, g.white_id, g.black_id, g.status, g.result, g.stage, g.category, g.rated,
                            g.played_at
                        FROM game g JOIN tournament_pairing p ON p.id = g.tournament_pairing_id
                        JOIN tournament_round r ON r.id = p.round_id WHERE r.tournament_id = ? ORDER BY g.played_at, g.id""")
                .param(tournamentId).query(GameRow.class).list();
        Map<Long, List<GameSummary>> gamesByPairing = new HashMap<>();
        Map<Long, GameSummary> gameById = new HashMap<>();
        for (GameRow game : games) {
            GameSummary summary = new GameSummary(game.id(), game.status(), game.result(), game.stage(), game.category(), game.rated(),
                    users.get(game.whiteId()), users.get(game.blackId()), game.playedAt().toInstant());
            gameById.put(game.id(), summary);
            gamesByPairing.computeIfAbsent(game.tournamentPairingId(), k -> new ArrayList<>()).add(summary);
        }

        Map<Long, List<PairingView>> pairingsByRound = new HashMap<>();
        for (PairingRow p : pairings) {
            UserRef white = p.whiteParticipantId() == null ? null : users.get(userOf.get(p.whiteParticipantId()));
            UserRef black = p.blackParticipantId() == null ? null : users.get(userOf.get(p.blackParticipantId()));
            UserRef advancing = p.advancingParticipantId() == null ? null : users.get(userOf.get(p.advancingParticipantId()));
            pairingsByRound.computeIfAbsent(p.roundId(), k -> new ArrayList<>()).add(new PairingView(p.id(), p.boardNumber(), white, black,
                    p.blackParticipantId() == null, p.result(), advancing, p.gameId() == null ? null : gameById.get(p.gameId()),
                    gamesByPairing.getOrDefault(p.id(), List.of())));
        }
        List<RoundView> rounds = jdbc.sql("""
                        SELECT id, bracket, round_number, status FROM tournament_round WHERE tournament_id = ?
                        ORDER BY CASE bracket WHEN 'MAIN' THEN 0 WHEN 'WINNERS' THEN 1 WHEN 'LOSERS' THEN 2 ELSE 3 END, round_number""")
                .param(tournamentId)
                .query((rs, row) -> new RoundView(rs.getLong("id"), rs.getString("bracket"), rs.getInt("round_number"), rs.getString("status"),
                        pairingsByRound.getOrDefault(rs.getLong("id"), List.of())))
                .list();

        List<StandingView> standings;
        List<BracketMatchView> bracketView = null;
        UserRef champion = null;
        if (format.isKnockout() && !t.status().equals("DRAFT")) {
            EliminationBracket bracket = rebuildBracket(t, participantRows);
            bracketView = bracket.matches().stream().map(m -> new BracketMatchView(m.id(), m.side().name(), m.round(),
                    ref(users, m.playerA()), ref(users, m.playerB()), ref(users, m.winner()))).toList();
            standings = knockoutStandings(format, participantRows, users, bracket);
            champion = bracket.champion().map(users::get).orElse(null);
        } else if (format == TournamentFormat.ARENA) {
            standings = arenaStandings(participantRows, users, games);
        } else {
            standings = scoreStandings(format, participantRows, users, pairings, userOf);
        }
        if (champion == null && t.status().equals("FINISHED") && !standings.isEmpty() && !format.isKnockout()) {
            champion = standings.get(0).user();
        }
        return new Detail(t.id(), t.name(), t.description(), t.format(), t.status(), t.category(), t.timeControl(), t.rated(),
                t.drawResolution(), t.totalRounds(), t.currentRound(), TournamentService.instant(t.startsAt()).orElse(null),
                TournamentService.instant(t.endsAt()).orElse(null), t.createdAt().toInstant(), participants, rounds, standings,
                bracketView, champion);
    }

    /** Replays every decided knockout pairing (board number = bracket match id) onto a fresh bracket. */
    EliminationBracket rebuildBracket(TournamentRow t, List<ParticipantRow> participants) {
        List<Long> seeds = participants.stream().sorted(Comparator.comparingInt(ParticipantRow::seed)).map(ParticipantRow::userId).toList();
        EliminationBracket bracket = t.formatEnum() == TournamentFormat.DOUBLE_ELIMINATION
                ? EliminationBracket.doubleElimination(seeds) : EliminationBracket.single(seeds);
        Map<Long, Long> userOf = new HashMap<>();
        participants.forEach(p -> userOf.put(p.id(), p.userId()));
        Map<Integer, Long> winners = new HashMap<>();
        jdbc.sql("""
                        SELECT p.board_number, p.advancing_participant_id FROM tournament_pairing p
                        JOIN tournament_round r ON r.id = p.round_id
                        WHERE r.tournament_id = ? AND p.advancing_participant_id IS NOT NULL""")
                .param(t.id()).query((rs, row) -> winners.put(rs.getInt(1), userOf.get(rs.getLong(2)))).list();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (BracketMatch match : bracket.readyMatches()) {
                Long winner = winners.get(match.id());
                if (winner != null) {
                    bracket.report(match.id(), winner);
                    progress = true;
                }
            }
        }
        return bracket;
    }

    private List<StandingView> scoreStandings(TournamentFormat format, List<ParticipantRow> participants, Map<Long, UserRef> users,
                                              List<PairingRow> pairings, Map<Long, Long> userOf) {
        List<PlayedGame> played = new ArrayList<>();
        for (PairingRow p : pairings) {
            if (p.result() == null) {
                continue;
            }
            long white = userOf.get(p.whiteParticipantId());
            if (p.blackParticipantId() == null) {
                // Swiss gives a point for a bye; in a round-robin it is simply a round off.
                if (format == TournamentFormat.SWISS) {
                    played.add(PlayedGame.bye(white));
                }
                continue;
            }
            played.add(new PlayedGame(white, userOf.get(p.blackParticipantId()), GameResult.valueOf(p.result())));
        }
        List<Long> players = participants.stream().map(ParticipantRow::userId).toList();
        List<StandingView> rows = new ArrayList<>();
        for (StandingRow row : StandingsCalculator.standings(players, played)) {
            rows.add(new StandingView(row.rank(), users.get(row.player()), row.score(), row.played(), row.wins(), row.draws(),
                    row.losses(), row.headToHead(), row.buchholz(), row.sonnebornBerger(), 0, false, false));
        }
        return rows;
    }

    private List<StandingView> arenaStandings(List<ParticipantRow> participants, Map<Long, UserRef> users, List<GameRow> games) {
        Map<Long, ArenaScoring.ArenaState> state = new LinkedHashMap<>();
        Map<Long, int[]> record = new HashMap<>();   // wins, draws, losses
        for (ParticipantRow p : participants) {
            state.put(p.userId(), new ArenaScoring.ArenaState(0, 0));
            record.put(p.userId(), new int[3]);
        }
        for (GameRow game : games) {
            if (!game.status().equals("FINISHED") || !state.containsKey(game.whiteId()) || !state.containsKey(game.blackId())) {
                continue;
            }
            GameResult result = GameResult.valueOf(game.result());
            ArenaScoring.Outcome white = result == GameResult.WHITE_WIN ? ArenaScoring.Outcome.WIN
                    : result == GameResult.DRAW ? ArenaScoring.Outcome.DRAW : ArenaScoring.Outcome.LOSS;
            ArenaScoring.Outcome black = result == GameResult.BLACK_WIN ? ArenaScoring.Outcome.WIN
                    : result == GameResult.DRAW ? ArenaScoring.Outcome.DRAW : ArenaScoring.Outcome.LOSS;
            state.put(game.whiteId(), ArenaScoring.apply(state.get(game.whiteId()), white));
            state.put(game.blackId(), ArenaScoring.apply(state.get(game.blackId()), black));
            record.get(game.whiteId())[white.ordinal()]++;
            record.get(game.blackId())[black.ordinal()]++;
        }
        List<Long> order = new ArrayList<>(state.keySet());
        order.sort(Comparator.<Long>comparingInt(u -> -state.get(u).points())
                .thenComparingInt(u -> -record.get(u)[0]).thenComparingLong(u -> u));
        List<StandingView> rows = new ArrayList<>();
        int rank = 0;
        for (int i = 0; i < order.size(); i++) {
            long user = order.get(i);
            if (i == 0 || state.get(order.get(i - 1)).points() != state.get(user).points()) {
                rank = i + 1;
            }
            int[] r = record.get(user);
            ArenaScoring.ArenaState s = state.get(user);
            rows.add(new StandingView(rank, users.get(user), s.points(), r[0] + r[1] + r[2], r[0], r[1], r[2], 0, 0, 0, s.streak(),
                    s.onFire(), false));
        }
        return rows;
    }

    private List<StandingView> knockoutStandings(TournamentFormat format, List<ParticipantRow> participants, Map<Long, UserRef> users,
                                                 EliminationBracket bracket) {
        Map<Long, Integer> wins = new HashMap<>();
        for (BracketMatch match : bracket.matches()) {
            if (match.winner() != null && match.playerA() != null && match.playerB() != null) {
                wins.merge(match.winner(), 1, Integer::sum);
            }
        }
        int livesLost = format == TournamentFormat.DOUBLE_ELIMINATION ? 2 : 1;
        Long champion = bracket.champion().orElse(null);
        List<ParticipantRow> order = new ArrayList<>(participants);
        order.sort(Comparator.<ParticipantRow>comparingInt(p -> p.userId() == (champion == null ? -1 : champion) ? 0 : 1)
                .thenComparingInt(p -> bracket.losses(p.userId()))
                .thenComparingInt(p -> -wins.getOrDefault(p.userId(), 0))
                .thenComparingInt(ParticipantRow::seed));
        List<StandingView> rows = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            ParticipantRow p = order.get(i);
            int w = wins.getOrDefault(p.userId(), 0);
            int l = bracket.losses(p.userId());
            rows.add(new StandingView(i + 1, users.get(p.userId()), w, w + l, w, 0, l, 0, 0, 0, 0, false, l >= livesLost));
        }
        return rows;
    }

    private Map<Long, UserRef> users(long tournamentId) {
        Map<Long, UserRef> users = new HashMap<>();
        jdbc.sql("""
                        SELECT u.id, u.username, u.display_name FROM app_user u
                        WHERE u.id IN (SELECT user_id FROM tournament_participant WHERE tournament_id = :t)
                           OR u.id IN (SELECT g.white_id FROM game g JOIN tournament_pairing p ON p.id = g.tournament_pairing_id
                                       JOIN tournament_round r ON r.id = p.round_id WHERE r.tournament_id = :t)
                           OR u.id IN (SELECT g.black_id FROM game g JOIN tournament_pairing p ON p.id = g.tournament_pairing_id
                                       JOIN tournament_round r ON r.id = p.round_id WHERE r.tournament_id = :t)""")
                .param("t", tournamentId)
                .query((rs, row) -> users.put(rs.getLong(1), new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)))).list();
        return users;
    }

    private static UserRef ref(Map<Long, UserRef> users, Long id) {
        return id == null ? null : users.get(id);
    }
}
