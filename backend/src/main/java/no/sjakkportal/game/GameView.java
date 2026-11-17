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
                       String pgn, List<Move> moves) {

    public record Player(long id, String username, String displayName) {
    }

    public record Move(int ply, String san, String uci, String fen) {
    }
}
