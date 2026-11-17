package no.sjakkportal.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "invite_code")
public class InviteCode {

    public enum Status {
        ACTIVE, EXPIRED, EXHAUSTED, REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;

    @Column(name = "max_uses", updatable = false)
    private Integer maxUses;

    /** Written only by the atomic claim statement in {@link InviteService}, never through the entity. */
    @Column(name = "use_count", nullable = false, insertable = false, updatable = false)
    private int useCount;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", insertable = false, updatable = false)
    private Instant revokedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected InviteCode() {
    }

    public InviteCode(String code, String note, long createdBy, Integer maxUses, Instant expiresAt) {
        this.code = code;
        this.note = note;
        this.createdBy = createdBy;
        this.maxUses = maxUses;
        this.expiresAt = expiresAt;
    }

    /** Revocation wins over expiry, which wins over exhaustion: the most deliberate reason is shown. */
    public Status status(Instant now) {
        if (revokedAt != null) {
            return Status.REVOKED;
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return Status.EXPIRED;
        }
        if (maxUses != null && useCount >= maxUses) {
            return Status.EXHAUSTED;
        }
        return Status.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getNote() {
        return note;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public int getUseCount() {
        return useCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
