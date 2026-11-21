package no.sjakkportal.game;

import no.sjakkportal.rating.GameResult;

/**
 * Published inside the correction transaction, before anything is saved, so a listener (the
 * tournament service) can refuse the change by throwing, or bring its own records in line.
 *
 * @param newResult the corrected result, or null when the game is being annulled
 */
public record GameCorrectedEvent(Game game, GameResult newResult) {

    public boolean annulled() {
        return newResult == null;
    }
}
