package no.sjakkportal.stats;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.game.GameService;
import no.sjakkportal.rating.EloCalculator;
import no.sjakkportal.rating.TimeCategory;
import no.sjakkportal.stats.StatsViews.Badge;
import no.sjakkportal.stats.StatsViews.CategoryRating;
import no.sjakkportal.stats.StatsViews.HeadToHead;
import no.sjakkportal.stats.StatsViews.LeaderboardRow;
import no.sjakkportal.stats.StatsViews.Matchup;
import no.sjakkportal.stats.StatsViews.OpponentStat;
import no.sjakkportal.stats.StatsViews.PlayerStats;
import no.sjakkportal.stats.StatsViews.RatingPoint;
import no.sjakkportal.stats.StatsViews.Record;
import no.sjakkportal.stats.StatsViews.Streak;
import no.sjakkportal.stats.StatsViews.UserRef;
import no.sjakkportal.tournament.TournamentService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Player statistics, computed from confirmed (FINISHED) games on each request. With a family's worth
 * of games this is cheap, and it means statistics can never drift out of step with the games.
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

    private static final int NEMESIS_MIN_GAMES = 3;
    private static final int GIANT_KILLER_GAP = 200;

    /** One finished game from the player's point of view. */
    private record PlayerGame(long gameId, String category, boolean white, long opponent, double score, boolean rated,
                              Integer myRatingBefore, Integer opponentRatingBefore, Timestamp playedAt) {
    }

    private final JdbcClient jdbc;
    private final GameService gameService;
    private final TournamentService tournamentService;

    public StatsService(JdbcClient jdbc, GameService gameService, TournamentService tournamentService) {
        this.jdbc = jdbc;
        this.gameService = gameService;
        this.tournamentService = tournamentService;
    }

    public PlayerStats stats(long userId, TimeCategory category) {
        UserRef user = user(userId);
        List<PlayerGame> all = games(userId);
        List<PlayerGame> games = category == null ? all : all.stream().filter(g -> g.category().equals(category.name())).toList();

        int[] total = new int[3];
        int[] white = new int[3];
        int[] black = new int[3];
        int longest = 0;
        int run = 0;
        for (PlayerGame game : games) {
            int outcome = outcome(game.score());
            total[outcome]++;
            (game.white() ? white : black)[outcome]++;
            run = outcome == 0 ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        Streak current = null;
        if (!games.isEmpty()) {
            int last = outcome(games.get(games.size() - 1).score());
            int length = 0;
            for (int i = games.size() - 1; i >= 0 && outcome(games.get(i).score()) == last; i--) {
                length++;
            }
            current = new Streak(new String[] {"WIN", "DRAW", "LOSS"}[last], length);
        }
        List<String> form = new ArrayList<>();
        for (int i = games.size() - 1; i >= 0 && form.size() < 5; i--) {
            form.add(new String[] {"W", "D", "L"}[outcome(games.get(i).score())]);
        }

        Map<Long, double[]> byOpponent = new LinkedHashMap<>();   // games, score
        for (PlayerGame game : games) {
            double[] tally = byOpponent.computeIfAbsent(game.opponent(), k -> new double[2]);
            tally[0]++;
            tally[1] += game.score();
        }
        OpponentStat nemesis = byOpponent.entrySet().stream().filter(e -> e.getValue()[0] >= NEMESIS_MIN_GAMES)
                .filter(e -> e.getValue()[1] / e.getValue()[0] < 0.5)
                .min(Comparator.<Map.Entry<Long, double[]>>comparingDouble(e -> e.getValue()[1] / e.getValue()[0])
                        .thenComparingDouble(e -> -e.getValue()[0]))
                .map(e -> new OpponentStat(user(e.getKey()), (int) e.getValue()[0], e.getValue()[1])).orElse(null);
        OpponentStat favourite = byOpponent.entrySet().stream()
                .max(Comparator.<Map.Entry<Long, double[]>>comparingDouble(e -> e.getValue()[0]).thenComparingDouble(e -> e.getValue()[1]))
                .map(e -> new OpponentStat(user(e.getKey()), (int) e.getValue()[0], e.getValue()[1])).orElse(null);

        List<Long> ids = games.stream().map(PlayerGame::gameId).toList();
        double accuracySum = 0;
        int analysed = 0;
        int blunders = 0;
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : jdbc.sql("""
                            SELECT a.game_id, g.white_id, a.white_accuracy, a.black_accuracy, a.white_blunders, a.black_blunders
                            FROM game_analysis a JOIN game g ON g.id = a.game_id WHERE a.game_id IN (:ids)""")
                    .param("ids", ids).query().listOfRows()) {
                boolean isWhite = ((Number) row.get("white_id")).longValue() == userId;
                accuracySum += ((Number) row.get(isWhite ? "white_accuracy" : "black_accuracy")).doubleValue();
                blunders += ((Number) row.get(isWhite ? "white_blunders" : "black_blunders")).intValue();
                analysed++;
            }
        }
        return new PlayerStats(user, category == null ? "ALL" : category.name(), games.size(), total[0], total[1], total[2],
                record(white), record(black), current, longest, form, ratings(userId),
                analysed == 0 ? null : Math.round(accuracySum / analysed * 10) / 10.0, analysed,
                analysed == 0 ? 0 : Math.round((double) blunders / analysed * 100) / 100.0, nemesis, favourite, badges(userId, all));
    }

    public List<RatingPoint> ratingHistory(long userId, TimeCategory category) {
        user(userId);
        List<RatingPoint> points = new ArrayList<>();
        List<RatingPoint> changes = jdbc.sql("""
                        SELECT h.game_id, g.played_at, h.rating_before, h.rating_after FROM rating_history h JOIN game g ON g.id = h.game_id
                        WHERE h.user_id = ? AND h.category = ? ORDER BY h.id""")
                .params(userId, category.name())
                .query((rs, row) -> {
                    if (row == 0) {
                        points.add(new RatingPoint(null, rs.getTimestamp("played_at").toInstant(), rs.getInt("rating_before")));
                    }
                    return new RatingPoint(rs.getLong("game_id"), rs.getTimestamp("played_at").toInstant(), rs.getInt("rating_after"));
                }).list();
        if (points.isEmpty()) {
            points.add(new RatingPoint(null, java.time.Instant.now(), EloCalculator.INITIAL_RATING));
        }
        points.addAll(changes);
        return points;
    }

    public List<LeaderboardRow> leaderboard(TimeCategory category) {
        List<LeaderboardRow> rows = new ArrayList<>();
        jdbc.sql("""
                        SELECT u.id, u.username, u.display_name, r.rating, r.rated_games,
                            GREATEST(r.rating, COALESCE((SELECT max(h.rating_after) FROM rating_history h
                                WHERE h.user_id = u.id AND h.category = r.category), r.rating)) AS peak
                        FROM player_rating r JOIN app_user u ON u.id = r.user_id
                        WHERE r.category = ? AND r.rated_games > 0 AND u.enabled ORDER BY r.rating DESC, u.username""")
                .param(category.name())
                .query((rs, row) -> {
                    int rank = rows.isEmpty() || rows.get(rows.size() - 1).rating() != rs.getInt("rating") ? row + 1 : rows.get(rows.size() - 1).rank();
                    rows.add(new LeaderboardRow(rank, new UserRef(rs.getLong("id"), rs.getString("username"), rs.getString("display_name")),
                            rs.getInt("rating"), rs.getInt("peak"), rs.getInt("rated_games")));
                    return null;
                }).list();
        return rows;
    }

    public Matchup matchup(long a, long b, TimeCategory category) {
        if (a == b) {
            throw new ChessException(ErrorCode.GAME_SAME_PLAYER);
        }
        UserRef playerA = user(a);
        UserRef playerB = user(b);
        int[] h2h = new int[3];
        for (PlayerGame game : games(a)) {
            if (game.opponent() == b) {
                h2h[outcome(game.score())]++;
            }
        }
        List<GameService.GameListItem> recent = gameService.list(a, null, 100).stream()
                .filter(g -> g.status().equals("FINISHED") && (g.white().id() == b || g.black().id() == b)).limit(10).toList();
        int ratingA = rating(a, category);
        int ratingB = rating(b, category);
        int gamesA = ratedGames(a, category);
        int gamesB = ratedGames(b, category);
        return new Matchup(playerA, playerB, category.name(), ratingA, ratingB, new HeadToHead(h2h[0] + h2h[1] + h2h[2], h2h[0], h2h[1], h2h[2]),
                recent, form(a, category), form(b, category), probabilities(ratingA, ratingB),
                EloCalculator.stakes(ratingA, gamesA, ratingB), EloCalculator.stakes(ratingB, gamesB, ratingA));
    }

    public Matchup preview(long gameId) {
        Map<String, Object> game = jdbc.sql("SELECT white_id, black_id, category FROM game WHERE id = ?").param(gameId).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> new ChessException(ErrorCode.GAME_NOT_FOUND));
        return matchup(((Number) game.get("white_id")).longValue(), ((Number) game.get("black_id")).longValue(),
                TimeCategory.valueOf((String) game.get("category")));
    }

    private static StatsViews.Probabilities probabilities(int ratingA, int ratingB) {
        var p = EloCalculator.probabilities(ratingA, ratingB);
        return new StatsViews.Probabilities(p.whiteWin(), p.draw(), p.blackWin());
    }

    // ------------------------------------------------------------------------------------------

    private List<Badge> badges(long userId, List<PlayerGame> all) {
        List<Badge> badges = new ArrayList<>();
        int wins = 0;
        int run = 0;
        boolean streak = false;
        boolean giantKiller = false;
        Set<String> ratedCategories = new java.util.HashSet<>();
        for (PlayerGame game : all) {
            if (game.score() == 1.0) {
                wins++;
                run++;
                if (game.myRatingBefore() != null && game.opponentRatingBefore() != null
                        && game.opponentRatingBefore() - game.myRatingBefore() >= GIANT_KILLER_GAP) {
                    giantKiller = true;
                }
            } else {
                run = 0;
            }
            streak |= run >= 5;
            if (game.rated()) {
                ratedCategories.add(game.category());
            }
        }
        add(badges, wins > 0, "FIRST_WIN", "Første seier", "Vant sitt første parti.");
        add(badges, all.size() >= 10, "TEN_GAMES", "Ti partier", "Har spilt ti partier.");
        add(badges, all.size() >= 50, "FIFTY_GAMES", "Femti partier", "Har spilt femti partier.");
        add(badges, streak, "WIN_STREAK_5", "Seiersrekke", "Vant fem partier på rad.");
        add(badges, giantKiller, "GIANT_KILLER", "Kjempedreper", "Slo en motstander med minst 200 poeng høyere rating.");
        add(badges, ratedCategories.size() == TimeCategory.values().length, "ALL_ROUNDER", "Allsidig",
                "Har ratede partier i lyn-, hurtig- og langsjakk.");
        Map<String, Object> analysis = jdbc.sql("""
                        SELECT coalesce(max(CASE WHEN g.white_id = :u THEN a.white_accuracy ELSE a.black_accuracy END), 0) AS best,
                               coalesce(sum(CASE WHEN g.white_id = :u THEN a.white_brilliancies ELSE a.black_brilliancies END), 0) AS brilliant
                        FROM game_analysis a JOIN game g ON g.id = a.game_id
                        WHERE g.status = 'FINISHED' AND (g.white_id = :u OR g.black_id = :u)""")
                .param("u", userId).query().singleRow();
        add(badges, ((Number) analysis.get("best")).doubleValue() >= 90, "SHARPSHOOTER", "Skarpskytter",
                "Spilte et parti med minst 90 % nøyaktighet.");
        add(badges, ((Number) analysis.get("brilliant")).intValue() > 0, "BRILLIANT", "Briljant", "Fant et briljant trekk.");
        List<Long> finished = jdbc.sql("""
                        SELECT t.id FROM tournament t JOIN tournament_participant p ON p.tournament_id = t.id
                        WHERE t.status = 'FINISHED' AND p.user_id = ?""").param(userId).query(Long.class).list();
        boolean champion = finished.stream().map(tournamentService::detail)
                .anyMatch(t -> t.champion() != null && t.champion().id() == userId);
        add(badges, champion, "TOURNAMENT_WINNER", "Turneringsvinner", "Vant en turnering.");
        return badges;
    }

    private static void add(List<Badge> badges, boolean earned, String code, String title, String description) {
        if (earned) {
            badges.add(new Badge(code, title, description));
        }
    }

    private List<String> form(long userId, TimeCategory category) {
        List<PlayerGame> games = games(userId).stream().filter(g -> g.category().equals(category.name())).toList();
        List<String> form = new ArrayList<>();
        for (int i = games.size() - 1; i >= 0 && form.size() < 5; i--) {
            form.add(new String[] {"W", "D", "L"}[outcome(games.get(i).score())]);
        }
        return form;
    }

    /** Finished games in the order they were played. */
    private List<PlayerGame> games(long userId) {
        return jdbc.sql("""
                        SELECT id, category, white_id, black_id, result, rated, white_rating_before, black_rating_before, played_at
                        FROM game WHERE status = 'FINISHED' AND (white_id = :u OR black_id = :u) ORDER BY played_at, id""")
                .param("u", userId)
                .query((rs, row) -> {
                    boolean white = rs.getLong("white_id") == userId;
                    String result = rs.getString("result");
                    double score = result.equals("DRAW") ? 0.5 : result.equals(white ? "WHITE_WIN" : "BLACK_WIN") ? 1.0 : 0.0;
                    Integer whiteBefore = (Integer) rs.getObject("white_rating_before");
                    Integer blackBefore = (Integer) rs.getObject("black_rating_before");
                    return new PlayerGame(rs.getLong("id"), rs.getString("category"), white,
                            white ? rs.getLong("black_id") : rs.getLong("white_id"), score, rs.getBoolean("rated"),
                            white ? whiteBefore : blackBefore, white ? blackBefore : whiteBefore, rs.getTimestamp("played_at"));
                }).list();
    }

    private Map<String, CategoryRating> ratings(long userId) {
        Map<String, CategoryRating> ratings = new LinkedHashMap<>();
        Map<String, int[]> stored = new HashMap<>();
        jdbc.sql("""
                        SELECT r.category, r.rating, r.rated_games,
                            GREATEST(r.rating, COALESCE((SELECT max(h.rating_after) FROM rating_history h
                                WHERE h.user_id = r.user_id AND h.category = r.category), r.rating), ?) AS peak
                        FROM player_rating r WHERE r.user_id = ?""")
                .params(EloCalculator.INITIAL_RATING, userId)
                .query((rs, row) -> stored.put(rs.getString("category"),
                        new int[] {rs.getInt("rating"), rs.getInt("peak"), rs.getInt("rated_games")})).list();
        for (TimeCategory category : EnumSet.allOf(TimeCategory.class)) {
            int[] r = stored.getOrDefault(category.name(), new int[] {EloCalculator.INITIAL_RATING, EloCalculator.INITIAL_RATING, 0});
            ratings.put(category.name(), new CategoryRating(r[0], r[1], r[2]));
        }
        return ratings;
    }

    private int rating(long userId, TimeCategory category) {
        return jdbc.sql("SELECT rating FROM player_rating WHERE user_id = ? AND category = ?").params(userId, category.name())
                .query(Integer.class).optional().orElse(EloCalculator.INITIAL_RATING);
    }

    private int ratedGames(long userId, TimeCategory category) {
        return jdbc.sql("SELECT rated_games FROM player_rating WHERE user_id = ? AND category = ?").params(userId, category.name())
                .query(Integer.class).optional().orElse(0);
    }

    private UserRef user(long userId) {
        return jdbc.sql("SELECT id, username, display_name FROM app_user WHERE id = ?").param(userId)
                .query((rs, row) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> new ChessException(ErrorCode.USER_NOT_FOUND));
    }

    private static int outcome(double score) {
        return score == 1.0 ? 0 : score == 0.5 ? 1 : 2;
    }

    private static Record record(int[] r) {
        return new Record(r[0] + r[1] + r[2], r[0], r[1], r[2]);
    }
}
