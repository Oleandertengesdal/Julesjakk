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
