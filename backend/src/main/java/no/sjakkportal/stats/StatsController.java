package no.sjakkportal.stats;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import no.sjakkportal.game.GameService;
import no.sjakkportal.rating.TimeCategory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class StatsController {

    private final StatsService stats;
    private final GameService games;

    public StatsController(StatsService stats, GameService games) {
        this.stats = stats;
        this.games = games;
    }

    @GetMapping("/api/users/{id}/stats")
    StatsViews.PlayerStats stats(@PathVariable long id, @RequestParam(required = false) TimeCategory category) {
        return stats.stats(id, category);
    }

    @GetMapping("/api/users/{id}/rating-history")
    List<StatsViews.RatingPoint> ratingHistory(@PathVariable long id, @RequestParam TimeCategory category) {
        return stats.ratingHistory(id, category);
    }

    @GetMapping("/api/users/{id}/games")
    List<GameService.GameListItem> userGames(@PathVariable long id, @RequestParam(required = false) Long before,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return games.list(id, before, limit);
    }

    @GetMapping("/api/games")
    List<GameService.GameListItem> recentGames(@RequestParam(required = false) Long before,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return games.list(null, before, limit);
    }

    @GetMapping("/api/leaderboard")
    List<StatsViews.LeaderboardRow> leaderboard(@RequestParam TimeCategory category) {
        return stats.leaderboard(category);
    }

    @GetMapping("/api/matchups")
    StatsViews.Matchup matchup(@RequestParam long a, @RequestParam long b, @RequestParam TimeCategory category) {
        return stats.matchup(a, b, category);
    }

    @GetMapping("/api/games/{id}/preview")
    StatsViews.Matchup preview(@PathVariable long id) {
        return stats.preview(id);
    }
}
