package no.sjakkportal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import no.sjakkportal.config.SjakkportalProperties;
import org.junit.jupiter.api.Test;

/** The signing key comes from a hosting dashboard; accept both Base64 alphabets, refuse weak keys clearly. */
class JwtServiceKeyTest {

    private static JwtService withSecret(String secret) {
        return new JwtService(new SjakkportalProperties(new SjakkportalProperties.Jwt(secret, Duration.ofMinutes(15), Duration.ofDays(30)),
                null, null, null, null, null));
    }

    @Test
    void acceptsStandardAndUrlSafeBase64() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (0xFB + i * 7);   // produces '+' '/' in standard and '-' '_' in URL-safe form
        }
        for (String secret : new String[] {Base64.getEncoder().encodeToString(bytes), Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}) {
            JwtService service = withSecret(secret);
            assertThat(service.verify(service.issue(7, 0))).isPresent();
        }
    }

    @Test
    void refusesKeysShorterThan256Bits() {
        assertThatThrownBy(() -> withSecret(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("SJAKKPORTAL_JWT_SECRET");
    }
}
