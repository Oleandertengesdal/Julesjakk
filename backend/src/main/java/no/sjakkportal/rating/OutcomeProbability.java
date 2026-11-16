package no.sjakkportal.rating;

/** Pre-match forecast. The three values are in [0, 1] and sum to 1. */
public record OutcomeProbability(double whiteWin, double draw, double blackWin) {
}
