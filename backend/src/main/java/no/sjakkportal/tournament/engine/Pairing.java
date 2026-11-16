package no.sjakkportal.tournament.engine;

/** One board in a round. {@code black == null} means White has a bye. */
public record Pairing(long white, Long black) {

    public static Pairing bye(long player) {
        return new Pairing(player, null);
    }

    public boolean isBye() {
        return black == null;
    }

    public boolean involves(long player) {
        return white == player || (black != null && black == player);
    }
}
