package no.sjakkportal.game;

public enum GameStatus {
    SCHEDULED,
    /** Being entered move by move. */
    LIVE,
    /** A result has been reported by one player and awaits the opponent's (or an admin's) confirmation. */
    PENDING,
    REJECTED,
    /** Confirmed; ratings and standings include it. */
    FINISHED,
    ABORTED,
    /** Confirmed once, then annulled by an admin: kept for the record, counted nowhere. */
    ANNULLED
}
