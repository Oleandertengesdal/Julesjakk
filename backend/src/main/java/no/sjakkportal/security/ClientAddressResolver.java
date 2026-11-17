package no.sjakkportal.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Works out the client's address for per-address rate limits.
 *
 * <p>Without configuration the connection's address is used, which behind a proxy is the proxy's
 * address (every visitor then shares one bucket: safe, but coarse). With a header configured, the
 * entry {@code trustedHops} positions from the right is used: the address appended by the outermost
 * proxy we trust. Entries further left were written by the client and can be anything, so they are
 * never used. Anything that does not look like an IP address falls back to the connection address.
 */
public class ClientAddressResolver {

    private static final int MAX_HEADER_LENGTH = 1000;
    private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final String header;
    private final int trustedHops;

    public ClientAddressResolver(String header, int trustedHops) {
        this.header = header == null || header.isBlank() ? null : header.strip();
        this.trustedHops = Math.max(1, trustedHops);
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (header == null) {
            return remote;
        }
        String value = request.getHeader(header);
        if (value == null || value.length() > MAX_HEADER_LENGTH) {
            return remote;
        }
        String[] entries = value.split(",");
        int index = entries.length - trustedHops;
        if (index < 0) {
            return remote;
        }
        String candidate = entries[index].strip();
        return IP.matcher(candidate).matches() ? candidate : remote;
    }

    public boolean usesHeader() {
        return header != null;
    }
}
