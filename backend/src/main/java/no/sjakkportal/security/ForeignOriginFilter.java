package no.sjakkportal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.web.Problems;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses actual (non-preflight) cross-origin requests from origins that are not our frontend,
 * before they reach any endpoint, with the same problem format as every other error.
 * Preflights are left to Spring's CORS filter, which simply omits the allow headers.
 */
class ForeignOriginFilter extends OncePerRequestFilter {

    private final List<String> allowedOrigins;
    private final Problems problems;

    ForeignOriginFilter(List<String> allowedOrigins, Problems problems) {
        this.allowedOrigins = allowedOrigins;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean foreign;
        try {
            foreign = CorsUtils.isCorsRequest(request) && !CorsUtils.isPreFlightRequest(request)
                    && !allowedOrigins.contains(request.getHeader(HttpHeaders.ORIGIN));
        } catch (IllegalArgumentException e) {
            // An Origin header that is not even a valid URL (e.g. "http://host@evil.example") would make
            // Spring's own CORS handling throw further down the chain. It is certainly not our frontend.
            foreign = true;
        }
        if (foreign) {
            problems.write(ErrorCode.AUTH_ACCESS_DENIED, request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
