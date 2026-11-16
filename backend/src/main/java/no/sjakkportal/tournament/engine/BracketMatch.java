package no.sjakkportal.tournament.engine;

/**
 * A match in an elimination bracket. Players are {@code null} until the feeding matches are decided.
 * A match with exactly one player and no pending feeder is a bye and is resolved automatically.
 */
public record BracketMatch(int id, BracketSide side, int round, Long playerA, Long playerB, Long winner) {

    public boolean isReady() {
        return playerA != null && playerB != null && winner == null;
    }

    public boolean isDecided() {
        return winner != null;
    }
}
