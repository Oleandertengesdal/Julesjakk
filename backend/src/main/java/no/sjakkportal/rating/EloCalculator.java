package no.sjakkportal.rating;

import no.sjakkportal.common.NotImplementedYet;

/**
 * Elo rating maths. Pure and stateless; see docs/ARCHITECTURE.md for the formulas.
 *
 * <ul>
 *   <li>Expected score: {@code E = 1 / (1 + 10^((Rb - Ra) / 400))}.</li>
 *   <li>K-factor: 40 for a player's first {@value #PROVISIONAL_GAMES} rated games, then 20,
 *       and 10 once rated {@value #MASTER_RATING} or above.</li>
 *   <li>Ratings never drop below {@value #RATING_FLOOR}.</li>
 * </ul>
 */
public final class EloCalculator {

    public static final int INITIAL_RATING = 1200;
    public static final int RATING_FLOOR = 100;
    public static final int PROVISIONAL_GAMES = 20;
    public static final int MASTER_RATING = 2400;

    private EloCalculator() {
    }

    /** Expected score of player A against player B, in (0, 1). */
    public static double expectedScore(int ratingA, int ratingB) {
        throw new NotImplementedYet();
    }

    public static int kFactor(int ratedGames, int rating) {
        throw new NotImplementedYet();
    }

    /** Rating change for both players given their ratings and rated-game counts before the game. */
    public static RatingChange rate(int whiteRating, int whiteRatedGames,
                                    int blackRating, int blackRatedGames, GameResult result) {
        throw new NotImplementedYet();
    }

    /** What the player stands to gain or lose against this opponent. */
    public static RatingStakes stakes(int playerRating, int playerRatedGames, int opponentRating) {
        throw new NotImplementedYet();
    }

    /** Win/draw/loss forecast consistent with the Elo expected score. */
    public static OutcomeProbability probabilities(int whiteRating, int blackRating) {
        throw new NotImplementedYet();
    }
}
