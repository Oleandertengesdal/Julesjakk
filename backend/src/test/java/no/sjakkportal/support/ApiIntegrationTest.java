package no.sjakkportal.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import no.sjakkportal.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base class for black-box API tests: the full application against a real PostgreSQL database,
 * driven only through HTTP. Test data is inserted with SQL so the tests do not depend on the
 * code they are testing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiIntegrationTest {

    public static final String PASSWORD = "Sjakk-er-goy-2026";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper json;

    /** A client address unique to this test, so in-memory rate limits never leak between tests. */
    protected String clientIp;

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE TABLE user_badge, tournament_pairing, rating_history, game_analysis, game_move, game,
                    tournament_round, tournament_participant, tournament, audit_log, password_reset_token,
                    refresh_token, invite_redemption, invite_code, app_user
                RESTART IDENTITY CASCADE""");
        clientIp = nextIp();
    }

    protected static String nextIp() {
        int n = ADDRESSES.incrementAndGet();
        return "10." + (n / 62500 % 250) + "." + (n / 250 % 250) + "." + (n % 250 + 1);
    }

    // --- data setup -----------------------------------------------------------------------

    protected long createUser(String username, String role) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (username, display_name, password_hash, role)
                VALUES (?, ?, ?, ?) RETURNING id""", Long.class, username, capitalize(username), PASSWORD_HASH, role);
    }

    protected long createPlayer(String username) {
        return createUser(username, "PLAYER");
    }

    protected long createAdmin(String username) {
        return createUser(username, "ADMIN");
    }

    protected long createInvite(long adminId, String code, Integer maxUses, String expiresAtSql) {
        return jdbc.queryForObject("INSERT INTO invite_code (code, created_by, max_uses, expires_at) VALUES (?, ?, ?, "
                + (expiresAtSql == null ? "NULL" : expiresAtSql) + ") RETURNING id", Long.class, code, adminId, maxUses);
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    // --- requests -------------------------------------------------------------------------

    protected RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, Object body) throws Exception {
        return post(url).with(fromIp(clientIp)).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String accessToken) {
        return builder.with(fromIp(clientIp)).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    protected MvcResult loginResult(String username, String password) throws Exception {
        return mvc.perform(jsonPost("/api/auth/login", Map.of("username", username, "password", password))).andReturn();
    }

    /** Logs in and returns the access token, failing the test if login does not succeed. */
    protected String login(String username) throws Exception {
        MvcResult result = loginResult(username, PASSWORD);
        assertThat(result.getResponse().getStatus()).as("login as %s: %s", username, result.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(result).get("accessToken").asText();
    }

    protected Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("sjakkportal_refresh");
        assertThat(cookie).as("refresh cookie").isNotNull();
        return cookie;
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // --- assertions -----------------------------------------------------------------------

    /** The response is an RFC 9457 problem document carrying exactly this catalog entry. */
    protected static ResultMatcher problem(ErrorCode expected) {
        return ResultMatcher.matchAll(
                status().is(expected.status().value()),
                content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                jsonPath("$.code").value(expected.code()),
                jsonPath("$.status").value(expected.status().value()),
                jsonPath("$.title").value(expected.title()),
                jsonPath("$.detail").isNotEmpty(),
                jsonPath("$.type").isNotEmpty(),
                jsonPath("$.timestamp").isNotEmpty(),
                jsonPath("$.traceId").isNotEmpty(),
                jsonPath("$.trace").doesNotExist(),
                jsonPath("$.stackTrace").doesNotExist(),
                jsonPath("$.exception").doesNotExist());
    }

    // --- concurrency ----------------------------------------------------------------------

    /**
     * Runs the tasks on separate threads, released at the same instant by a barrier, and returns
     * their results. Any exception thrown by a task fails the test.
     */
    protected static <R> List<R> runConcurrently(List<Callable<R>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        try {
            List<Future<R>> futures = new ArrayList<>();
            for (Callable<R> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<R> results = new ArrayList<>();
            for (Future<R> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
