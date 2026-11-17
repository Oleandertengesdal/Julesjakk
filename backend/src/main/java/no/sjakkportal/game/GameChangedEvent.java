package no.sjakkportal.game;

/**
 * Published whenever a game's visible state changes (move, result, confirmation, abort...).
 * Live-update broadcasting listens for it after commit, so spectators never see a change that was rolled back.
 */
public record GameChangedEvent(long gameId) {
}
