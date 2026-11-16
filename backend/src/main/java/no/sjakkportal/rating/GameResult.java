package no.sjakkportal.rating;

public enum GameResult {
    WHITE_WIN(1.0),
    BLACK_WIN(0.0),
    DRAW(0.5);

    private final double whiteScore;

    GameResult(double whiteScore) {
        this.whiteScore = whiteScore;
    }

    /** Points scored by White: 1, ½ or 0. */
    public double whiteScore() {
        return whiteScore;
    }

    public double blackScore() {
        return 1.0 - whiteScore;
    }
}
