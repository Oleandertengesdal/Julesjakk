package no.sjakkportal.rating;

import java.util.Objects;

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

    /**
     * Share of the "contested" probability mass that ends in a draw. Elo itself says nothing about
     * draws; this constant gives equal players a 20 % draw chance, which fits casual play.
     */
    private static final double DRAW_SHARE = 0.2;

    private EloCalculator() {
    }

    /** Expected score of player A against player B, in (0, 1). */
    public static double expectedScore(int ratingA, int ratingB) {
        return 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0));
    }

    public static int kFactor(int ratedGames, int rating) {
        if (ratedGames < 0) {
            throw new IllegalArgumentException("ratedGames must not be negative");
        }
        if (ratedGames < PROVISIONAL_GAMES) {
            return 40;
        }
        return rating >= MASTER_RATING ? 10 : 20;
    }

    /** Rating change for both players given their ratings and rated-game counts before the game. */
    public static RatingChange rate(int whiteRating, int whiteRatedGames,
                                    int blackRating, int blackRatedGames, GameResult result) {
        Objects.requireNonNull(result, "result");
        int whiteK = kFactor(whiteRatedGames, whiteRating);
        int blackK = kFactor(blackRatedGames, blackRating);
        double whiteExpected = expectedScore(whiteRating, blackRating);

        int whiteDelta = roundHalfAwayFromZero(whiteK * (result.whiteScore() - whiteExpected));
        // With equal K the exchange is exactly zero-sum; computing Black separately could differ by a
        // point through floating-point noise in (1 - E).
        int blackDelta = whiteK == blackK
                ? -whiteDelta
                : roundHalfAwayFromZero(blackK * (result.blackScore() - (1.0 - whiteExpected)));
        return new RatingChange(applyFloor(whiteRating, whiteDelta), applyFloor(blackRating, blackDelta));
    }

    /** What the player stands to gain or lose against this opponent. */
    public static RatingStakes stakes(int playerRating, int playerRatedGames, int opponentRating) {
        int k = kFactor(playerRatedGames, playerRating);
        double expected = expectedScore(playerRating, opponentRating);
        return new RatingStakes(
                applyFloor(playerRating, roundHalfAwayFromZero(k * (1.0 - expected))),
                applyFloor(playerRating, roundHalfAwayFromZero(k * (0.5 - expected))),
                applyFloor(playerRating, roundHalfAwayFromZero(k * (0.0 - expected))));
    }

    /**
     * Win/draw/loss forecast consistent with the Elo expected score: {@code P(win) + P(draw)/2 = E}.
     * The draw probability is a fixed share of the smaller of the two expectations, so it peaks
     * between equal players and can never push a win probability below zero.
     */
    public static OutcomeProbability probabilities(int whiteRating, int blackRating) {
        double expected = expectedScore(whiteRating, blackRating);
        double draw = 2.0 * DRAW_SHARE * Math.min(expected, 1.0 - expected);
        double whiteWin = expected - draw / 2.0;
        double blackWin = 1.0 - whiteWin - draw;
        return new OutcomeProbability(whiteWin, draw, Math.max(0.0, blackWin));
    }

    /** {@code Math.round} rounds -2.5 to -2; ratings need symmetric rounding. */
    private static int roundHalfAwayFromZero(double value) {
        return value < 0 ? -(int) Math.round(-value) : (int) Math.round(value);
    }

    private static int applyFloor(int rating, int delta) {
        return Math.max(RATING_FLOOR, rating + delta) - rating;
    }
}
