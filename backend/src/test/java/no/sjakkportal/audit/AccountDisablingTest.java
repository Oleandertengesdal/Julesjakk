package no.sjakkportal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * An admin can shut an account out (a lost phone, someone who left the family group) and let it back
 * in. The catalog has always promised CHESS-AUTH-006 for this; the audit found nothing could set it.
 */
class AccountDisablingTest extends ApiIntegrationTest {

    private long admin;
    private long kari;
    private String adminT;

    @BeforeEach
    void users() throws Exception {
        admin = createAdmin("admin");
        kari = createPlayer("kari");
        createPlayer("ola");
        adminT = login("admin");
    }

    private static String code(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(r.getResponse().getContentAsString());
        return m.find() ? m.group(1) : "status " + r.getResponse().getStatus();
    }

    private MvcResult disable(long user, String token) throws Exception {
        return mvc.perform(as(post("/api/admin/users/" + user + "/disable"), token)).andReturn();
    }

    @Test
    void aDisabledAccountIsShutOutEverywhereAtOnce() throws Exception {
        MvcResult session = loginResult("kari", PASSWORD);
        String token = body(session).get("accessToken").asText();
        Cookie cookie = refreshCookie(session);

        MvcResult r = disable(kari, adminT);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("enabled").asBoolean()).isFalse();

        assertThat(code(loginResult("kari", PASSWORD))).isEqualTo(ErrorCode.AUTH_ACCOUNT_DISABLED.code());
        assertThat(mvc.perform(as(get("/api/users/me"), token)).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(code(mvc.perform(post("/api/auth/refresh").with(fromIp(clientIp)).cookie(cookie)).andReturn()))
                .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID.code());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'USER_DISABLED'", Integer.class)).isEqualTo(1);
    }

    /** Only someone who knows the password learns that the account is disabled. */
    @Test
    void aWrongPasswordOnADisabledAccountIsStillJustAWrongPassword() throws Exception {
        disable(kari, adminT);
        assertThat(code(loginResult("kari", "Feil-passord-1"))).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.code());
    }

    @Test
    void enablingLetsThePlayerBackIn() throws Exception {
        disable(kari, adminT);
        MvcResult r = mvc.perform(as(post("/api/admin/users/" + kari + "/enable"), adminT)).andReturn();
        assertThat(body(r).get("enabled").asBoolean()).isTrue();
        assertThat(loginResult("kari", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'USER_ENABLED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void anAdminCannotLockThemselvesOut() throws Exception {
        assertThat(code(disable(admin, adminT))).isEqualTo(ErrorCode.USER_CANNOT_DISABLE.code());
        assertThat(loginResult("admin", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void playersCannotDisableAnyone() throws Exception {
        assertThat(code(disable(kari, login("ola")))).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
        assertThat(code(mvc.perform(as(post("/api/admin/users/" + kari + "/enable"), login("ola"))).andReturn()))
                .isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
    }

    @Test
    void unknownUsersAreReported() throws Exception {
        assertThat(code(disable(999_999, adminT))).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
    }

    @Test
    void adminsStillSeeDisabledAccountsSoTheyCanEnableThemButPlayersDoNot() throws Exception {
        disable(kari, adminT);
        String forAdmin = mvc.perform(as(get("/api/users"), adminT)).andReturn().getResponse().getContentAsString();
        assertThat(forAdmin).contains("\"username\":\"kari\"").contains("\"enabled\":false");
        String forPlayer = mvc.perform(as(get("/api/users"), login("ola"))).andReturn().getResponse().getContentAsString();
        assertThat(forPlayer).doesNotContain("\"username\":\"kari\"");
        // the profile and games are still there
        assertThat(mvc.perform(as(get("/api/users/" + kari), login("ola"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theDisableFlagIsNotSettableThroughOtherEndpoints() throws Exception {
        String kariT = login("kari");
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/me/accounts")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"role\":\"ADMIN\"}"), kariT)).andReturn();
        assertThat(jdbc.queryForObject("SELECT enabled FROM app_user WHERE id = ?", Boolean.class, kari)).isTrue();
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, kari)).isEqualTo("PLAYER");
    }
}
