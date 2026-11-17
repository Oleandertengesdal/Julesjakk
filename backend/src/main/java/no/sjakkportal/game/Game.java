package no.sjakkportal.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import no.sjakkportal.chess.Termination;
import no.sjakkportal.rating.GameResult;
import no.sjakkportal.rating.TimeCategory;

@Entity
@Table(name = "game")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "white_id", nullable = false, updatable = false)
    long whiteId;

    @Column(name = "black_id", nullable = false, updatable = false)
    long blackId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    TimeCategory category;

    @Column(name = "registered_by")
    Long registeredBy;

    @Column(name = "confirmed_by")
    Long confirmedBy;

    @Column(name = "confirmed_at")
    Instant confirmedAt;

    @Column(name = "rejection_reason")
    String rejectionReason;

    @Enumerated(EnumType.STRING)
    GameResult result;

    @Enumerated(EnumType.STRING)
    Termination termination;

    @Column(nullable = false, updatable = false)
    String source;

    @Column(name = "external_id", updatable = false)
    String externalId;

    @Column(name = "client_request_id", updatable = false)
    UUID clientRequestId;

    @Column(nullable = false, updatable = false)
    boolean rated;

    @Column(nullable = false)
    boolean featured;

    /** MAIN for ordinary games; PLAYOFF / ARMAGEDDON for knockout tie-breaks. */
    @Column(nullable = false, updatable = false)
    String stage = "MAIN";

    @Column(name = "tournament_pairing_id", updatable = false)
    Long tournamentPairingId;

    @Column(name = "time_control", updatable = false)
    String timeControl;

    @Column(columnDefinition = "text")
    String pgn;

    @Column(name = "initial_fen")
    String initialFen;

    @Column(name = "current_fen")
    String currentFen;

    @Column(name = "ply_count", nullable = false)
    int plyCount;

    String comment;

    @Column(name = "white_rating_before")
    Integer whiteRatingBefore;

    @Column(name = "black_rating_before")
    Integer blackRatingBefore;

    @Column(name = "white_rating_delta")
    Integer whiteRatingDelta;

    @Column(name = "black_rating_delta")
    Integer blackRatingDelta;

    @Column(name = "created_by", nullable = false, updatable = false)
    long createdBy;

    @Column(name = "played_at", nullable = false)
    Instant playedAt;

    @Column(name = "finished_at")
    Instant finishedAt;

    /** Optimistic lock: a second writer working from a stale copy fails instead of overwriting. */
    @Version
    long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt = Instant.now();

    protected Game() {
    }

    Game(long whiteId, long blackId, TimeCategory category, String timeControl, boolean rated, String source, long createdBy) {
        this.whiteId = whiteId;
        this.blackId = blackId;
        this.category = category;
        this.timeControl = timeControl;
        this.rated = rated;
        this.source = source;
        this.createdBy = createdBy;
        this.playedAt = Instant.now();
    }

    boolean hasPlayer(long userId) {
        return whiteId == userId || blackId == userId;
    }

    /** The result becomes official: ratings may now be applied. */
    void confirm(long byUserId) {
        status = GameStatus.FINISHED;
        confirmedBy = byUserId;
        confirmedAt = Instant.now();
        if (finishedAt == null) {
            finishedAt = confirmedAt;
        }
    }

    public Long getId() {
        return id;
    }

    public long getWhiteId() {
        return whiteId;
    }

    public long getBlackId() {
        return blackId;
    }

    public TimeCategory getCategory() {
        return category;
    }

    public GameResult getResult() {
        return result;
    }

    public boolean isRated() {
        return rated;
    }

    public GameStatus getStatus() {
        return status;
    }

    public String getStage() {
        return stage;
    }

    public Long getTournamentPairingId() {
        return tournamentPairingId;
    }

    public void recordRating(int whiteBefore, int whiteDelta, int blackBefore, int blackDelta) {
        whiteRatingBefore = whiteBefore;
        whiteRatingDelta = whiteDelta;
        blackRatingBefore = blackBefore;
        blackRatingDelta = blackDelta;
    }
}
