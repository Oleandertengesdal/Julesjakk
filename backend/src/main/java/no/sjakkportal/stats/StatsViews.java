package no.sjakkportal.stats;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import no.sjakkportal.game.GameService.GameListItem;
import no.sjakkportal.rating.RatingStakes;

public final class StatsViews {

    private StatsViews() {
    }

    public record UserRef(long id, String username, String displayName) {
    }

    public record Record(int games, int wins, int draws, int losses) {
    }

    public record Streak(String type, int length) {
    }

    public record CategoryRating(int rating, int peak, int ratedGames) {
    }

    public record OpponentStat(UserRef user, int games, double score) {
    }

    public record Badge(String code, String title, String description) {
    }

    /** {@code averageAccuracy}, {@code nemesis} and {@code favouriteOpponent} are null when there is nothing to base them on. */
    public record PlayerStats(UserRef user, String category, int games, int wins, int draws, int losses, Record asWhite,
                              Record asBlack, Streak currentStreak, int longestWinStreak, List<String> recentForm,
                              Map<String, CategoryRating> ratings, Double averageAccuracy, int analysedGames, double blundersPerGame,
                              OpponentStat nemesis, OpponentStat favouriteOpponent, List<Badge> badges) {
    }

    /** @param gameId null for the starting point */
    public record RatingPoint(Long gameId, Instant at, int rating) {
    }

    public record LeaderboardRow(int rank, UserRef user, int rating, int peak, int ratedGames) {
    }

    public record HeadToHead(int games, int aWins, int draws, int bWins) {
    }

    public record Probabilities(double aWin, double draw, double bWin) {
    }

    /** The pre-match card. Probabilities and stakes treat player A as White. */
    public record Matchup(UserRef playerA, UserRef playerB, String category, int ratingA, int ratingB, HeadToHead headToHead,
                          List<GameListItem> recentGames, List<String> formA, List<String> formB, Probabilities probabilities,
                          RatingStakes stakesA, RatingStakes stakesB) {
    }
}
