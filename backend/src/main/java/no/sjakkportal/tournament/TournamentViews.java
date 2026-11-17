package no.sjakkportal.tournament;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Response shapes for tournaments. */
public final class TournamentViews {

    private TournamentViews() {
    }

    public record UserRef(long id, String username, String displayName) {
    }

    public record Summary(long id, String name, String format, String status, String category, String timeControl,
                          int participantCount, Instant startsAt, Instant endsAt, Instant createdAt) {
    }

    public record Participant(long id, UserRef user, int seed, int seedRating) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GameSummary(long id, String status, String result, String stage, String category, boolean rated,
                              UserRef white, UserRef black, Instant playedAt) {
    }

    public record PairingView(long id, int board, UserRef white, UserRef black, boolean bye, String result,
                              UserRef advancing, GameSummary currentGame, List<GameSummary> games) {
    }

    public record RoundView(long id, String bracket, int number, String status, List<PairingView> pairings) {
    }

    /**
     * One standings line. {@code streak}/{@code onFire} are arena-only; {@code buchholz}, {@code sonnebornBerger}
     * and {@code headToHead} apply to round-robin and Swiss; {@code losses} decides knockout order.
     */
    public record StandingView(int rank, UserRef user, double score, int played, int wins, int draws, int losses,
                               double headToHead, double buchholz, double sonnebornBerger, int streak, boolean onFire,
                               boolean eliminated) {
    }

    public record BracketMatchView(int id, String side, int round, UserRef playerA, UserRef playerB, UserRef winner) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(long id, String name, String description, String format, String status, String category,
                         String timeControl, boolean rated, String drawResolution, Integer totalRounds, int currentRound,
                         Instant startsAt, Instant endsAt, Instant createdAt, List<Participant> participants,
                         List<RoundView> rounds, List<StandingView> standings, List<BracketMatchView> bracket,
                         UserRef champion) {
    }
}
