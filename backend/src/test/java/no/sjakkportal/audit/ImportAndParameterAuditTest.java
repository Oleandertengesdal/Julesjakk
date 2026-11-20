package no.sjakkportal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.importer.ExternalGameFetcher;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/** Audit: the importer must never be talked into fetching other hosts; odd parameters are client errors. */
class ImportAndParameterAuditTest extends ApiIntegrationTest {

    @MockitoBean
    private ExternalGameFetcher fetcher;

    private String kariT;
    private long kari;
    private long ola;

    @BeforeEach
    void users() throws Exception {
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        kariT = login("kari");
    }

    private static String code(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(r.getResponse().getContentAsString());
        return m.find() ? m.group(1) : "status " + r.getResponse().getStatus();
    }

    @Test
    void linksThatOnlyLookLikeLichessOrChessComAreNeverFetched() throws Exception {
        for (String url : List.of(
                "http://169.254.169.254/latest/meta-data/",
                "https://lichess.org.evil.example/AbCd1234",
                "https://evil.example/lichess.org/AbCd1234",
                "https://evil.example/?u=https://lichess.org/AbCd1234",
                "https://lichess.org@evil.example/AbCd1234",
                "file:///etc/passwd",
                "javascript:alert(1)",
                "https://www.chess.com.evil.example/game/live/123456789",
                "https://localhost:8080/api/users",
                "https://lichess.org/../../etc/passwd",
                "https://lichess.org/AbCd1234%2F..%2F..%2Fapi")) {
            MvcResult r = mvc.perform(as(jsonPost("/api/games/import", Map.of("url", url)), kariT)).andReturn();
            assertThat(code(r)).as(url).isIn(ErrorCode.IMPORT_UNSUPPORTED_SOURCE.code(), ErrorCode.VALIDATION_FAILED.code());
        }
        verify(fetcher, never()).fetchPgn(any());
    }

    @Test
    void nonJsonBodiesAreClientErrors() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").with(fromIp(clientIp)).contentType(MediaType.TEXT_PLAIN).content("username=kari"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isBetween(400, 415);
        MvcResult xml = mvc.perform(post("/api/auth/login").with(fromIp(clientIp)).contentType(MediaType.APPLICATION_XML)
                .content("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>")).andReturn();
        assertThat(xml.getResponse().getStatus()).isBetween(400, 415);
        assertThat(xml.getResponse().getContentAsString()).doesNotContain("root:");
    }

    @Test
    void badQueryParametersAreClientErrors() throws Exception {
        for (String path : List.of("/api/leaderboard?category=HYPERBULLET", "/api/users/" + kari + "/stats?category=x",
                "/api/matchups?a=" + kari + "&b=abc", "/api/matchups?a=" + kari, "/api/users/" + kari + "/games?before=xyz",
                "/api/users/" + kari + "/rating-history?category=")) {
            MvcResult r = mvc.perform(as(get(path), kariT)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isBetween(400, 422);
            assertThat(code(r)).as(path).startsWith("CHESS-");
        }
    }

    @Test
    void aMatchupOfAPlayerAgainstThemselvesIsHarmless() throws Exception {
        MvcResult r = mvc.perform(as(get("/api/matchups?a=" + kari + "&b=" + kari), kariT)).andReturn();
        assertThat(r.getResponse().getStatus()).isLessThan(500);
        assertThat(ola).isPositive();
    }
}
