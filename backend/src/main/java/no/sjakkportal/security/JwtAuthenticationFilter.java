package no.sjakkportal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.web.Problems;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer} requests. A request without the header continues
 * anonymously; a request with a bad token is rejected here, so the caller learns the token is the problem.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    /** Reachable while a password change is pending. */
    private static final Set<String> ALLOWED_BEFORE_PASSWORD_CHANGE =
            Set.of("/api/auth/change-password", "/api/auth/logout", "/api/users/me");

    private final TokenAuthenticator authenticator;
    private final Problems problems;

    public JwtAuthenticationFilter(TokenAuthenticator authenticator, Problems problems) {
        this.authenticator = authenticator;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        CurrentUser user;
        try {
            if (!header.startsWith(BEARER)) {
                throw new ChessException(ErrorCode.AUTH_TOKEN_INVALID);
            }
            user = authenticator.authenticate(header.substring(BEARER.length()).strip());
        } catch (ChessException e) {
            problems.write(e.errorCode(), request, response);
            return;
        }
        if (user.mustChangePassword() && !ALLOWED_BEFORE_PASSWORD_CHANGE.contains(request.getRequestURI())) {
            problems.write(ErrorCode.AUTH_PASSWORD_CHANGE_REQUIRED, request, response);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
        chain.doFilter(request, response);
    }
}
