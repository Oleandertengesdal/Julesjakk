package no.sjakkportal.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import no.sjakkportal.config.SjakkportalProperties;
import no.sjakkportal.security.CurrentUser;
import no.sjakkportal.security.RequestGuards;
import no.sjakkportal.user.UserView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "sjakkportal_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    record RegisterRequest(
            @NotBlank String inviteCode,
            @NotBlank @Pattern(regexp = "(?i)[a-z0-9_.-]{3,32}", message = "3–32 tegn: bokstaver a–z, tall, punktum, bindestrek eller understrek")
            String username,
            @NotBlank @Size(max = 64) String displayName,
            @NotNull @Size(max = 1000) String password) {
    }

    record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 1000) String password) {
    }

    record ChangePasswordRequest(@NotBlank @Size(max = 1000) String currentPassword, @NotNull @Size(max = 1000) String newPassword) {
    }

    record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, @NotNull @Size(max = 1000) String newPassword) {
    }

    record SessionResponse(String accessToken, long expiresIn, UserView user) {
    }

    private final AuthService authService;
    private final RequestGuards guards;
    private final RefreshTokens refreshTokens;
    private final SjakkportalProperties properties;

    public AuthController(AuthService authService, RequestGuards guards, RefreshTokens refreshTokens, SjakkportalProperties properties) {
        this.authService = authService;
        this.guards = guards;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
    }

    @PostMapping("/register")
    ResponseEntity<UserView> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        // Every attempt costs a token, valid code or not: this is what makes codes unguessable in practice.
        guards.limit(guards.limits().invitePolicy(), guards.clientAddress(request));
        UserView user = authService.register(body.inviteCode(), body.username(), body.displayName(), body.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PostMapping("/login")
    ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        String address = guards.clientAddress(request);
        // Per address first (stops spraying one password over many usernames), then per username and address.
        guards.limit(guards.limits().loginAddressPolicy(), address);
        guards.limit(guards.limits().loginPolicy(), AuthService.normalizeUsername(body.username()) + "|" + address);
        return sessionResponse(authService.login(body.username(), body.password()));
    }

    @PostMapping("/refresh")
    ResponseEntity<SessionResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                            HttpServletRequest request) {
        guards.requireTrustedOrigin(request);
        return sessionResponse(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                HttpServletRequest request) {
        guards.requireTrustedOrigin(request);
        authService.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    @PostMapping("/change-password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body, @AuthenticationPrincipal CurrentUser user) {
        authService.changePassword(user.id(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    @PostMapping("/reset-password")
    ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        guards.limit(guards.limits().passwordResetPolicy(), guards.clientAddress(request));
        authService.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<SessionResponse> sessionResponse(AuthService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), Duration.ofSeconds(refreshTokens.ttlSeconds())))
                .body(new SessionResponse(session.accessToken(), session.expiresIn(), session.user()));
    }

    /** HttpOnly keeps the token away from scripts; the narrow path keeps it off every other request. */
    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(true)
                .sameSite(properties.cookie().sameSite()).path(COOKIE_PATH).maxAge(maxAge).build().toString();
    }
}
