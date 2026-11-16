package no.sjakkportal.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EloCalculatorTest {

    private static final int ESTABLISHED = 50;

    @Nested
    class ExpectedScore {

        @Test
        void equalRatingsGiveOneHalf() {
            assertThat(EloCalculator.expectedScore(1200, 1200)).isEqualTo(0.5);
        }

        @Test
        void fourHundredPointsIsTenToOneOdds() {
            assertThat(EloCalculator.expectedScore(1600, 1200)).isCloseTo(10.0 / 11.0, within(1e-9));
            assertThat(EloCalculator.expectedScore(1200, 1600)).isCloseTo(1.0 / 11.0, within(1e-9));
        }

        @ParameterizedTest
        @CsvSource({"100,4000", "1200,1201", "1850,1432", "4000,100"})
        void bothPlayersExpectationsSumToOne(int a, int b) {
            assertThat(EloCalculator.expectedScore(a, b) + EloCalculator.expectedScore(b, a))
                    .isCloseTo(1.0, within(1e-12));
        }

        @Test
        void staysStrictlyInsideZeroAndOneForExtremeGaps() {
            assertThat(EloCalculator.expectedScore(4000, 100)).isGreaterThan(0.0).isLessThan(1.0);
            assertThat(EloCalculator.expectedScore(100, 4000)).isGreaterThan(0.0).isLessThan(1.0);
        }
    }

    @Nested
    class KFactor {

        @ParameterizedTest
        @CsvSource({"0,1200,40", "19,1200,40", "20,1200,20", "500,2399,20", "500,2400,10", "500,2800,10"})
        void followsTheDocumentedBands(int ratedGames, int rating, int expectedK) {
            assertThat(EloCalculator.kFactor(ratedGames, rating)).isEqualTo(expectedK);
        }

        @Test
        void provisionalPeriodWinsOverTheMasterBand() {
            assertThat(EloCalculator.kFactor(3, 2500)).isEqualTo(40);
        }

        @Test
        void rejectsNegativeGameCounts() {
            assertThatThrownBy(() -> EloCalculator.kFactor(-1, 1200)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Rate {

        @Test
        void winBetweenEqualEstablishedPlayersMovesTenPoints() {
            assertThat(EloCalculator.rate(1200, ESTABLISHED, 1200, ESTABLISHED, GameResult.WHITE_WIN))
                    .isEqualTo(new RatingChange(10, -10));
            assertThat(EloCalculator.rate(1200, ESTABLISHED, 1200, ESTABLISHED, GameResult.BLACK_WIN))
                    .isEqualTo(new RatingChange(-10, 10));
        }

        @Test
        void drawBetweenEqualPlayersChangesNothing() {
            assertThat(EloCalculator.rate(1500, ESTABLISHED, 1500, ESTABLISHED, GameResult.DRAW))
                    .isEqualTo(new RatingChange(0, 0));
        }

        @Test
        void upsetIsRewardedMoreThanAnExpectedWin() {
            // E(1200 v 1600) = 1/11, so 20 * (1 - 1/11) = 18.18
            assertThat(EloCalculator.rate(1200, ESTABLISHED, 1600, ESTABLISHED, GameResult.WHITE_WIN))
                    .isEqualTo(new RatingChange(18, -18));
            // The favourite winning: 20 * (1 - 10/11) = 1.82
            assertThat(EloCalculator.rate(1600, ESTABLISHED, 1200, ESTABLISHED, GameResult.WHITE_WIN))
                    .isEqualTo(new RatingChange(2, -2));
        }

        @Test
        void drawAgainstStrongerPlayerGainsPoints() {
            // 20 * (0.5 - 1/11) = 8.18
            assertThat(EloCalculator.rate(1200, ESTABLISHED, 1600, ESTABLISHED, GameResult.DRAW))
                    .isEqualTo(new RatingChange(8, -8));
        }

        @Test
        void eachPlayerUsesTheirOwnKFactor() {
            // White is provisional (K=40), Black established (K=20).
            assertThat(EloCalculator.rate(1200, 0, 1200, ESTABLISHED, GameResult.WHITE_WIN))
                    .isEqualTo(new RatingChange(20, -10));
        }

        /**
         * Naive Math.round() on each side breaks symmetry on .5 boundaries
         * (round(2.5) = 3 but round(-2.5) = -2). With equal K the exchange must be exactly zero-sum.
         */
        @Test
        void equalKFactorsAreAlwaysExactlyZeroSum() {
            for (int white = 800; white <= 2300; white += 7) {
                for (int black = 800; black <= 2300; black += 13) {
                    for (GameResult result : GameResult.values()) {
                        RatingChange change = EloCalculator.rate(white, ESTABLISHED, black, ESTABLISHED, result);
                        assertThat(change.whiteDelta() + change.blackDelta())
                                .as("%d v %d, %s", white, black, result)
                                .isZero();
                    }
                }
            }
        }

        @Test
        void winnerNeverLosesPointsAndLoserNeverGainsThem() {
            for (int gap = 0; gap <= 3000; gap += 50) {
                RatingChange favouriteWins = EloCalculator.rate(1000 + gap, ESTABLISHED, 1000, ESTABLISHED, GameResult.WHITE_WIN);
                assertThat(favouriteWins.whiteDelta()).as("gap %d", gap).isGreaterThanOrEqualTo(0);
                assertThat(favouriteWins.blackDelta()).as("gap %d", gap).isLessThanOrEqualTo(0);
            }
        }

        @Test
        void ratingCannotDropBelowTheFloor() {
            RatingChange change = EloCalculator.rate(104, ESTABLISHED, 100, ESTABLISHED, GameResult.BLACK_WIN);
            assertThat(104 + change.whiteDelta()).isEqualTo(EloCalculator.RATING_FLOOR);
        }

        @Test
        void rejectsMissingResult() {
            assertThatThrownBy(() -> EloCalculator.rate(1200, 0, 1200, 0, null))
                    .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }
    }

    @Nested
    class Stakes {

        @Test
        void equalEstablishedPlayersPlayForTenPoints() {
            assertThat(EloCalculator.stakes(1200, ESTABLISHED, 1200)).isEqualTo(new RatingStakes(10, 0, -10));
        }

        @Test
        void underdogHasMoreToWinThanToLose() {
            assertThat(EloCalculator.stakes(1200, ESTABLISHED, 1600)).isEqualTo(new RatingStakes(18, 8, -2));
        }

        @Test
        void stakesAgreeWithTheActualRatingChange() {
            RatingStakes stakes = EloCalculator.stakes(1437, 5, 1512);
            assertThat(stakes.onWin())
                    .isEqualTo(EloCalculator.rate(1437, 5, 1512, ESTABLISHED, GameResult.WHITE_WIN).whiteDelta());
            assertThat(stakes.onLoss())
                    .isEqualTo(EloCalculator.rate(1437, 5, 1512, ESTABLISHED, GameResult.BLACK_WIN).whiteDelta());
        }
    }

    @Nested
    class Probabilities {

        @ParameterizedTest
        @CsvSource({"1200,1200", "1500,1200", "1200,1500", "100,4000", "4000,100", "2000,1999"})
        void formAValidDistributionConsistentWithElo(int white, int black) {
            OutcomeProbability p = EloCalculator.probabilities(white, black);
            assertThat(p.whiteWin()).isBetween(0.0, 1.0);
            assertThat(p.draw()).isBetween(0.0, 1.0);
            assertThat(p.blackWin()).isBetween(0.0, 1.0);
            assertThat(p.whiteWin() + p.draw() + p.blackWin()).isCloseTo(1.0, within(1e-9));
            // A draw is worth half a point, so P(win) + P(draw)/2 must equal the Elo expectation.
            assertThat(p.whiteWin() + p.draw() / 2).isCloseTo(EloCalculator.expectedScore(white, black), within(1e-9));
        }

        @Test
        void equalPlayersAreEquallyLikelyToWinAndCanDraw() {
            OutcomeProbability p = EloCalculator.probabilities(1400, 1400);
            assertThat(p.whiteWin()).isCloseTo(p.blackWin(), within(1e-12));
            assertThat(p.draw()).isGreaterThan(0.0);
        }

        @Test
        void drawsAreMostLikelyBetweenEqualPlayers() {
            double equal = EloCalculator.probabilities(1400, 1400).draw();
            assertThat(EloCalculator.probabilities(1400, 1700).draw()).isLessThan(equal);
            assertThat(EloCalculator.probabilities(1900, 1400).draw()).isLessThan(equal);
        }

        @Test
        void mirroredRatingsMirrorTheForecast() {
            OutcomeProbability p = EloCalculator.probabilities(1350, 1620);
            OutcomeProbability mirrored = EloCalculator.probabilities(1620, 1350);
            assertThat(p.whiteWin()).isCloseTo(mirrored.blackWin(), within(1e-12));
            assertThat(p.draw()).isCloseTo(mirrored.draw(), within(1e-12));
        }
    }
}
