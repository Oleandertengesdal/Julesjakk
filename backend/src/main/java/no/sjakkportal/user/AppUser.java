package no.sjakkportal.user;

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
import no.sjakkportal.security.Role;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private boolean managed;

    @Column(name = "chess_com_username")
    private String chessComUsername;

    @Column(name = "lichess_username")
    private String lichessUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.PLAYER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AppUser() {
    }

    public static AppUser withLogin(String username, String displayName, String passwordHash, Role role) {
        AppUser user = new AppUser();
        user.username = username;
        user.displayName = displayName;
        user.passwordHash = passwordHash;
        user.role = role;
        return user;
    }

    /** A player without credentials; an admin registers and confirms games on their behalf. */
    public static AppUser managed(String username, String displayName) {
        AppUser user = new AppUser();
        user.username = username;
        user.displayName = displayName;
        user.managed = true;
        return user;
    }

    /**
     * Sets a new password and invalidates every access token issued before now.
     *
     * @param mustChange true for an admin-issued temporary password
     */
    public void changePassword(String newHash, boolean mustChange) {
        this.passwordHash = newHash;
        this.mustChangePassword = mustChange;
        this.managed = false;
        this.tokenVersion++;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isManaged() {
        return managed;
    }

    public String getChessComUsername() {
        return chessComUsername;
    }

    public String getLichessUsername() {
        return lichessUsername;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }
}
