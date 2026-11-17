package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Invite-only registration, including the races an invite code invites. */
class RegistrationApiTest extends ApiIntegrationTest {

    private long admin;

    @BeforeEach
    void setUp() {
        admin = createAdmin("admin");
    }

    private Map<String, Object> registration(String code, String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("inviteCode", code);
        body.put("username", username);
        body.put("displayName", "Test " + username);
        body.put("password", PASSWORD);
        return body;
    }

    /** Registers from a fresh address so the per-IP invite rate limit is not what is being tested. */
    private MvcResult register(Map<String, Object> body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/auth/register").with(fromIp(nextIp()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    private int useCount(String code) {
        return jdbc.queryForObject("SELECT use_count FROM invite_code WHERE code = ?", Integer.class, code);
    }

    private int userCount() {
        return jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);
    }

    @Test
    void validInviteCreatesAPlayerAccount() throws Exception {
        createInvite(admin, "JUL-2026", 5, "now() + interval '7 days'");
        MvcResult result = register(registration("JUL-2026", "kari"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("username").asText()).isEqualTo("kari");
        assertThat(body(result).get("role").asText()).isEqualTo("PLAYER");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");
        assertThat(useCount("JUL-2026")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invite_redemption", Integer.class)).isEqualTo(1);
        assertThat(loginResult("kari", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void passwordIsStoredAsABcryptHash() throws Exception {
        createInvite(admin, "JUL-2026", null, null);
        register(registration("JUL-2026", "kari"));
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'kari'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD).hasSize(60);
    }

    @Test
    void registrationWithoutAnInviteIsImpossible() throws Exception {
        Map<String, Object> body = registration(null, "kari");
        assertThat(register(body).getResponse().getStatus()).isEqualTo(400);
        body.put("inviteCode", "");
        assertThat(register(body).getResponse().getStatus()).isEqualTo(400);
        body.remove("inviteCode");
        assertThat(register(body).getResponse().getStatus()).isEqualTo(400);
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void unknownExpiredRevokedAndExhaustedCodesEachHaveTheirOwnErrorCode() throws Exception {
        createInvite(admin, "UTLOPT-KODE", null, "now() - interval '1 second'");
        long revoked = createInvite(admin, "TRUKKET-KODE", null, null);
        jdbc.update("UPDATE invite_code SET revoked_at = now() WHERE id = ?", revoked);
        long used = createInvite(admin, "BRUKT-KODE", 1, null);
        jdbc.update("UPDATE invite_code SET use_count = 1 WHERE id = ?", used);

        assertThat(body(register(registration("FINNES-IKKE", "kari"))).get("code").asText()).isEqualTo(ErrorCode.INVITE_NOT_FOUND.code());
        assertThat(body(register(registration("UTLOPT-KODE", "kari"))).get("code").asText()).isEqualTo(ErrorCode.INVITE_EXPIRED.code());
        assertThat(body(register(registration("TRUKKET-KODE", "kari"))).get("code").asText()).isEqualTo(ErrorCode.INVITE_REVOKED.code());
        assertThat(body(register(registration("BRUKT-KODE", "kari"))).get("code").asText()).isEqualTo(ErrorCode.INVITE_EXHAUSTED.code());
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void codeIsCaseInsensitiveAndTrimmed() throws Exception {
        createInvite(admin, "JUL-2026", null, null);
        assertThat(register(registration("  jul-2026 ", "kari")).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void inviteThatExpiresWhileUnusedStopsWorkingAtTheBoundary() throws Exception {
        long invite = createInvite(admin, "SNART-UTLOPT", null, "now() + interval '1 hour'");
        assertThat(register(registration("SNART-UTLOPT", "kari")).getResponse().getStatus()).isEqualTo(201);
        jdbc.update("UPDATE invite_code SET expires_at = now() WHERE id = ?", invite);
        assertThat(body(register(registration("SNART-UTLOPT", "ola"))).get("code").asText())
                .isEqualTo(ErrorCode.INVITE_EXPIRED.code());
    }

    /** The classic check-then-act race: every request reads use_count = 0 before anyone writes. */
    @Test
    void simultaneousRedemptionsNeverExceedMaxUses() throws Exception {
        createInvite(admin, "TRE-PLASSER", 3, null);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Map<String, Object> body = registration("TRE-PLASSER", "spiller" + i);
            tasks.add(() -> register(body));
        }
        List<MvcResult> results = runConcurrently(tasks);

        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        assertThat(created).isEqualTo(3);
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() != 201) {
                // Losers get the documented business error, not a 500 from a constraint or lock failure.
                assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.INVITE_EXHAUSTED.code());
            }
        }
        assertThat(useCount("TRE-PLASSER")).isEqualTo(3);
        assertThat(userCount()).isEqualTo(1 + 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invite_redemption", Integer.class)).isEqualTo(3);
    }

    @Test
    void simultaneousRedemptionsOfASingleUseCodeCreateExactlyOneAccount() throws Exception {
        createInvite(admin, "EN-PLASS", 1, null);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Map<String, Object> body = registration("EN-PLASS", "spiller" + i);
            tasks.add(() -> register(body));
        }
        List<MvcResult> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() >= 500).count()).isZero();
        assertThat(userCount()).isEqualTo(2);
    }

    @Test
    void unlimitedCodeAcceptsEveryoneAtTheFamilyGathering() throws Exception {
        createInvite(admin, "FAMILIETREFF", null, null);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Map<String, Object> body = registration("FAMILIETREFF", "gjest" + i);
            tasks.add(() -> register(body));
        }
        assertThat(runConcurrently(tasks)).allMatch(r -> r.getResponse().getStatus() == 201);
        // No lost updates on the counter either.
        assertThat(useCount("FAMILIETREFF")).isEqualTo(12);
    }

    @Test
    void redemptionRacingARevocationNeverLeavesAnAccountOnARevokedCodeUncounted() throws Exception {
        long invite = createInvite(admin, "KAPPLOP", null, null);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Map<String, Object> body = registration("KAPPLOP", "spiller" + i);
            tasks.add(() -> register(body).getResponse().getStatus());
        }
        tasks.add(() -> jdbc.update("UPDATE invite_code SET revoked_at = now(), version = version + 1 WHERE id = ?", invite));
        List<Integer> results = runConcurrently(tasks);

        assertThat(results).noneMatch(status -> status >= 500);
        // Whatever the interleaving, the books balance.
        assertThat(useCount("KAPPLOP")).isEqualTo(userCount() - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invite_redemption", Integer.class)).isEqualTo(userCount() - 1);
    }

    @Test
    void failedRegistrationDoesNotConsumeAnInviteUse() throws Exception {
        createInvite(admin, "EN-PLASS", 1, null);
        createPlayer("opptatt");

        assertThat(body(register(registration("EN-PLASS", "opptatt"))).get("code").asText()).isEqualTo(ErrorCode.AUTH_USERNAME_TAKEN.code());
        Map<String, Object> weak = registration("EN-PLASS", "kari");
        weak.put("password", "kort");
        assertThat(body(register(weak)).get("code").asText()).isEqualTo(ErrorCode.AUTH_WEAK_PASSWORD.code());
        assertThat(register(registration("EN-PLASS", "x")).getResponse().getStatus()).isEqualTo(400);

        assertThat(useCount("EN-PLASS")).isZero();
        assertThat(register(registration("EN-PLASS", "kari")).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void twoPeopleRacingForTheSameUsernameConsumeOnlyOneUse() throws Exception {
        createInvite(admin, "TO-PLASSER", 2, null);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> register(registration("TO-PLASSER", "kari")));
        }
        List<MvcResult> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() >= 500).count()).isZero();
        assertThat(useCount("TO-PLASSER")).isEqualTo(1);
    }

    @Test
    void usernameIsNormalisedSoCaseVariantsCannotImpersonate() throws Exception {
        createInvite(admin, "JUL-2026", null, null);
        assertThat(body(register(registration("JUL-2026", "Kari"))).get("username").asText()).isEqualTo("kari");
        assertThat(body(register(registration("JUL-2026", "KARI"))).get("code").asText()).isEqualTo(ErrorCode.AUTH_USERNAME_TAKEN.code());
        assertThat(body(register(registration("JUL-2026", "admin"))).get("code").asText()).isEqualTo(ErrorCode.AUTH_USERNAME_TAKEN.code());
    }

    @Test
    void invalidUsernamesAndDisplayNamesAreValidationErrors() throws Exception {
        createInvite(admin, "JUL-2026", null, null);
        for (String bad : new String[] {"ab", "a".repeat(33), "kari nordmann", "kari<script>", "kari@hjemme", "../admin", "kаri"}) {
            mvc.perform(jsonPost("/api/auth/register", registration("JUL-2026", bad)).with(fromIp(nextIp())))
                    .andExpect(problem(ErrorCode.VALIDATION_FAILED))
                    .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists());
        }
        Map<String, Object> longName = registration("JUL-2026", "kari");
        longName.put("displayName", "K".repeat(65));
        assertThat(register(longName).getResponse().getStatus()).isEqualTo(400);
        assertThat(useCount("JUL-2026")).isZero();
    }

    @Test
    void displayNameMarkupIsNeutralised() throws Exception {
        createInvite(admin, "JUL-2026", null, null);
        Map<String, Object> body = registration("JUL-2026", "kari");
        body.put("displayName", "Kari <img src=x onerror=alert(1)>");
        MvcResult result = register(body);
        if (result.getResponse().getStatus() == 201) {
            assertThat(body(result).get("displayName").asText()).doesNotContain("<", ">");
        } else {
            assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        }
    }

    /** Mass assignment: extra JSON properties must never reach the entity. */
    @Test
    void clientCannotChooseItsOwnRoleRatingOrId() throws Exception {
        createInvite(admin, "JUL-2026", null, null);
        Map<String, Object> body = registration("JUL-2026", "mallory");
        body.put("role", "ADMIN");
        body.put("rating", 2800);
        body.put("id", admin);
        body.put("enabled", true);
        body.put("mustChangePassword", false);
        MvcResult result = register(body);

        assertThat(result.getResponse().getStatus()).isIn(201, 400);
        Integer admins = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = 'ADMIN'", Integer.class);
        assertThat(admins).isEqualTo(1);
        Integer inflated = jdbc.queryForObject("SELECT count(*) FROM player_rating WHERE rating <> 1200", Integer.class);
        assertThat(inflated).isZero();
    }

    @Test
    void sqlInjectionInTheCodeIsJustAnUnknownCode() throws Exception {
        createInvite(admin, "JUL-2026", null, null);
        mvc.perform(jsonPost("/api/auth/register", registration("' OR '1'='1", "kari")).with(fromIp(nextIp())))
                .andExpect(status().is4xxClientError());
        assertThat(userCount()).isEqualTo(1);
    }
}
