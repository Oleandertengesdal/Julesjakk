package no.sjakkportal.live;

import java.util.List;
import java.util.Optional;
import no.sjakkportal.game.GameService;
import no.sjakkportal.game.GameView;
import no.sjakkportal.rating.EloCalculator;
import no.sjakkportal.tournament.TournamentService;
import no.sjakkportal.tournament.TournamentViews;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides what the living-room screen shows, in this order: the featured game while it is live,
 * the most recently active live game, the featured game after it has ended, the latest finished game.
 */
@Service
public class TvService {

    public record TvState(GameView game, String reason, Integer whiteRating, Integer blackRating, TournamentTicker tournament) {
    }

    public record TournamentTicker(long id, String name, String format, List<TournamentViews.StandingView> standings) {
    }

    private final JdbcClient jdbc;
    private final GameService gameService;
    private final TournamentService tournamentService;

    public TvService(JdbcClient jdbc, GameService gameService, TournamentService tournamentService) {
        this.jdbc = jdbc;
        this.gameService = gameService;
        this.tournamentService = tournamentService;
    }

    @Transactional(readOnly = true)
    public TvState current() {
        String reason = "FEATURED";
        Optional<Long> gameId = id("SELECT id FROM game WHERE featured AND status = 'LIVE' LIMIT 1");
        if (gameId.isEmpty()) {
            reason = "LIVE";
            gameId = id("""
                    SELECT g.id FROM game g WHERE g.status = 'LIVE'
                    ORDER BY GREATEST(g.played_at, COALESCE((SELECT max(m.played_at) FROM game_move m WHERE m.game_id = g.id),
                        g.played_at)) DESC, g.id DESC LIMIT 1""");
        }
        if (gameId.isEmpty()) {
            reason = "FEATURED";
            gameId = id("SELECT id FROM game WHERE featured AND status IN ('FINISHED', 'PENDING') LIMIT 1");
        }
        if (gameId.isEmpty()) {
            reason = "RECENT";
            gameId = id("SELECT id FROM game WHERE status IN ('FINISHED', 'PENDING') ORDER BY COALESCE(finished_at, played_at) DESC, id DESC LIMIT 1");
        }
        GameView game = gameId.map(gameService::get).orElse(null);
        Integer whiteRating = game == null ? null : rating(game.white().id(), game.category());
        Integer blackRating = game == null ? null : rating(game.black().id(), game.category());
        return new TvState(game, game == null ? null : reason, whiteRating, blackRating, ticker());
    }

    private TournamentTicker ticker() {
        return id("SELECT id FROM tournament WHERE status = 'ACTIVE' ORDER BY starts_at DESC NULLS LAST, id DESC LIMIT 1")
                .map(tournamentService::detail)
                .map(t -> new TournamentTicker(t.id(), t.name(), t.format(), t.standings().stream().limit(8).toList()))
                .orElse(null);
    }

    private Optional<Long> id(String sql) {
        return jdbc.sql(sql).query(Long.class).optional();
    }

    private int rating(long userId, String category) {
        return jdbc.sql("SELECT rating FROM player_rating WHERE user_id = ? AND category = ?").params(userId, category)
                .query(Integer.class).optional().orElse(EloCalculator.INITIAL_RATING);
    }
}
