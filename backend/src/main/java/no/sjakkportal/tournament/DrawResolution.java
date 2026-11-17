package no.sjakkportal.tournament;

/** How a drawn knockout game is settled. */
public enum DrawResolution {
    /** Play the game again with colours reversed, until someone wins. */
    REPLAY,
    /** Two blitz games (colours alternate); if still level, one armageddon game. */
    BLITZ_PLAYOFF,
    /** One armageddon game straight away: a draw sends Black through. */
    ARMAGEDDON
}
