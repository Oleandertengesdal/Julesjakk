package no.sjakkportal.tournament;

public enum TournamentFormat {
    ROUND_ROBIN, DOUBLE_ROUND_ROBIN, SWISS, SINGLE_ELIMINATION, DOUBLE_ELIMINATION, ARENA;

    public boolean isKnockout() {
        return this == SINGLE_ELIMINATION || this == DOUBLE_ELIMINATION;
    }

    public boolean isRoundRobin() {
        return this == ROUND_ROBIN || this == DOUBLE_ROUND_ROBIN;
    }
}
