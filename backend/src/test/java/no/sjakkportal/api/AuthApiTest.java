package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class AuthApiTest extends ApiIntegrationTest {

    @Value("${sjakkportal.jwt.secret}")
    private String jwtSecret;

    private MvcResult refresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(cookie)
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")).andReturn();
    }

    @Nested
    class Login {

        @Test
        void returnsAccessTokenAndUserButNeverSecrets() throws Exception {
            createPlayer("kari");
            MvcResult result = loginResult("kari", PASSWORD);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result).get("accessToken").asText()).isNotBlank();
            assertThat(body(result).get("expiresIn").asInt()).isBetween(60, 3600);
            assertThat(body(result).at("/user/username").asText()).isEqualTo("kari");
            assertThat(body(result).at("/user/role").asText()).isEqualTo("PLAYER");
            assertThat(body(result).at("/user/mustChangePassword").asBoolean()).isFalse();
            String raw = result.getResponse().getContentAsString();
            assertThat(raw).doesNotContain("passwordHash", "password_hash", "$2a$", "$2b$", "refreshToken", "tokenVersion");
        }

        @Test
        void refreshTokenLivesOnlyInAHardenedCookie() throws Exception {
            createPlayer("kari");
            MvcResult result = loginResult("kari", PASSWORD);
            String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);

            assertThat(setCookie).startsWith("sjakkportal_refresh=").contains("HttpOnly").contains("Secure")
                    .contains("SameSite=").contains("Path=/api/auth").contains("Max-Age=");
            assertThat(result.getResponse().getContentAsString()).doesNotContain(refreshCookie(result).getValue());
        }

        @Test
        void refreshTokenIsStoredHashed() throws Exception {
            createPlayer("kari");
            String raw = refreshCookie(loginResult("kari", PASSWORD)).getValue();
            assertThat(raw.length()).isGreaterThanOrEqualTo(32);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE token_hash = ?", Integer.class, raw)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token", Integer.class)).isEqualTo(1);
        }

        @Test
        void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
            createPlayer("kari");
            MvcResult wrongPassword = mvc.perform(jsonPost("/api/auth/login", Map.of("username", "kari", "password", "feil-passord-1")))
                    .andExpect(problem(ErrorCode.AUTH_INVALID_CREDENTIALS)).andReturn();
            MvcResult unknownUser = mvc.perform(jsonPost("/api/auth/login", Map.of("username", "finnes-ikke", "password", "feil-passord-1")))
                    .andExpect(problem(ErrorCode.AUTH_INVALID_CREDENTIALS)).andReturn();

            assertThat(body(wrongPassword).get("detail")).isEqualTo(body(unknownUser).get("detail"));
            assertThat(wrongPassword.getResponse().getCookie("sjakkportal_refresh")).isNull();
        }

        @Test
        void usernameIsCaseInsensitiveAndTrimmed() throws Exception {
            createPlayer("kari");
            assertThat(loginResult("  KaRi ", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void disabledAccountCannotLogIn() throws Exception {
            long kari = createPlayer("kari");
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", kari);
            mvc.perform(jsonPost("/api/auth/login", Map.of("username", "kari", "password", PASSWORD)))
                    .andExpect(problem(ErrorCode.AUTH_ACCOUNT_DISABLED));
        }

        @Test
        void disabledAccountIsNotRevealedToSomeoneGuessingPasswords() throws Exception {
            long kari = createPlayer("kari");
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", kari);
            mvc.perform(jsonPost("/api/auth/login", Map.of("username", "kari", "password", "feil-passord-1")))
                    .andExpect(problem(ErrorCode.AUTH_INVALID_CREDENTIALS));
        }

        @Test
        void sqlAndOversizedInputAreJustBadCredentials() throws Exception {
            createPlayer("kari");
            mvc.perform(jsonPost("/api/auth/login", Map.of("username", "kari' OR '1'='1", "password", "x' OR '1'='1")))
                    .andExpect(status().is4xxClientError());
            mvc.perform(jsonPost("/api/auth/login", Map.of("username", "kari", "password", "p".repeat(100_000))))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    class AccessTokens {

        private SecretKey key() {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        }

        @Test
        void validTokenAuthenticates() throws Exception {
            long kari = createPlayer("kari");
            mvc.perform(as(get("/api/users/me"), login("kari")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(kari))
                    .andExpect(jsonPath("$.username").value("kari"))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
        }

        @Test
        void expiredTokenIsRejected() throws Exception {
            long kari = createPlayer("kari");
            String expired = Jwts.builder().subject(String.valueOf(kari)).claim("ver", 0)
                    .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                    .expiration(Date.from(Instant.now().minusSeconds(3600))).signWith(key()).compact();
            mvc.perform(as(get("/api/users/me"), expired)).andExpect(problem(ErrorCode.AUTH_TOKEN_INVALID));
        }

        @Test
        void tokenSignedWithAnotherKeyIsRejected() throws Exception {
            long kari = createPlayer("kari");
            SecretKey other = Keys.hmacShaKeyFor("a-completely-different-signing-key-0123456789".getBytes(StandardCharsets.UTF_8));
            String forged = Jwts.builder().subject(String.valueOf(kari)).claim("ver", 0).claim("role", "ADMIN")
                    .expiration(Date.from(Instant.now().plusSeconds(600))).signWith(other).compact();
            mvc.perform(as(get("/api/users/me"), forged)).andExpect(problem(ErrorCode.AUTH_TOKEN_INVALID));
        }

        @Test
        void unsignedAlgNoneTokenIsRejected() throws Exception {
            long kari = createPlayer("kari");
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64.encodeToString(("{\"sub\":\"" + kari + "\",\"ver\":0,\"role\":\"ADMIN\",\"exp\":"
                    + (Instant.now().getEpochSecond() + 600) + "}").getBytes(StandardCharsets.UTF_8));
            mvc.perform(as(get("/api/users/me"), header + "." + payload + ".")).andExpect(problem(ErrorCode.AUTH_TOKEN_INVALID));
        }

        @Test
        void tamperedPayloadIsRejected() throws Exception {
            createPlayer("kari");
            long admin = createAdmin("admin");
            String[] parts = login("kari").split("\\.");
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                    .replaceFirst("\"sub\":\"\\d+\"", "\"sub\":\"" + admin + "\"");
            String tampered = parts[0] + "." + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
            mvc.perform(as(get("/api/users/me"), tampered)).andExpect(problem(ErrorCode.AUTH_TOKEN_INVALID));
        }

        @Test
        void garbageTokensAreRejectedCleanly() throws Exception {
            for (String garbage : new String[] {"abc", "a.b.c", "", "....", "Bearer", "null", "x".repeat(20_000)}) {
                mvc.perform(as(get("/api/users/me"), garbage)).andExpect(status().isUnauthorized());
            }
        }

        @Test
        void tokenOfADeletedOrDisabledUserStopsWorkingImmediately() throws Exception {
            long kari = createPlayer("kari");
            String token = login("kari");
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", kari);
            mvc.perform(as(get("/api/users/me"), token)).andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_ACCOUNT_DISABLED.code()));
        }

        @Test
        void refreshTokenIsNotAcceptedAsAnAccessToken() throws Exception {
            createPlayer("kari");
            String refreshValue = refreshCookie(loginResult("kari", PASSWORD)).getValue();
            mvc.perform(as(get("/api/users/me"), refreshValue)).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Refresh {

        @Test
        void rotatesTheRefreshTokenAndIssuesANewAccessToken() throws Exception {
            createPlayer("kari");
            Cookie first = refreshCookie(loginResult("kari", PASSWORD));
            MvcResult refreshed = refresh(first);

            assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
            Cookie second = refreshCookie(refreshed);
            assertThat(second.getValue()).isNotEqualTo(first.getValue());
            mvc.perform(as(get("/api/users/me"), body(refreshed).get("accessToken").asText())).andExpect(status().isOk());
        }

        @Test
        void reusingARotatedTokenRevokesTheWholeSession() throws Exception {
            createPlayer("kari");
            Cookie stolen = refreshCookie(loginResult("kari", PASSWORD));
            Cookie current = refreshCookie(refresh(stolen));

            // The old token shows up again: someone has a copy. Kill the family, including the newest token.
            assertThat(refresh(stolen).getResponse().getStatus()).isEqualTo(401);
            MvcResult afterTheft = refresh(current);
            assertThat(afterTheft.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(afterTheft).get("code").asText()).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
        }

        @Test
        void concurrentRefreshesWithTheSameTokenYieldAtMostOneNewSession() throws Exception {
            createPlayer("kari");
            Cookie cookie = refreshCookie(loginResult("kari", PASSWORD));
            var statuses = runConcurrently(java.util.Collections.nCopies(8,
                    (java.util.concurrent.Callable<Integer>) () -> refresh(cookie).getResponse().getStatus()));

            assertThat(statuses.stream().filter(s -> s == 200).count()).isLessThanOrEqualTo(1);
            assertThat(statuses).allMatch(s -> s == 200 || s == 401);
            Integer live = jdbc.queryForObject(
                    "SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL AND expires_at > now()", Integer.class);
            assertThat(live).isLessThanOrEqualTo(1);
        }

        @Test
        void missingUnknownOrExpiredTokenIsRejected() throws Exception {
            long kari = createPlayer("kari");
            mvc.perform(post("/api/auth/refresh").with(fromIp(clientIp)).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                    .andExpect(problem(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));
            assertThat(refresh(new Cookie("sjakkportal_refresh", "not-a-real-token")).getResponse().getStatus()).isEqualTo(401);

            Cookie cookie = refreshCookie(loginResult("kari", PASSWORD));
            jdbc.update("UPDATE refresh_token SET expires_at = now() - interval '1 second' WHERE user_id = ?", kari);
            assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        void crossSiteRefreshIsRefused() throws Exception {
            createPlayer("kari");
            Cookie cookie = refreshCookie(loginResult("kari", PASSWORD));
            mvc.perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(cookie)
                    .header(HttpHeaders.ORIGIN, "https://evil.example")).andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));
            // ...and the refused attempt must not have burned the legitimate token.
            assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
            createPlayer("kari");
            MvcResult loginResult = loginResult("kari", PASSWORD);
            Cookie cookie = refreshCookie(loginResult);

            MvcResult logout = mvc.perform(post("/api/auth/logout").with(fromIp(clientIp)).cookie(cookie)
                    .header(HttpHeaders.ORIGIN, "http://localhost:5173")).andReturn();
            assertThat(logout.getResponse().getStatus()).isEqualTo(204);
            assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("sjakkportal_refresh=").contains("Max-Age=0");
            assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        void logoutWithoutASessionStillSucceeds() throws Exception {
            mvc.perform(post("/api/auth/logout").with(fromIp(clientIp)).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    class ChangePassword {

        private MvcResult change(String token, String current, String next) throws Exception {
            return mvc.perform(as(jsonPost("/api/auth/change-password", Map.of("currentPassword", current, "newPassword", next)), token))
                    .andReturn();
        }

        @Test
        void changesThePasswordAndInvalidatesEveryOlderToken() throws Exception {
            createPlayer("kari");
            MvcResult session = loginResult("kari", PASSWORD);
            String oldAccess = body(session).get("accessToken").asText();
            Cookie oldRefresh = refreshCookie(session);

            assertThat(change(oldAccess, PASSWORD, "Nytt-og-bedre-passord-7").getResponse().getStatus()).isEqualTo(204);

            assertThat(loginResult("kari", PASSWORD).getResponse().getStatus()).isEqualTo(401);
            assertThat(loginResult("kari", "Nytt-og-bedre-passord-7").getResponse().getStatus()).isEqualTo(200);
            mvc.perform(as(get("/api/users/me"), oldAccess)).andExpect(problem(ErrorCode.AUTH_TOKEN_INVALID));
            assertThat(refresh(oldRefresh).getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        void requiresTheCurrentPassword() throws Exception {
            createPlayer("kari");
            MvcResult result = change(login("kari"), "feil-passord-1", "Nytt-og-bedre-passord-7");
            assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.code());
            assertThat(loginResult("kari", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void enforcesThePasswordPolicy() throws Exception {
            createPlayer("kari-nordmann");
            String token = login("kari-nordmann");
            for (String weak : new String[] {"kort1", "1234567", "kari-nordmann", "KARI-NORDMANN", "        "}) {
                assertThat(body(change(token, PASSWORD, weak)).get("code").asText()).as("password '%s'", weak)
                        .isEqualTo(ErrorCode.AUTH_WEAK_PASSWORD.code());
            }
        }

        /** BCrypt silently ignores everything after 72 bytes; two different long passwords would both work. */
        @Test
        void rejectsPasswordsLongerThanBcryptCanHash() throws Exception {
            createPlayer("kari");
            String token = login("kari");
            assertThat(body(change(token, PASSWORD, "a".repeat(73))).get("code").asText())
                    .isEqualTo(ErrorCode.AUTH_WEAK_PASSWORD.code());
            // 36 two-byte characters are 72 bytes: allowed. 37 are 74 bytes: not.
            assertThat(body(change(token, PASSWORD, "\u00e5".repeat(37))).get("code").asText())
                    .isEqualTo(ErrorCode.AUTH_WEAK_PASSWORD.code());
        }

        @Test
        void requiresAuthentication() throws Exception {
            mvc.perform(jsonPost("/api/auth/change-password", Map.of("currentPassword", PASSWORD, "newPassword", "Nytt-og-bedre-passord-7")))
                    .andExpect(problem(ErrorCode.AUTH_REQUIRED));
        }
    }
}
