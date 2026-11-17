package no.sjakkportal.tournament;

/** Something visible about a tournament changed; live-update broadcasting listens for it after commit. */
public record TournamentChangedEvent(long tournamentId) {
}
