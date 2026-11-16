package no.sjakkportal.tournament.engine;

/**
 * One line of a standings table.
 *
 * @param rank            1-based; players who are tied on every criterion share a rank
 * @param buchholz        sum of all opponents' scores (a bye counts as an opponent scoring 0)
 * @param sonnebornBerger sum of beaten opponents' scores plus half of drawn opponents' scores
 * @param headToHead      points scored against the other players on the same score
 */
public record StandingRow(int rank, long player, double score, int played, int wins, int draws, int losses,
                          double headToHead, double buchholz, double sonnebornBerger) {
}
