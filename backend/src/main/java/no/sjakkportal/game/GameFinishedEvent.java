package no.sjakkportal.game;

/**
 * Published inside the transaction that makes a game official (status FINISHED). Listeners that must
 * stay consistent with the game, such as tournament progression, handle it synchronously in that
 * same transaction: if they fail, the confirmation rolls back too.
 */
public record GameFinishedEvent(Game game) {
}
