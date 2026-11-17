package no.sjakkportal.user;

import java.util.Map;

/** What the API reveals about a user. Credentials and token state have no field here by design. */
public record UserView(long id, String username, String displayName, String role, boolean managed,
                       boolean mustChangePassword, String chessComUsername, String lichessUsername,
                       Map<String, RatingView> ratings) {

    public record RatingView(int rating, int ratedGames) {
    }
}
