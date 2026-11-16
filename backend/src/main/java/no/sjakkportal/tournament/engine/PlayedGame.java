package no.sjakkportal.tournament.engine;

import no.sjakkportal.rating.GameResult;

/** A finished tournament game. A bye is {@code black == null} with result {@code WHITE_WIN}. */
public record PlayedGame(long white, Long black, GameResult result) {

    public static PlayedGame bye(long player) {
        return new PlayedGame(player, null, GameResult.WHITE_WIN);
    }

    public boolean isBye() {
        return black == null;
    }
}
