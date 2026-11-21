package no.sjakkportal.rating;

import java.util.List;
import no.sjakkportal.game.Game;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a confirmed game to both players' ratings in the game's category.
 *
 * <p>Concurrency: both rating rows are locked before they are read, always in ascending user-id
 * order. Locking before reading prevents lost updates when one player finishes two games at once;
 * the fixed order prevents the deadlock of A-v-B and B-v-A being rated simultaneously.
 */
@Service
public class RatingService {

    private record Row(long userId, int rating, int ratedGames) {
    }

    private final JdbcClient jdbc;

    public RatingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Game game) {
        if (!game.isRated() || game.getResult() == null) {
            return;
        }
        String category = game.getCategory().name();
        long first = Math.min(game.getWhiteId(), game.getBlackId());
        long second = Math.max(game.getWhiteId(), game.getBlackId());
        for (long userId : new long[] {first, second}) {
            jdbc.sql("INSERT INTO player_rating (user_id, category) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(userId, category).update();
        }
        List<Row> rows = jdbc.sql("""
                        SELECT user_id, rating, rated_games FROM player_rating
                        WHERE category = ? AND user_id IN (?, ?) ORDER BY user_id FOR UPDATE""")
                .params(category, first, second).query(Row.class).list();
        Row white = rows.get(0).userId() == game.getWhiteId() ? rows.get(0) : rows.get(1);
        Row black = rows.get(0).userId() == game.getBlackId() ? rows.get(0) : rows.get(1);

        RatingChange change = EloCalculator.rate(white.rating(), white.ratedGames(), black.rating(), black.ratedGames(), game.getResult());
        update(white, change.whiteDelta(), category, game.getId());
        update(black, change.blackDelta(), category, game.getId());
        game.recordRating(white.rating(), change.whiteDelta(), black.rating(), change.blackDelta());
    }

    private record RatedGame(long id, long whiteId, long blackId, String result, java.sql.Timestamp ratedAt) {
    }

    private static final class Standing {
        int rating = EloCalculator.INITIAL_RATING;
        int ratedGames;
    }

    /**
     * Replays every confirmed, rated game in a category from the initial rating, in the order the
     * games were originally rated, and rewrites ratings, rating history and each game's rating
     * columns. Used after an admin corrects or annuls a result: Elo depends on order, so a changed
     * game changes every later rating in the category, including players who were not in it.
     *
     * <p>Locks {@code player_rating} before {@code rating_history}, the same order in which
     * {@link #apply} takes its row locks, so a confirmation in progress finishes first.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recompute(TimeCategory category) {
        String c = category.name();
        jdbc.sql("LOCK TABLE player_rating IN EXCLUSIVE MODE").update();
        jdbc.sql("LOCK TABLE rating_history IN EXCLUSIVE MODE").update();
        // Original rating order: the first history row of each game. Games rated before history existed
        // (none today) would fall back to their confirmation time.
        List<RatedGame> games = jdbc.sql("""
                        SELECT g.id, g.white_id, g.black_id, g.result, coalesce(h.first_at, g.confirmed_at, g.played_at) AS rated_at
                        FROM game g
                        LEFT JOIN (SELECT game_id, min(id) AS first_id, min(created_at) AS first_at FROM rating_history GROUP BY game_id) h
                               ON h.game_id = g.id
                        WHERE g.category = ? AND g.status = 'FINISHED' AND g.rated
                        ORDER BY h.first_id NULLS LAST, g.confirmed_at, g.id""")
                .param(c).query(RatedGame.class).list();

        jdbc.sql("DELETE FROM rating_history WHERE category = ?").param(c).update();
        jdbc.sql("""
                        UPDATE game SET white_rating_before = NULL, white_rating_delta = NULL,
                                        black_rating_before = NULL, black_rating_delta = NULL
                        WHERE category = ? AND (status <> 'FINISHED' OR NOT rated)""").param(c).update();

        java.util.Map<Long, Standing> table = new java.util.HashMap<>();
        for (RatedGame g : games) {
            Standing white = table.computeIfAbsent(g.whiteId(), id -> new Standing());
            Standing black = table.computeIfAbsent(g.blackId(), id -> new Standing());
            RatingChange change = EloCalculator.rate(white.rating, white.ratedGames, black.rating, black.ratedGames,
                    GameResult.valueOf(g.result()));
            insertHistory(g.whiteId(), g.id(), c, white.rating, white.rating + change.whiteDelta(), g.ratedAt());
            insertHistory(g.blackId(), g.id(), c, black.rating, black.rating + change.blackDelta(), g.ratedAt());
            jdbc.sql("""
                            UPDATE game SET white_rating_before = ?, white_rating_delta = ?, black_rating_before = ?, black_rating_delta = ?
                            WHERE id = ?""")
                    .params(white.rating, change.whiteDelta(), black.rating, change.blackDelta(), g.id()).update();
            white.rating += change.whiteDelta();
            white.ratedGames++;
            black.rating += change.blackDelta();
            black.ratedGames++;
        }

        jdbc.sql("""
                        UPDATE player_rating SET rating = ?, rated_games = 0, version = version + 1, updated_at = now()
                        WHERE category = ?""").params(EloCalculator.INITIAL_RATING, c).update();
        table.forEach((userId, standing) -> {
            jdbc.sql("INSERT INTO player_rating (user_id, category) VALUES (?, ?) ON CONFLICT DO NOTHING").params(userId, c).update();
            jdbc.sql("UPDATE player_rating SET rating = ?, rated_games = ? WHERE user_id = ? AND category = ?")
                    .params(standing.rating, standing.ratedGames, userId, c).update();
        });
    }

    private void insertHistory(long userId, long gameId, String category, int before, int after, java.sql.Timestamp at) {
        jdbc.sql("""
                        INSERT INTO rating_history (user_id, game_id, category, rating_before, rating_after, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(userId, gameId, category, before, after, at).update();
    }

    private void update(Row row, int delta, String category, long gameId) {
        jdbc.sql("""
                        UPDATE player_rating SET rating = ?, rated_games = rated_games + 1, version = version + 1, updated_at = now()
                        WHERE user_id = ? AND category = ?""")
                .params(row.rating() + delta, row.userId(), category).update();
        // The unique (user_id, game_id) constraint makes a double application fail loudly instead of silently.
        jdbc.sql("INSERT INTO rating_history (user_id, game_id, category, rating_before, rating_after) VALUES (?, ?, ?, ?, ?)")
                .params(row.userId(), gameId, category, row.rating(), row.rating() + delta).update();
    }
}
