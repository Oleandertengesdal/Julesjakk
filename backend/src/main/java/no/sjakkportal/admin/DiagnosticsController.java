package no.sjakkportal.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.sjakkportal.security.RequestGuards;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows an admin how the server sees their request: the connection address, the forwarding headers
 * the proxies added, and the address the rate limiter uses. Used once after deploying to choose
 * SJAKKPORTAL_CLIENT_IP_HEADER and SJAKKPORTAL_CLIENT_IP_TRUSTED_HOPS. Never echoes credentials.
 */
@RestController
public class DiagnosticsController {

    private static final List<String> FORWARDING_HEADERS = List.of("X-Forwarded-For", "X-Real-IP", "True-Client-IP",
            "CF-Connecting-IP", "Forwarded", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Vercel-Forwarded-For");

    private final RequestGuards guards;

    public DiagnosticsController(RequestGuards guards) {
        this.guards = guards;
    }

    @GetMapping("/api/admin/diagnostics/request")
    Map<String, Object> request(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDING_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value.length() > 500 ? value.substring(0, 500) : value);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("remoteAddress", request.getRemoteAddr());
        body.put("clientAddressUsedForRateLimits", guards.clientAddress(request));
        body.put("secure", request.isSecure());
        body.put("forwardingHeaders", headers);
        return body;
    }
}
