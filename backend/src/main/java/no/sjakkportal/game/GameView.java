package no.sjakkportal.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Absent values are omitted, so an unfinished game simply has no {@code result}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameView(long id, String status, String result, String termination, String category, String timeControl,
                       boolean rated, String stage, Long tournamentPairingId, boolean featured, String source, Player white, Player black, Player registeredBy, Player confirmedBy,
                       String rejectionReason, int plyCount, String currentFen, String comment, Instant playedAt,
                       Integer whiteRatingBefore, Integer whiteRatingDelta, Integer blackRatingBefore, Integer blackRatingDelta,
                       String pgn, List<Move> moves, Correction correction, Clock clock) {

    /**
     * The chess clock as last reported by the phone at the board. A viewer shows
     * {@code running}'s time as {@code xMs - (serverNow - updatedAt) - (time since the view arrived)}.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Clock(long initialMs, long incrementMs, long whiteMs, long blackMs, String running, int sequence,
                        Instant updatedAt, Instant serverNow) {
    }

    /** Set when an admin corrected or annulled the game after it was confirmed. */
    public record Correction(String reason, Player by, Instant at) {
    }

    public record Player(long id, String username, String displayName) {
    }

    public record Move(int ply, String san, String uci, String fen) {
    }
}
