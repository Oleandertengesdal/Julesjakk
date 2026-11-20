package no.sjakkportal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Audit: the promises in docs/SECURITY.md, attacked from outside. Written from the documentation
 * before re-reading the implementation.
 */
class SecurityAuditTest extends ApiIntegrationTest {

    private static final String TEST_KEY = "dGVzdC1vbmx5LXNpZ25pbmcta2V5LWZvci1zamFra3BvcnRhbC10ZXN0cy0wMTIzNDU2Nzg5";

    private long admin;
    private long kari;
    private long ola;

    @BeforeEach
    void users() {
        admin = createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andReturn();
    }

    private static String code(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(r.getResponse().getContentAsString());
        return m.find() ? m.group(1) : "status " + r.getResponse().getStatus() + ": " + r.getResponse().getContentAsString();
    }

    private MvcResult getAs(String path, String token) throws Exception {
        return perform(as(get(path), token));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String headerAndPayload, byte[] key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(headerAndPayload.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String payloadOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    // --- access tokens -------------------------------------------------------------------------

    @Nested
    class AccessTokens {

        @Test
        void unsignedAlgNoneTokensAreRefused() throws Exception {
            String payload = payloadOf(login("kari")).replace("\"sub\":\"" + kari + "\"", "\"sub\":\"" + admin + "\"");
            String none = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + b64(payload) + ".";
            assertThat(getAs("/api/users/me", none).getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        void aTokenWhosePayloadWasEditedIsRefused() throws Exception {
            String token = login("kari");
            String[] parts = token.split("\\.");
            String edited = parts[0] + "." + b64(payloadOf(token).replace("\"" + kari + "\"", "\"" + admin + "\"")) + "." + parts[2];
            assertThat(code(getAs("/api/admin/invites", edited))).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.code());
        }

        @Test
        void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
            String token = login("kari");
            String header = token.split("\\.")[0];
            String body = b64(payloadOf(token).replace("\"" + kari + "\"", "\"" + admin + "\""));
            String forged = header + "." + body + "." + sign(header + "." + body, "another-key-another-key-another-key!".getBytes());
            assertThat(code(getAs("/api/admin/invites", forged))).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.code());
        }

        @Test
        void anExpiredTokenIsRefused() throws Exception {
            String token = login("kari");
            String header = token.split("\\.")[0];
            long past = System.currentTimeMillis() / 1000 - 3600;
            String payload = payloadOf(token).replaceAll("\"exp\":\\d+", "\"exp\":" + past).replaceAll("\"iat\":\\d+", "\"iat\":" + (past - 900));
            String expired = header + "." + b64(payload) + "." + sign(header + "." + b64(payload), Base64.getDecoder().decode(TEST_KEY));
            assertThat(code(getAs("/api/users/me", expired))).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.code());
        }

        /** Docs: access tokens carry only the user id and token version; role comes from the database. */
        @Test
        void tokensCarryNoRoleOrPersonalData() throws Exception {
            String payload = payloadOf(login("kari"));
            assertThat(payload).doesNotContain("PLAYER", "ADMIN", "Kari", "password");
        }

        @Test
        void changingThePasswordKillsEveryOutstandingAccessToken() throws Exception {
            String first = login("kari");
            String second = login("kari");
            perform(as(jsonPost("/api/auth/change-password", Map.of("currentPassword", PASSWORD, "newPassword", "Et-nytt-passord-1")), first));
            assertThat(code(getAs("/api/users/me", first))).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.code());
            assertThat(code(getAs("/api/users/me", second))).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.code());
        }

        @Test
        void aTemporaryPasswordFromTheAdminKillsTheUsersTokensAndSessions() throws Exception {
            MvcResult session = loginResult("kari", PASSWORD);
            String kariToken = body(session).get("accessToken").asText();
            perform(as(jsonPost("/api/admin/users/" + kari + "/password-reset", Map.of("mode", "TEMPORARY_PASSWORD")), login("admin")));
            assertThat(code(getAs("/api/users/me", kariToken))).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.code());
            MvcResult refresh = perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(refreshCookie(session)));
            assertThat(code(refresh)).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
        }

        /** Issuing a link changes nothing yet; using it sets a new password and must end every session. */
        @Test
        void usingAResetLinkKillsTheUsersTokensAndSessions() throws Exception {
            MvcResult session = loginResult("kari", PASSWORD);
            String kariToken = body(session).get("accessToken").asText();
            String resetToken = body(perform(as(jsonPost("/api/admin/users/" + kari + "/password-reset", Map.of("mode", "RESET_LINK")),
                    login("admin")))).get("resetToken").asText();
            perform(jsonPost("/api/auth/reset-password", Map.of("token", resetToken, "newPassword", "Et-nytt-passord-1")));
            assertThat(code(getAs("/api/users/me", kariToken))).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID.code());
            MvcResult refresh = perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(refreshCookie(session)));
            assertThat(code(refresh)).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
        }
    }

    // --- refresh tokens and cookie -------------------------------------------------------------

    @Nested
    class Sessions {

        private MvcResult refresh(Cookie cookie) throws Exception {
            return perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(cookie));
        }

        @Test
        void theRefreshCookieIsHttpOnlySecureSameSiteAndScopedToAuth() throws Exception {
            String header = loginResult("kari", PASSWORD).getResponse().getHeader(HttpHeaders.SET_COOKIE);
            assertThat(header).startsWith("sjakkportal_refresh=").contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/auth");
        }

        @Test
        void aReusedRefreshTokenRevokesTheWholeFamily() throws Exception {
            Cookie original = refreshCookie(loginResult("kari", PASSWORD));
            Cookie rotated = refreshCookie(refresh(original));
            assertThat(code(refresh(original))).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
            // the thief's copy was used: the legitimate successor dies too
            assertThat(code(refresh(rotated))).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
        }

        @Test
        void reuseInOneLoginDoesNotLogOutOtherDevices() throws Exception {
            Cookie phone = refreshCookie(loginResult("kari", PASSWORD));
            Cookie laptop = refreshCookie(loginResult("kari", PASSWORD));
            refresh(phone);
            refresh(phone);
            assertThat(refresh(laptop).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void logoutEndsTheSession() throws Exception {
            Cookie cookie = refreshCookie(loginResult("kari", PASSWORD));
            perform(post("/api/auth/logout").with(fromIp(clientIp)).cookie(cookie));
            assertThat(code(refresh(cookie))).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
        }

        @Test
        void aMadeUpRefreshCookieIsRefused() throws Exception {
            assertThat(code(refresh(new Cookie("sjakkportal_refresh", "made-up")))).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
            assertThat(code(refresh(new Cookie("sjakkportal_refresh", "x".repeat(5000))))).isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
        }

        /** Docs: foreign Origin headers are refused, which is what makes the cookie endpoint CSRF-proof. */
        @Test
        void refreshFromAForeignSiteIsRefused() throws Exception {
            Cookie cookie = refreshCookie(loginResult("kari", PASSWORD));
            MvcResult r = perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(cookie).header("Origin", "https://evil.example"));
            assertThat(code(r)).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
            assertThat(r.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            // and the cookie still works for the real site afterwards
            assertThat(perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(cookie).header("Origin", "http://localhost:5173"))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void loginResponsesNeverContainPasswordMaterial() throws Exception {
            String text = loginResult("kari", PASSWORD).getResponse().getContentAsString();
            assertThat(text.toLowerCase()).doesNotContain("password\":\"", "hash", "$2a$", "tokenversion");
        }
    }

    // --- logins and accounts -------------------------------------------------------------------

    @Nested
    class Accounts {

        @Test
        void unknownUsersAndWrongPasswordsLookTheSame() throws Exception {
            MvcResult unknown = loginResult("nobody", "Whatever-123");
            MvcResult wrong = loginResult("kari", "Whatever-123");
            assertThat(code(unknown)).isEqualTo(code(wrong)).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.code());
            assertThat(body(unknown).get("detail")).isEqualTo(body(wrong).get("detail"));
        }

        @Test
        void sqlInjectionInTheUsernameIsJustAWrongLogin() throws Exception {
            assertThat(code(loginResult("kari' OR '1'='1", "x' OR '1'='1"))).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.code());
        }

        @Test
        void managedPlayersCannotLogIn() throws Exception {
            createManagedPlayer("lillebror");
            assertThat(code(loginResult("lillebror", ""))).isIn(ErrorCode.AUTH_INVALID_CREDENTIALS.code(), ErrorCode.VALIDATION_FAILED.code());
            assertThat(code(loginResult("lillebror", "anything-at-all"))).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.code());
        }

        @Test
        void disabledAccountsCannotLogInAndTheirTokensStopWorking() throws Exception {
            String token = login("kari");
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", kari);
            assertThat(code(loginResult("kari", PASSWORD))).isEqualTo(ErrorCode.AUTH_ACCOUNT_DISABLED.code());
            assertThat(getAs("/api/users/me", token).getResponse().getStatus()).isIn(401, 403);
        }

        @Test
        void registrationCannotChooseItsOwnRole() throws Exception {
            createInvite(admin, "FAMILIE", 5, null);
            Map<String, Object> body = new HashMap<>(Map.of("inviteCode", "FAMILIE", "username", "mallory", "displayName", "Mallory",
                    "password", "Mallory-passord-1"));
            body.put("role", "ADMIN");
            body.put("managed", false);
            perform(jsonPost("/api/auth/register", body));
            assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE username = 'mallory'", String.class)).isEqualTo("PLAYER");
        }

        @Test
        void usernamesAreUniqueRegardlessOfCase() throws Exception {
            createInvite(admin, "FAMILIE", 5, null);
            MvcResult r = perform(jsonPost("/api/auth/register", Map.of("inviteCode", "FAMILIE", "username", "KARI", "displayName", "Kari 2",
                    "password", "Kari-passord-2")));
            assertThat(code(r)).isEqualTo(ErrorCode.AUTH_USERNAME_TAKEN.code());
        }

        @Test
        void passwordPolicyIsEnforcedOnEveryPathThatSetsAPassword() throws Exception {
            createInvite(admin, "FAMILIE", 5, null);
            String tooLongInBytes = "æøå".repeat(25);   // 75 characters, 150 bytes
            for (String weak : List.of("kort", "1234567", tooLongInBytes, "nykommer")) {
                MvcResult r = perform(jsonPost("/api/auth/register", Map.of("inviteCode", "FAMILIE", "username", "nykommer",
                        "displayName", "Ny", "password", weak)));
                assertThat(code(r)).as(weak).isEqualTo(ErrorCode.AUTH_WEAK_PASSWORD.code());
            }
            MvcResult change = perform(as(jsonPost("/api/auth/change-password", Map.of("currentPassword", PASSWORD, "newPassword", "kari")),
                    login("kari")));
            assertThat(code(change)).isEqualTo(ErrorCode.AUTH_WEAK_PASSWORD.code());
        }

        @Test
        void changingThePasswordNeedsTheCurrentOne() throws Exception {
            MvcResult r = perform(as(jsonPost("/api/auth/change-password", Map.of("currentPassword", "wrong-wrong-1",
                    "newPassword", "Et-nytt-passord-1")), login("kari")));
            assertThat(r.getResponse().getStatus()).isIn(401, 403, 422);
            assertThat(loginResult("kari", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        }

        /** Docs: until the password is changed, only change-password, users/me and logout respond. */
        @Test
        void aTemporaryPasswordLocksEverythingElse() throws Exception {
            JsonNode reset = body(perform(as(jsonPost("/api/admin/users/" + kari + "/password-reset",
                    Map.of("mode", "TEMPORARY_PASSWORD")), login("admin"))));
            String temporary = reset.get("temporaryPassword").asText();
            MvcResult session = loginResult("kari", temporary);
            assertThat(session.getResponse().getStatus()).as(session.getResponse().getContentAsString()).isEqualTo(200);
            String token = body(session).get("accessToken").asText();
            assertThat(getAs("/api/users/me", token).getResponse().getStatus()).isEqualTo(200);
            for (String path : List.of("/api/games", "/api/users", "/api/tv", "/api/leaderboard")) {
                assertThat(code(getAs(path, token))).as(path).isEqualTo(ErrorCode.AUTH_PASSWORD_CHANGE_REQUIRED.code());
            }
            assertThat(code(perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "DRAW",
                    "timeControl", "5+3")), token)))).isEqualTo(ErrorCode.AUTH_PASSWORD_CHANGE_REQUIRED.code());
        }

        @Test
        void resetLinksWorkOnceAndANewLinkCancelsTheOldOne() throws Exception {
            String adminToken = login("admin");
            String first = resetToken(adminToken);
            String second = resetToken(adminToken);
            assertThat(code(perform(jsonPost("/api/auth/reset-password", Map.of("token", first, "newPassword", "Et-nytt-passord-1")))))
                    .isIn(ErrorCode.RESET_TOKEN_INVALID.code(), ErrorCode.RESET_TOKEN_USED.code(), ErrorCode.RESET_TOKEN_EXPIRED.code());
            assertThat(perform(jsonPost("/api/auth/reset-password", Map.of("token", second, "newPassword", "Et-nytt-passord-1")))
                    .getResponse().getStatus()).isIn(200, 204);
            assertThat(code(perform(jsonPost("/api/auth/reset-password", Map.of("token", second, "newPassword", "Et-tredje-passord-1")))))
                    .isEqualTo(ErrorCode.RESET_TOKEN_USED.code());
            assertThat(loginResult("kari", "Et-nytt-passord-1").getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void expiredResetLinksAreRefused() throws Exception {
            String token = resetToken(login("admin"));
            jdbc.update("UPDATE password_reset_token SET expires_at = now() - interval '1 minute'");
            assertThat(code(perform(jsonPost("/api/auth/reset-password", Map.of("token", token, "newPassword", "Et-nytt-passord-1")))))
                    .isEqualTo(ErrorCode.RESET_TOKEN_EXPIRED.code());
        }

        @Test
        void resetTokensAreNotStoredInPlainText() throws Exception {
            String token = resetToken(login("admin"));
            String stored = jdbc.queryForObject("SELECT string_agg(t::text, ' ') FROM password_reset_token t", String.class);
            assertThat(stored).doesNotContain(token);
        }

        private String resetToken(String adminToken) throws Exception {
            JsonNode reset = body(perform(as(jsonPost("/api/admin/users/" + kari + "/password-reset", Map.of("mode", "RESET_LINK")), adminToken)));
            return reset.get("resetToken").asText();
        }
    }

    private static String findString(JsonNode node, java.util.function.Predicate<String> wanted) {
        if (node.isTextual() && wanted.test(node.asText())) {
            return node.asText();
        }
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
            String found = findString(it.next(), wanted);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --- invites -------------------------------------------------------------------------------

    @Nested
    class Invites {

        private MvcResult register(String code, String username) throws Exception {
            return perform(jsonPost("/api/auth/register", Map.of("inviteCode", code, "username", username, "displayName", "X",
                    "password", "Sterkt-passord-1")).with(fromIp(nextIp())));
        }

        @Test
        void expiredExhaustedAndRevokedCodesAreRefused() throws Exception {
            createInvite(admin, "GAMMEL", 5, "now() - interval '1 hour'");
            createInvite(admin, "EN-GANG", 1, null);
            long revoked = createInvite(admin, "TRUKKET", 5, null);
            perform(as(request(HttpMethod.DELETE, "/api/admin/invites/" + revoked), login("admin")));
            assertThat(code(register("GAMMEL", "anna1"))).isEqualTo(ErrorCode.INVITE_EXPIRED.code());
            assertThat(register("EN-GANG", "anna2").getResponse().getStatus()).isEqualTo(201);
            assertThat(code(register("EN-GANG", "anna3"))).isEqualTo(ErrorCode.INVITE_EXHAUSTED.code());
            assertThat(code(register("TRUKKET", "anna4"))).isEqualTo(ErrorCode.INVITE_REVOKED.code());
            assertThat(code(register("FINNES-IKKE", "anna5"))).isEqualTo(ErrorCode.INVITE_NOT_FOUND.code());
        }

        @Test
        void aFailedRegistrationDoesNotUseUpTheCode() throws Exception {
            createInvite(admin, "EN-GANG", 1, null);
            perform(jsonPost("/api/auth/register", Map.of("inviteCode", "EN-GANG", "username", "kari", "displayName", "X",
                    "password", "Sterkt-passord-1")));   // username taken
            assertThat(register("EN-GANG", "nykommer").getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        void generatedCodesAreLongAndRandom() throws Exception {
            String token = login("admin");
            String a = body(perform(as(jsonPost("/api/admin/invites", Map.of("maxUses", 1)), token))).get("code").asText();
            String b = body(perform(as(jsonPost("/api/admin/invites", Map.of("maxUses", 1)), token))).get("code").asText();
            assertThat(a.replace("-", "")).hasSize(16);
            assertThat(a).isNotEqualTo(b);
        }
    }

    // --- input handling ------------------------------------------------------------------------

    @Nested
    class Input {

        @Test
        void markupInFreeTextIsStripped() throws Exception {
            createInvite(admin, "FAMILIE", 5, null);
            perform(jsonPost("/api/auth/register", Map.of("inviteCode", "FAMILIE", "username", "tante", "displayName",
                    "<img src=x onerror=alert(1)>Tante<script>alert(2)</script>", "password", "Sterkt-passord-1")));
            String name = jdbc.queryForObject("SELECT display_name FROM app_user WHERE username = 'tante'", String.class);
            assertThat(name).doesNotContain("<", ">", "onerror");

            String kariToken = login("kari");
            JsonNode game = body(perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "DRAW",
                    "timeControl", "5+3", "comment", "<b onmouseover=x>Fin</b> kamp")), kariToken)));
            assertThat(game.get("comment").asText()).doesNotContain("<", ">");
            perform(as(jsonPost("/api/games/" + game.get("id").asLong() + "/reject", Map.of("reason", "<script>x</script>Feil")), login("ola")));
            assertThat(jdbc.queryForObject("SELECT string_agg(coalesce(rejection_reason,'') || coalesce(comment,''), '') FROM game", String.class))
                    .doesNotContain("<");

            String adminToken = login("admin");
            JsonNode t = body(perform(as(jsonPost("/api/tournaments", Map.of("name", "<i>Jul</i>", "description", "<script>x</script>",
                    "format", "ROUND_ROBIN", "timeControl", "5+3")), adminToken)));
            assertThat(t.toString()).doesNotContain("<i>", "<script>");
        }

        @Test
        void oversizedTextIsRefusedNotTruncatedOrCrashed() throws Exception {
            String kariToken = login("kari");
            MvcResult comment = perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "DRAW",
                    "timeControl", "5+3", "comment", "x".repeat(20_000))), kariToken));
            assertThat(code(comment)).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
            MvcResult pgn = perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "DRAW",
                    "timeControl", "5+3", "pgn", "1. e4 e5 ".repeat(25_000))), kariToken));
            assertThat(code(pgn)).isIn(ErrorCode.PGN_TOO_LARGE.code(), ErrorCode.VALIDATION_FAILED.code());
            MvcResult accounts = perform(as(put("/api/users/me/accounts").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"lichessUsername\":\"" + "a".repeat(300) + "\"}"), kariToken));
            assertThat(code(accounts)).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        }

        @Test
        void aHugeRequestBodyIsRefusedWithoutAServerError() throws Exception {
            MvcResult r = perform(jsonPost("/api/auth/login", "{\"username\":\"" + "a".repeat(3_000_000) + "\",\"password\":\"x\"}"));
            assertThat(r.getResponse().getStatus()).isBetween(400, 499);
        }

        @Test
        void brokenJsonWrongTypesAndUnknownEnumsAreClientErrors() throws Exception {
            String kariToken = login("kari");
            for (String bad : List.of("{", "[]", "{\"whiteId\":\"abc\",\"blackId\":2,\"result\":\"DRAW\"}",
                    "{\"whiteId\":1,\"blackId\":2,\"result\":\"WHITE_WINS_BIG\",\"timeControl\":\"5+3\"}", "null")) {
                MvcResult r = perform(as(jsonPost("/api/games", bad), kariToken));
                assertThat(r.getResponse().getStatus()).as(bad).isBetween(400, 422);
            }
        }

        @Test
        void oddPathIdsAreClientErrors() throws Exception {
            String kariToken = login("kari");
            assertThat(getAs("/api/games/abc", kariToken).getResponse().getStatus()).isEqualTo(400);
            assertThat(getAs("/api/games/99999999999999999999999", kariToken).getResponse().getStatus()).isEqualTo(400);
            assertThat(code(getAs("/api/games/-1", kariToken))).isEqualTo(ErrorCode.GAME_NOT_FOUND.code());
            assertThat(code(getAs("/api/users/0", kariToken))).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
            assertThat(getAs("/api/users/" + kari + "/games?limit=-5", kariToken).getResponse().getStatus()).isLessThan(500);
        }

        @Test
        void pagingNeverReturnsMoreThanOneHundredGames() throws Exception {
            for (int i = 0; i < 105; i++) {
                jdbc.update("""
                        INSERT INTO game (white_id, black_id, status, result, category, rated, created_by, played_at)
                        VALUES (?, ?, 'FINISHED', 'DRAW', 'BLITZ', FALSE, ?, now())""", kari, ola, admin);
            }
            MvcResult r = getAs("/api/users/" + kari + "/games?limit=100000", login("kari"));
            if (r.getResponse().getStatus() == 200) {
                JsonNode b = body(r);
                JsonNode items = b.isArray() ? b : b.get("items");
                assertThat(items.size()).isLessThanOrEqualTo(100);
            } else {
                assertThat(code(r)).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
            }
        }

        @Test
        void hostileTraceIdsAreReplacedNotEchoed() throws Exception {
            for (String bad : List.of("abc", "x".repeat(200), "ok-ok-ok-ok\r\nSet-Cookie: x=1", "<script>alert(1)</script>")) {
                MvcResult r = perform(get("/api/ping").with(fromIp(clientIp)).header("X-Trace-Id", bad));
                assertThat(r.getResponse().getHeader("X-Trace-Id")).as(bad).isNotEqualTo(bad).matches("[A-Za-z0-9-]{8,64}");
            }
        }
    }

    // --- data exposure -------------------------------------------------------------------------

    @Nested
    class Exposure {

        @Test
        void userViewsExposeNoSecrets() throws Exception {
            String kariToken = login("kari");
            for (String path : List.of("/api/users", "/api/users/" + ola, "/api/users/me", "/api/leaderboard?category=BLITZ")) {
                String text = getAs(path, kariToken).getResponse().getContentAsString().toLowerCase();
                assertThat(text).as(path).doesNotContain("\"password\"", "passwordhash", "hash\"", "$2a$", "tokenversion", "token_version");
            }
        }

        @Test
        void playersOnlySeeTheirOwnPendingConfirmations() throws Exception {
            long per = createPlayer("per");
            perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "DRAW", "timeControl", "5+3")), login("kari")));
            JsonNode perPending = body(getAs("/api/games/pending", login("per")));
            assertThat(perPending.toString()).doesNotContain("\"id\":" + kari);
            assertThat((perPending.isArray() ? perPending : perPending.get("items")).size()).isZero();
            assertThat(per).isPositive();
        }

        @Test
        void onlyHealthIsExposedFromActuatorAndWithoutDetails() throws Exception {
            for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump",
                    "/actuator/mappings", "/actuator/loggers", "/actuator", "/v3/api-docs", "/swagger-ui.html", "/h2-console")) {
                assertThat(perform(get(path).with(fromIp(clientIp))).getResponse().getStatus()).as(path).isIn(401, 403, 404);
            }
            String health = perform(get("/actuator/health")).getResponse().getContentAsString();
            assertThat(health).doesNotContain("jdbc", "postgres", "database\":\"PostgreSQL", "diskSpace");
        }

        @Test
        void traceAndUnusualMethodsAreNotAllowed() throws Exception {
            assertThat(perform(request(HttpMethod.TRACE, "/api/ping")).getResponse().getStatus()).isIn(400, 401, 403, 405);
            assertThat(perform(request(HttpMethod.PATCH, "/api/users/me").with(fromIp(clientIp))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("kari"))).getResponse().getStatus()).isEqualTo(405);
        }

        @Test
        void errorResponsesCarryTheSameSecurityHeaders() throws Exception {
            MvcResult r = perform(get("/api/does-not-exist").with(fromIp(clientIp)).header(HttpHeaders.AUTHORIZATION, "Bearer " + login("kari")));
            assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'none'");
            assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        }
    }

    // --- cross-origin --------------------------------------------------------------------------

    @Nested
    class CrossOrigin {

        @Test
        void preflightsFromForeignSitesGetNoPermission() throws Exception {
            MvcResult r = perform(options("/api/games").header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization"));
            assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        void theRealFrontendGetsAnExactNonWildcardAllowance() throws Exception {
            MvcResult r = perform(options("/api/games").header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"));
            assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        }

        @Test
        void requestsFromForeignOriginsAreRefusedEvenWithAValidToken() throws Exception {
            String kariToken = login("kari");
            for (String origin : List.of("https://evil.example", "null", "http://localhost:5173.evil.example", "http://LOCALHOST:5173@evil.example")) {
                MvcResult r = perform(as(get("/api/users/me"), kariToken).header("Origin", origin));
                assertThat(code(r)).as(origin).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
            }
        }

        @Test
        void loginFromAForeignSiteIsRefused() throws Exception {
            MvcResult r = perform(jsonPost("/api/auth/login", Map.of("username", "kari", "password", PASSWORD)).header("Origin", "https://evil.example"));
            assertThat(code(r)).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
            assertThat(r.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }
    }

    // --- rate limits ---------------------------------------------------------------------------

    @Nested
    class RateLimits {

        @Test
        void passwordGuessingIsCutOffAndSaysWhenToRetry() throws Exception {
            MvcResult last = null;
            for (int i = 0; i < 6; i++) {
                last = loginResult("kari", "guess-" + i);
            }
            assertThat(code(last)).isEqualTo(ErrorCode.RATE_LIMITED.code());
            assertThat(last.getResponse().getHeader("Retry-After")).matches("\\d+");
            // the correct password does not slip through while limited
            assertThat(code(loginResult("kari", PASSWORD))).isEqualTo(ErrorCode.RATE_LIMITED.code());
        }

        @Test
        void inviteGuessingIsCutOff() throws Exception {
            MvcResult last = null;
            for (int i = 0; i < 11; i++) {
                last = perform(jsonPost("/api/auth/register", Map.of("inviteCode", "GJETT-" + i, "username", "gjest" + i, "displayName", "Gjest",
                        "password", "Sterkt-passord-1")));
            }
            assertThat(code(last)).isEqualTo(ErrorCode.RATE_LIMITED.code());
        }

        @Test
        void resetTokenGuessingIsCutOff() throws Exception {
            MvcResult last = null;
            for (int i = 0; i < 6; i++) {
                last = perform(jsonPost("/api/auth/reset-password", Map.of("token", "guess-" + i, "newPassword", "Sterkt-passord-1")));
            }
            assertThat(code(last)).isEqualTo(ErrorCode.RATE_LIMITED.code());
        }
    }
}
