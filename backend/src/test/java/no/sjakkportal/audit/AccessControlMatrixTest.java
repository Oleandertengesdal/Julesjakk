package no.sjakkportal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Audit: every endpoint, as documented in docs/API_AND_ERRORS.md, against every kind of caller.
 * Written from the documentation, not the code. A new endpoint that is not classified here fails
 * {@link #everyMappedEndpointIsClassifiedAndDocumented()}.
 */
class AccessControlMatrixTest extends ApiIntegrationTest {

    enum Access { PUBLIC, PLAYER, ADMIN }

    record Endpoint(String method, String template, Access access, Object body) {
        String key() {
            return method + " " + template;
        }
    }

    private static final Map<String, Object> GAME = Map.of("whiteId", 2, "blackId", 3, "result", "DRAW", "timeControl", "5+3");

    /** Every documented endpoint and who may call it. {id} and {userId} are filled with existing ids. */
    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("POST", "/api/auth/register", Access.PUBLIC, Map.of()),
            new Endpoint("POST", "/api/auth/login", Access.PUBLIC, Map.of()),
            new Endpoint("POST", "/api/auth/refresh", Access.PUBLIC, null),
            new Endpoint("POST", "/api/auth/logout", Access.PUBLIC, null),
            new Endpoint("POST", "/api/auth/change-password", Access.PLAYER, Map.of("currentPassword", "x", "newPassword", "y")),
            new Endpoint("POST", "/api/auth/reset-password", Access.PUBLIC, Map.of()),
            new Endpoint("GET", "/api/users/me", Access.PLAYER, null),
            new Endpoint("PUT", "/api/users/me/accounts", Access.PLAYER, Map.of()),
            new Endpoint("GET", "/api/users", Access.PLAYER, null),
            new Endpoint("GET", "/api/users/{id}", Access.PLAYER, null),
            new Endpoint("GET", "/api/users/{id}/stats", Access.PLAYER, null),
            new Endpoint("GET", "/api/users/{id}/rating-history", Access.PLAYER, null),
            new Endpoint("GET", "/api/users/{id}/games", Access.PLAYER, null),
            new Endpoint("GET", "/api/leaderboard", Access.PLAYER, null),
            new Endpoint("GET", "/api/matchups", Access.PLAYER, null),
            new Endpoint("GET", "/api/games", Access.PLAYER, null),
            new Endpoint("POST", "/api/games", Access.PLAYER, GAME),
            new Endpoint("GET", "/api/games/{id}", Access.PLAYER, null),
            new Endpoint("GET", "/api/games/{id}/preview", Access.PLAYER, null),
            new Endpoint("GET", "/api/games/pending", Access.PLAYER, null),
            new Endpoint("POST", "/api/games/{id}/confirm", Access.PLAYER, null),
            new Endpoint("POST", "/api/games/{id}/reject", Access.PLAYER, Map.of()),
            new Endpoint("POST", "/api/games/live", Access.PLAYER, Map.of("whiteId", 2, "blackId", 3)),
            new Endpoint("POST", "/api/games/{id}/moves", Access.PLAYER, Map.of("move", "e4", "expectedPly", 0)),
            new Endpoint("POST", "/api/games/{id}/finish", Access.PLAYER, Map.of("result", "DRAW")),
            new Endpoint("POST", "/api/games/{id}/abort", Access.PLAYER, null),
            new Endpoint("POST", "/api/games/{id}/start", Access.PLAYER, null),
            new Endpoint("POST", "/api/games/{id}/report", Access.PLAYER, Map.of("result", "DRAW")),
            new Endpoint("GET", "/api/games/{id}/analysis", Access.PLAYER, null),
            new Endpoint("PUT", "/api/games/{id}/analysis", Access.PLAYER, Map.of()),
            new Endpoint("POST", "/api/games/import", Access.PLAYER, Map.of("url", "https://lichess.org/AbCd1234")),
            new Endpoint("GET", "/api/tournaments", Access.PLAYER, null),
            new Endpoint("GET", "/api/tournaments/{id}", Access.PLAYER, null),
            new Endpoint("POST", "/api/tournaments", Access.ADMIN, Map.of("name", "Cup", "format", "ROUND_ROBIN", "timeControl", "5+3")),
            new Endpoint("POST", "/api/tournaments/{id}/participants", Access.ADMIN, Map.of("userId", 2)),
            new Endpoint("DELETE", "/api/tournaments/{id}/participants/{userId}", Access.ADMIN, null),
            new Endpoint("POST", "/api/tournaments/{id}/join", Access.PLAYER, null),
            new Endpoint("POST", "/api/tournaments/{id}/leave", Access.PLAYER, null),
            new Endpoint("POST", "/api/tournaments/{id}/start", Access.ADMIN, null),
            new Endpoint("POST", "/api/tournaments/{id}/finish", Access.ADMIN, null),
            new Endpoint("POST", "/api/tournaments/{id}/cancel", Access.ADMIN, null),
            new Endpoint("POST", "/api/tournaments/{id}/arena-games", Access.PLAYER, GAME),
            new Endpoint("GET", "/api/admin/invites", Access.ADMIN, null),
            new Endpoint("POST", "/api/admin/invites", Access.ADMIN, Map.of("maxUses", 1)),
            new Endpoint("DELETE", "/api/admin/invites/{id}", Access.ADMIN, null),
            new Endpoint("POST", "/api/admin/users/{id}/password-reset", Access.ADMIN, Map.of("mode", "RESET_LINK")),
            new Endpoint("POST", "/api/admin/users/{id}/disable", Access.ADMIN, null),
            new Endpoint("POST", "/api/admin/users/{id}/enable", Access.ADMIN, null),
            new Endpoint("POST", "/api/admin/players", Access.ADMIN, Map.of("username", "lille", "displayName", "Lille")),
            new Endpoint("GET", "/api/admin/games/pending", Access.ADMIN, null),
            new Endpoint("POST", "/api/admin/games/{id}/feature", Access.ADMIN, Map.of("featured", true)),
            new Endpoint("GET", "/api/admin/diagnostics/request", Access.ADMIN, null),
            new Endpoint("GET", "/api/tv", Access.PLAYER, null),
            new Endpoint("GET", "/api/ping", Access.PUBLIC, null),
            new Endpoint("GET", "/actuator/health", Access.PUBLIC, null));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private long admin;
    private long kari;
    private long ola;
    private long gameId;
    private long tournamentId;
    private long inviteId;

    private void seed() throws Exception {
        admin = createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        gameId = body(mvc.perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "DRAW", "timeControl", "5+3")),
                login("kari"))).andReturn()).get("id").asLong();
        String token = login("admin");
        MvcResult created = mvc.perform(as(jsonPost("/api/tournaments",
                Map.of("name", "Julecup", "format", "ROUND_ROBIN", "timeControl", "5+3")), token)).andReturn();
        tournamentId = body(created).get("id").asLong();
        inviteId = createInvite(admin, "MATRIX-1", 5, null);
    }

    private String path(Endpoint e) {
        return e.template().replace("{id}", String.valueOf(e.template().startsWith("/api/tournaments") ? tournamentId
                        : e.template().startsWith("/api/admin/invites") ? inviteId
                        : e.template().startsWith("/api/users") || e.template().startsWith("/api/admin/users") ? kari : gameId))
                .replace("{userId}", String.valueOf(kari));
    }

    private MvcResult call(Endpoint e, String bearer) throws Exception {
        var builder = request(HttpMethod.valueOf(e.method()), path(e)).with(fromIp(nextIp()));
        if (e.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(e.body()));
        }
        if (bearer != null) {
            builder.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return mvc.perform(builder).andReturn();
    }

    private static String code(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
        return m.find() ? m.group(1) : "(status " + r.getResponse().getStatus() + ") " + text.substring(0, Math.min(120, text.length()));
    }

    @TestFactory
    Stream<DynamicTest> anonymousCallersAreRefusedEverywhereExceptPublicEndpoints() throws Exception {
        seed();
        return ENDPOINTS.stream().filter(e -> e.access() != Access.PUBLIC).map(e -> DynamicTest.dynamicTest(e.key(), () -> {
            MvcResult r = call(e, null);
            assertThat(r.getResponse().getStatus()).as(e.key()).isEqualTo(401);
            assertThat(code(r)).as(e.key()).isEqualTo(ErrorCode.AUTH_REQUIRED.code());
        }));
    }

    @TestFactory
    Stream<DynamicTest> forgedOrMalformedTokensAreRefusedEverywhere() throws Exception {
        seed();
        String forged = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidiI6MH0.c2lnbmF0dXJlLWZyb20tc29tZW9uZS1lbHNl";
        return ENDPOINTS.stream().filter(e -> e.access() != Access.PUBLIC).flatMap(e -> Stream.of("Bearer " + forged, "Bearer x", "Basic YWRtaW46eA==")
                .map(header -> DynamicTest.dynamicTest(e.key() + " with " + header.substring(0, Math.min(14, header.length())), () -> {
                    MvcResult r = call(e, header);
                    assertThat(r.getResponse().getStatus()).as(e.key()).isEqualTo(401);
                })));
    }

    @TestFactory
    Stream<DynamicTest> playersAreRefusedOnAdminEndpointsBeforeAnythingElseHappens() throws Exception {
        seed();
        String kariToken = login("kari");
        return ENDPOINTS.stream().filter(e -> e.access() == Access.ADMIN).map(e -> DynamicTest.dynamicTest(e.key(), () -> {
            MvcResult r = call(e, "Bearer " + kariToken);
            assertThat(code(r)).as(e.key()).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
        }));
    }

    /** A player probing admin endpoints with ids that do not exist learns nothing: still 403, never 404. */
    @Test
    void adminEndpointsDoNotRevealWhichIdsExist() throws Exception {
        seed();
        String kariToken = login("kari");
        for (String path : List.of("/api/tournaments/999999/start", "/api/admin/games/999999/feature", "/api/tournaments/999999/cancel")) {
            MvcResult r = mvc.perform(request(HttpMethod.POST, path).with(fromIp(nextIp())).header(HttpHeaders.AUTHORIZATION, "Bearer " + kariToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"featured\":true}")).andReturn();
            assertThat(code(r)).as(path).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
        }
    }

    @Test
    void publicEndpointsAnswerWithoutAToken() throws Exception {
        seed();
        assertThat(call(find("GET /api/ping"), null).getResponse().getStatus()).isEqualTo(204);
        assertThat(call(find("GET /actuator/health"), null).getResponse().getStatus()).isEqualTo(200);
        // Wrong credentials are a 401 from the endpoint itself, not from the security filter.
        assertThat(code(mvc.perform(jsonPost("/api/auth/login", Map.of("username", "kari", "password", "wrong"))).andReturn()))
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.code());
    }

    private static Endpoint find(String key) {
        return ENDPOINTS.stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }

    // --- inventory ---------------------------------------------------------------------------

    private static String normalise(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    private Set<String> mappedEndpoints() {
        Set<String> found = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            Set<String> patterns = info.getPatternValues();
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (!p.startsWith("/api")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    found.add("ANY " + normalise(p));
                }
                methods.forEach(m -> found.add(m.name() + " " + normalise(p)));
            }
        });
        return found;
    }

    private static Set<String> documentedEndpoints() throws IOException {
        String doc = Files.readString(Path.of("../docs/API_AND_ERRORS.md"));
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\| (GET|POST|PUT|DELETE|PATCH) \\| `(/api[^`?]*)").matcher(doc);
        while (m.find()) {
            found.add(m.group(1) + " " + normalise(m.group(2)));
        }
        return found;
    }

    @Test
    void everyMappedEndpointIsClassifiedAndDocumented() throws IOException {
        Set<String> mapped = mappedEndpoints();
        Set<String> classified = ENDPOINTS.stream().filter(e -> e.template().startsWith("/api"))
                .map(e -> e.method() + " " + normalise(e.template())).collect(Collectors.toCollection(TreeSet::new));
        Set<String> documented = documentedEndpoints();

        List<String> problems = new ArrayList<>();
        mapped.stream().filter(e -> !classified.contains(e)).forEach(e -> problems.add("not classified in this test: " + e));
        mapped.stream().filter(e -> !documented.contains(e)).forEach(e -> problems.add("not documented: " + e));
        documented.stream().filter(e -> !mapped.contains(e)).forEach(e -> problems.add("documented but does not exist: " + e));
        assertThat(problems).isEmpty();
    }
}
