package no.sjakkportal.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import no.sjakkportal.auth.AuthService;
import no.sjakkportal.auth.AuthService.ResetMode;
import no.sjakkportal.common.AuditLog;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.text.Sanitizer;
import no.sjakkportal.invite.InviteService;
import no.sjakkportal.invite.InviteView;
import no.sjakkportal.security.CurrentUser;
import no.sjakkportal.user.AppUser;
import no.sjakkportal.user.UserRepository;
import no.sjakkportal.user.UserService;
import no.sjakkportal.user.UserView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything under {@code /api/admin} requires the ADMIN role (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    record CreateInviteRequest(@Size(max = 200) String code, Integer maxUses, Integer expiresInHours, Instant expiresAt,
                               @Size(max = 500) String note) {
    }

    record PasswordResetRequest(@NotNull ResetMode mode) {
    }

    record CreatePlayerRequest(
            @NotBlank @Pattern(regexp = "(?i)[a-z0-9_.-]{3,32}") String username,
            @NotBlank @Size(max = 64) String displayName) {
    }

    private final InviteService inviteService;
    private final AuthService authService;
    private final UserRepository users;
    private final UserService userService;
    private final AuditLog auditLog;

    public AdminController(InviteService inviteService, AuthService authService, UserRepository users, UserService userService,
                           AuditLog auditLog) {
        this.inviteService = inviteService;
        this.authService = authService;
        this.users = users;
        this.userService = userService;
        this.auditLog = auditLog;
    }

    @PostMapping("/invites")
    ResponseEntity<InviteView> createInvite(@Valid @RequestBody CreateInviteRequest body, @AuthenticationPrincipal CurrentUser admin) {
        InviteView invite = inviteService.create(admin.id(), new InviteService.CreateCommand(
                body.code(), body.maxUses(), body.expiresInHours(), body.expiresAt(), body.note()));
        return ResponseEntity.status(HttpStatus.CREATED).body(invite);
    }

    @GetMapping("/invites")
    List<InviteView> listInvites() {
        return inviteService.list();
    }

    @DeleteMapping("/invites/{id}")
    ResponseEntity<Void> revokeInvite(@PathVariable long id, @AuthenticationPrincipal CurrentUser admin) {
        inviteService.revoke(admin.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/password-reset")
    AuthService.ResetIssued resetPassword(@PathVariable long id, @Valid @RequestBody PasswordResetRequest body,
                                          @AuthenticationPrincipal CurrentUser admin) {
        return authService.issueReset(admin.id(), id, body.mode());
    }

    @PostMapping("/users/{id}/disable")
    UserView disable(@PathVariable long id, @AuthenticationPrincipal CurrentUser admin) {
        return authService.setEnabled(admin.id(), id, false);
    }

    @PostMapping("/users/{id}/enable")
    UserView enable(@PathVariable long id, @AuthenticationPrincipal CurrentUser admin) {
        return authService.setEnabled(admin.id(), id, true);
    }

    /** Creates a player without a login, for family members who will not manage an account themselves. */
    @PostMapping("/players")
    @Transactional
    public ResponseEntity<UserView> createManagedPlayer(@Valid @RequestBody CreatePlayerRequest body,
                                                        @AuthenticationPrincipal CurrentUser admin) {
        String username = AuthService.normalizeUsername(body.username());
        String displayName = Sanitizer.plainText(body.displayName(), 64);
        if (displayName.isBlank()) {
            throw new ChessException(ErrorCode.VALIDATION_FAILED);
        }
        if (users.existsByUsername(username)) {
            throw new ChessException(ErrorCode.AUTH_USERNAME_TAKEN);
        }
        AppUser player = users.saveAndFlush(AppUser.managed(username, displayName));
        auditLog.record(admin.id(), "MANAGED_PLAYER_CREATED", "user:" + player.getId(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.toView(player));
    }
}
