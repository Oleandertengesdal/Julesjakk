package no.sjakkportal.rating;

/** Rating deltas for one game. Not necessarily zero-sum: the players may have different K-factors. */
public record RatingChange(int whiteDelta, int blackDelta) {
}
