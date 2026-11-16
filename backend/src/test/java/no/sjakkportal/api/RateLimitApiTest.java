package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Default limits under test: login 5/min per username+address, invite redemption 10/min per address,
 * password-reset consumption 5/min per address.
 */
class RateLimitApiTest extends ApiIntegrationTest {

    private MvcResult loginFrom(String ip, String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password)))).andReturn();
    }

    private void assertRateLimited(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.RATE_LIMITED.code());
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).as("Retry-After header").matches("\\d+");
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 60);
    }

    @Test
    void sixthLoginAttemptInAMinuteIsLimitedEvenWithTheRightPassword() throws Exception {
        createPlayer("kari");
        for (int i = 1; i <= 5; i++) {
            assertThat(loginFrom(clientIp, "kari", "feil-passord-" + i).getResponse().getStatus()).as("attempt %d", i).isEqualTo(401);
        }
        assertRateLimited(loginFrom(clientIp, "kari", "feil-passord-6"));
        // Knowing the password does not help once the bucket is empty, or the limit is no protection at all.
        assertRateLimited(loginFrom(clientIp, "kari", PASSWORD));
    }

    @Test
    void exactlyFiveAttemptsAreAllowedAtTheBoundary() throws Exception {
        createPlayer("kari");
        for (int i = 1; i <= 4; i++) {
            loginFrom(clientIp, "kari", "feil-passord-" + i);
        }
        assertThat(loginFrom(clientIp, "kari", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void limitIsPerUsernameAndPerAddress() throws Exception {
        createPlayer("kari");
        createPlayer("ola");
        for (int i = 0; i < 6; i++) {
            loginFrom(clientIp, "kari", "feil-passord-1");
        }
        assertThat(loginFrom(clientIp, "ola", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(loginFrom(nextIp(), "kari", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void usernameCaseAndWhitespaceVariantsShareOneBucket() throws Exception {
        createPlayer("kari");
        String[] variants = {"kari", "KARI", "Kari", " kari", "kari ", "kArI"};
        for (int i = 0; i < 5; i++) {
            loginFrom(clientIp, variants[i], "feil-passord-1");
        }
        assertRateLimited(loginFrom(clientIp, variants[5], "feil-passord-1"));
    }

    @Test
    void spoofedForwardedHeadersDoNotBuyExtraAttempts() throws Exception {
        createPlayer("kari");
        MvcResult last = null;
        for (int i = 0; i < 6; i++) {
            last = mvc.perform(post("/api/auth/login").with(fromIp(clientIp))
                    .header("X-Forwarded-For", "203.0.113." + i).header("X-Real-IP", "198.51.100." + i)
                    .header("Forwarded", "for=192.0.2." + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", "kari", "password", "feil-passord-1")))).andReturn();
        }
        assertRateLimited(last);
    }

    @Test
    void passwordSprayingAcrossUsernamesFromOneAddressIsAlsoLimited() throws Exception {
        int limited = 0;
        for (int i = 0; i < 40; i++) {
            if (loginFrom(clientIp, "offer" + i, "Sommer2026!").getResponse().getStatus() == 429) {
                limited++;
            }
        }
        assertThat(limited).as("a per-address ceiling must exist besides the per-username one").isPositive();
    }

    @Test
    void concurrentBurstLetsThroughNoMoreThanTheLimit() throws Exception {
        createPlayer("kari");
        String ip = clientIp;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> loginFrom(ip, "kari", "feil-passord-1").getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(tasks);
        assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(15);
    }

    @Test
    void inviteCodeGuessingIsLimitedPerAddress() throws Exception {
        MvcResult last = null;
        for (int i = 1; i <= 11; i++) {
            last = mvc.perform(jsonPost("/api/auth/register", Map.of("inviteCode", "GJETNING-" + i, "username", "gjest" + i,
                    "displayName", "Gjest", "password", PASSWORD))).andReturn();
            if (i <= 10) {
                assertThat(last.getResponse().getStatus()).as("attempt %d", i).isEqualTo(404);
            }
        }
        assertRateLimited(last);
    }

    @Test
    void rateLimitedRegistrationDoesNotConsumeAValidInvite() throws Exception {
        long admin = createAdmin("admin");
        createInvite(admin, "JUL-2026", 1, null);
        for (int i = 1; i <= 10; i++) {
            mvc.perform(jsonPost("/api/auth/register", Map.of("inviteCode", "GJETNING-" + i, "username", "gjest" + i,
                    "displayName", "Gjest", "password", PASSWORD)));
        }
        assertRateLimited(mvc.perform(jsonPost("/api/auth/register", Map.of("inviteCode", "JUL-2026", "username", "kari",
                "displayName", "Kari", "password", PASSWORD))).andReturn());
        assertThat(jdbc.queryForObject("SELECT use_count FROM invite_code WHERE code = 'JUL-2026'", Integer.class)).isZero();
    }

    @Test
    void resetTokenGuessingIsLimitedPerAddress() throws Exception {
        MvcResult last = null;
        for (int i = 1; i <= 6; i++) {
            last = mvc.perform(jsonPost("/api/auth/reset-password", Map.of("token", "gjetning-" + i, "newPassword", "Et-helt-nytt-passord-1")))
                    .andReturn();
            if (i <= 5) {
                assertThat(last.getResponse().getStatus()).as("attempt %d", i).isEqualTo(400);
            }
        }
        assertRateLimited(last);
    }

    @Test
    void rateLimitedResponsesAreNotCacheable() throws Exception {
        createPlayer("kari");
        MvcResult last = null;
        for (int i = 0; i < 6; i++) {
            last = loginFrom(clientIp, "kari", "feil-passord-1");
        }
        assertThat(last.getResponse().getHeader("Cache-Control")).contains("no-store");
    }
}
