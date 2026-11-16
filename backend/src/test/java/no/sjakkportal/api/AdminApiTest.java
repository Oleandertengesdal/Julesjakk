package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Admin-only features, and every way a non-admin might try to reach them. */
class AdminApiTest extends ApiIntegrationTest {

    private long admin;
    private long kari;
    private long ola;
    private String adminToken;
    private String kariToken;

    @BeforeEach
    void setUp() throws Exception {
        admin = createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        adminToken = login("admin");
        kariToken = login("kari");
    }

    private MvcResult createInviteVia(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(as(jsonPost("/api/admin/invites", body), token)).andReturn();
    }

    private MvcResult resetPassword(String token, long userId, String mode) throws Exception {
        return mvc.perform(as(jsonPost("/api/admin/users/" + userId + "/password-reset", Map.of("mode", mode)), token)).andReturn();
    }

    @Nested
    class InviteManagement {

        @Test
        void adminCreatesACustomCodeWithLimitAndExpiry() throws Exception {
            MvcResult result = createInviteVia(adminToken, Map.of("code", "jul-2026", "maxUses", 5, "expiresInHours", 24, "note", "Julaften"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode invite = body(result);
            assertThat(invite.get("code").asText()).isEqualTo("JUL-2026");
            assertThat(invite.get("maxUses").asInt()).isEqualTo(5);
            assertThat(invite.get("useCount").asInt()).isZero();
            assertThat(invite.get("remainingUses").asInt()).isEqualTo(5);
            assertThat(invite.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(Instant.parse(invite.get("expiresAt").asText()))
                    .isCloseTo(Instant.now().plus(24, ChronoUnit.HOURS), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
        }

        @Test
        void generatedCodesAreUnguessableAndUnique() throws Exception {
            Set<String> codes = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                String code = body(createInviteVia(adminToken, Map.of("maxUses", 1))).get("code").asText();
                assertThat(code).matches("[A-Z0-9-]{12,64}");
                assertThat(codes.add(code)).isTrue();
            }
        }

        @Test
        void unlimitedNeverExpiringCodeIsAllowed() throws Exception {
            JsonNode invite = body(createInviteVia(adminToken, Map.of("code", "FAMILIETREFF")));
            assertThat(invite.get("maxUses").isNull()).isTrue();
            assertThat(invite.get("remainingUses").isNull()).isTrue();
            assertThat(invite.get("expiresAt").isNull()).isTrue();
        }

        @Test
        void invalidConfigurationsAreRejected() throws Exception {
            Object[] invalid = {
                    Map.of("maxUses", 0), Map.of("maxUses", -3), Map.of("expiresInHours", 0), Map.of("expiresInHours", -1),
                    Map.of("expiresAt", Instant.now().minusSeconds(60).toString()),
                    Map.of("expiresInHours", 24, "expiresAt", Instant.now().plusSeconds(3600).toString()),
                    Map.of("code", "abc"), Map.of("code", "har mellomrom"), Map.of("code", "<script>alert(1)</script>"),
                    Map.of("code", "X".repeat(65))};
            for (Object body : invalid) {
                MvcResult result = mvc.perform(as(jsonPost("/api/admin/invites", body), adminToken)).andReturn();
                assertThat(result.getResponse().getStatus()).as("config %s", body).isIn(400, 422);
                assertThat(body(result).get("code").asText()).as("config %s", body)
                        .isIn(ErrorCode.INVITE_INVALID_CONFIG.code(), ErrorCode.VALIDATION_FAILED.code());
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invite_code", Integer.class)).isZero();
        }

        @Test
        void duplicateCodeIsAConflictEvenInAnotherCase() throws Exception {
            createInviteVia(adminToken, Map.of("code", "JUL-2026"));
            assertThat(body(createInviteVia(adminToken, Map.of("code", "jul-2026"))).get("code").asText())
                    .isEqualTo(ErrorCode.INVITE_CODE_TAKEN.code());
        }

        @Test
        void listShowsStatusRemainingUsesAndWhoRedeemed() throws Exception {
            long active = createInvite(admin, "AKTIV-KODE", 3, "now() + interval '1 day'");
            createInvite(admin, "UTLOPT-KODE", null, "now() - interval '1 day'");
            long revoked = createInvite(admin, "TRUKKET-KODE", null, null);
            jdbc.update("UPDATE invite_code SET revoked_at = now() WHERE id = ?", revoked);
            long full = createInvite(admin, "BRUKT-KODE", 1, null);
            jdbc.update("UPDATE invite_code SET use_count = 1 WHERE id = ?", full);
            jdbc.update("UPDATE invite_code SET use_count = 1 WHERE id = ?", active);
            jdbc.update("INSERT INTO invite_redemption (invite_code_id, user_id) VALUES (?, ?)", active, kari);

            mvc.perform(as(get("/api/admin/invites"), adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(4))
                    .andExpect(jsonPath("$[?(@.code == 'AKTIV-KODE')].status").value("ACTIVE"))
                    .andExpect(jsonPath("$[?(@.code == 'AKTIV-KODE')].remainingUses").value(2))
                    .andExpect(jsonPath("$[?(@.code == 'AKTIV-KODE')].redemptions[0].username").value("kari"))
                    .andExpect(jsonPath("$[?(@.code == 'UTLOPT-KODE')].status").value("EXPIRED"))
                    .andExpect(jsonPath("$[?(@.code == 'TRUKKET-KODE')].status").value("REVOKED"))
                    .andExpect(jsonPath("$[?(@.code == 'BRUKT-KODE')].status").value("EXHAUSTED"));
        }

        @Test
        void revokedCodeStopsWorkingInstantlyButKeepsItsHistory() throws Exception {
            long invite = createInvite(admin, "JUL-2026", null, null);
            mvc.perform(as(delete("/api/admin/invites/" + invite), adminToken)).andExpect(status().isNoContent());

            mvc.perform(jsonPost("/api/auth/register", Map.of("inviteCode", "JUL-2026", "username", "nykommer",
                    "displayName", "Ny", "password", PASSWORD))).andExpect(problem(ErrorCode.INVITE_REVOKED));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM invite_code WHERE revoked_at IS NOT NULL", Integer.class)).isEqualTo(1);
            // Revoking twice is harmless; revoking something that never existed is a 404.
            mvc.perform(as(delete("/api/admin/invites/" + invite), adminToken)).andExpect(status().isNoContent());
            mvc.perform(as(delete("/api/admin/invites/424242"), adminToken)).andExpect(problem(ErrorCode.INVITE_NOT_FOUND));
        }
    }

    @Nested
    class AccessControl {

        @Test
        void playersCannotCreateListOrRevokeInvites() throws Exception {
            long invite = createInvite(admin, "JUL-2026", null, null);

            mvc.perform(as(jsonPost("/api/admin/invites", Map.of("maxUses", 1)), kariToken)).andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));
            mvc.perform(as(get("/api/admin/invites"), kariToken)).andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));
            mvc.perform(as(delete("/api/admin/invites/" + invite), kariToken)).andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM invite_code WHERE revoked_at IS NULL", Integer.class)).isEqualTo(1);
        }

        @Test
        void anonymousCallersGet401NotAHintThatTheEndpointExists() throws Exception {
            mvc.perform(jsonPost("/api/admin/invites", Map.of("maxUses", 1))).andExpect(problem(ErrorCode.AUTH_REQUIRED));
            mvc.perform(get("/api/admin/invites").with(fromIp(clientIp))).andExpect(problem(ErrorCode.AUTH_REQUIRED));
            mvc.perform(jsonPost("/api/admin/users/" + kari + "/password-reset", Map.of("mode", "RESET_LINK")))
                    .andExpect(problem(ErrorCode.AUTH_REQUIRED));
        }

        @Test
        void playersCannotResetAnyonesPasswordIncludingTheirOwn() throws Exception {
            for (long target : new long[] {ola, admin, kari}) {
                MvcResult result = resetPassword(kariToken, target, "TEMPORARY_PASSWORD");
                assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_token", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE must_change_password", Integer.class)).isZero();
            assertThat(loginResult("ola", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void accessDeniedLooksTheSameWhetherOrNotTheTargetExists() throws Exception {
            MvcResult existing = resetPassword(kariToken, ola, "RESET_LINK");
            MvcResult missing = resetPassword(kariToken, 987654, "RESET_LINK");
            assertThat(existing.getResponse().getStatus()).isEqualTo(403);
            assertThat(missing.getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        void adminPathTricksDoNotBypassTheRule() throws Exception {
            for (String path : new String[] {"/api/admin/invites/", "/api/admin//invites", "/api/ADMIN/invites",
                    "/api/admin/invites;x=1", "/api/admin/invites.json", "/api/admin/%69nvites", "/api/users/../admin/invites"}) {
                MvcResult result = mvc.perform(as(MockMvcRequestBuilders.get(java.net.URI.create(path)), kariToken)).andReturn();
                assertThat(result.getResponse().getStatus()).as("path %s", path).isIn(400, 403, 404);
                assertThat(result.getResponse().getContentAsString()).as("path %s", path).doesNotContain("JUL-", "remainingUses");
            }
        }

        /** Authorisation must follow the database, not a role claim minted when the user was still an admin. */
        @Test
        void demotedAdminLosesAccessWithoutWaitingForTokenExpiry() throws Exception {
            jdbc.update("UPDATE app_user SET role = 'PLAYER' WHERE id = ?", admin);
            mvc.perform(as(get("/api/admin/invites"), adminToken)).andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));
        }

        @Test
        void promotedPlayerGainsAccessOnTheNextRequest() throws Exception {
            jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", kari);
            mvc.perform(as(get("/api/admin/invites"), kariToken)).andExpect(status().isOk());
        }

        @Test
        void userListNeverExposesCredentialFields() throws Exception {
            MvcResult result = mvc.perform(as(get("/api/users"), kariToken)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("password", "$2a$", "$2b$", "tokenVersion", "token_version");
        }
    }

    @Nested
    class PasswordReset {

        private MvcResult consume(String token, String newPassword) throws Exception {
            return mvc.perform(jsonPost("/api/auth/reset-password", Map.of("token", token, "newPassword", newPassword))
                    .with(fromIp(nextIp()))).andReturn();
        }

        @Test
        void resetLinkLetsTheUserChooseANewPasswordExactlyOnce() throws Exception {
            MvcResult issued = resetPassword(adminToken, ola, "RESET_LINK");
            assertThat(issued.getResponse().getStatus()).isEqualTo(200);
            String token = body(issued).get("resetToken").asText();
            assertThat(token.length()).isGreaterThanOrEqualTo(32);
            assertThat(Instant.parse(body(issued).get("expiresAt").asText())).isAfter(Instant.now());

            assertThat(consume(token, "Olas-nye-passord-99").getResponse().getStatus()).isEqualTo(204);
            assertThat(loginResult("ola", "Olas-nye-passord-99").getResponse().getStatus()).isEqualTo(200);
            assertThat(loginResult("ola", PASSWORD).getResponse().getStatus()).isEqualTo(401);
            assertThat(body(consume(token, "Enda-et-passord-11")).get("code").asText()).isEqualTo(ErrorCode.RESET_TOKEN_USED.code());
            assertThat(loginResult("ola", "Olas-nye-passord-99").getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void resetTokenIsStoredHashedAndNeverLogged() throws Exception {
            String token = body(resetPassword(adminToken, ola, "RESET_LINK")).get("resetToken").asText();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_token WHERE token_hash = ?", Integer.class, token)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE detail LIKE '%' || ? || '%'", Integer.class, token)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND target LIKE '%' || ? || '%'",
                    Integer.class, admin, String.valueOf(ola))).isEqualTo(1);
        }

        @Test
        void simultaneousUseOfOneTokenSucceedsOnce() throws Exception {
            String token = body(resetPassword(adminToken, ola, "RESET_LINK")).get("resetToken").asText();
            java.util.List<java.util.concurrent.Callable<Integer>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String password = "Samtidig-passord-" + i;
                tasks.add(() -> consume(token, password).getResponse().getStatus());
            }
            java.util.List<Integer> statuses = runConcurrently(tasks);

            assertThat(statuses.stream().filter(s -> s == 204).count()).isEqualTo(1);
            assertThat(statuses).allMatch(s -> s == 204 || s == 410);
        }

        @Test
        void expiredGarbageAndSupersededTokensAreRejected() throws Exception {
            String first = body(resetPassword(adminToken, ola, "RESET_LINK")).get("resetToken").asText();
            String second = body(resetPassword(adminToken, ola, "RESET_LINK")).get("resetToken").asText();

            assertThat(consume(first, "Olas-nye-passord-99").getResponse().getStatus()).as("superseded token").isIn(400, 410);
            assertThat(body(consume("ikke-et-token", "Olas-nye-passord-99")).get("code").asText())
                    .isEqualTo(ErrorCode.RESET_TOKEN_INVALID.code());
            jdbc.update("UPDATE password_reset_token SET expires_at = now() - interval '1 second' WHERE used_at IS NULL");
            assertThat(body(consume(second, "Olas-nye-passord-99")).get("code").asText()).isEqualTo(ErrorCode.RESET_TOKEN_EXPIRED.code());
            assertThat(loginResult("ola", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void weakPasswordDoesNotBurnTheToken() throws Exception {
            String token = body(resetPassword(adminToken, ola, "RESET_LINK")).get("resetToken").asText();
            assertThat(body(consume(token, "kort")).get("code").asText()).isEqualTo(ErrorCode.AUTH_WEAK_PASSWORD.code());
            assertThat(consume(token, "Olas-nye-passord-99").getResponse().getStatus()).isEqualTo(204);
        }

        @Test
        void temporaryPasswordForcesAChangeBeforeAnythingElse() throws Exception {
            MvcResult issued = resetPassword(adminToken, ola, "TEMPORARY_PASSWORD");
            String temporary = body(issued).get("temporaryPassword").asText();
            assertThat(temporary.length()).isGreaterThanOrEqualTo(12);
            assertThat(loginResult("ola", PASSWORD).getResponse().getStatus()).isEqualTo(401);

            MvcResult session = loginResult("ola", temporary);
            assertThat(body(session).at("/user/mustChangePassword").asBoolean()).isTrue();
            String token = body(session).get("accessToken").asText();

            mvc.perform(as(get("/api/users"), token)).andExpect(problem(ErrorCode.AUTH_PASSWORD_CHANGE_REQUIRED));
            mvc.perform(as(jsonPost("/api/games", Map.of("whiteId", ola, "blackId", kari, "result", "WHITE_WIN")), token))
                    .andExpect(problem(ErrorCode.AUTH_PASSWORD_CHANGE_REQUIRED));
            mvc.perform(as(get("/api/users/me"), token)).andExpect(status().isOk());

            mvc.perform(as(jsonPost("/api/auth/change-password", Map.of("currentPassword", temporary, "newPassword", "Olas-nye-passord-99")), token))
                    .andExpect(status().isNoContent());
            String fresh = body(loginResult("ola", "Olas-nye-passord-99")).get("accessToken").asText();
            mvc.perform(as(get("/api/users"), fresh)).andExpect(status().isOk());
        }

        @Test
        void temporaryPasswordCannotBeKeptAsTheNewPassword() throws Exception {
            String temporary = body(resetPassword(adminToken, ola, "TEMPORARY_PASSWORD")).get("temporaryPassword").asText();
            String token = body(loginResult("ola", temporary)).get("accessToken").asText();
            mvc.perform(as(jsonPost("/api/auth/change-password", Map.of("currentPassword", temporary, "newPassword", temporary)), token))
                    .andExpect(problem(ErrorCode.AUTH_WEAK_PASSWORD));
        }

        @Test
        void resettingKicksOutExistingSessions() throws Exception {
            MvcResult olaSession = loginResult("ola", PASSWORD);
            String oldAccess = body(olaSession).get("accessToken").asText();
            Cookie oldRefresh = refreshCookie(olaSession);

            resetPassword(adminToken, ola, "TEMPORARY_PASSWORD");

            mvc.perform(as(get("/api/users/me"), oldAccess)).andExpect(problem(ErrorCode.AUTH_TOKEN_INVALID));
            mvc.perform(MockMvcRequestBuilders.post("/api/auth/refresh").with(fromIp(clientIp)).cookie(oldRefresh)
                    .header(HttpHeaders.ORIGIN, "http://localhost:5173")).andExpect(problem(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));
        }

        @Test
        void unknownUserAndUnknownModeAreReported() throws Exception {
            assertThat(body(resetPassword(adminToken, 987654, "RESET_LINK")).get("code").asText()).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
            assertThat(resetPassword(adminToken, ola, "EMAIL_ME_THE_PASSWORD").getResponse().getStatus()).isEqualTo(400);
        }
    }
}
