package no.sjakkportal.rating;

/** What one player gains or loses for each possible outcome, e.g. {@code +12 / 0 / -12}. */
public record RatingStakes(int onWin, int onDraw, int onLoss) {
}
