package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.util.Map;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Every failure, whatever layer raises it, must come back as a cataloged RFC 9457 document. */
class ProblemDetailsApiTest extends ApiIntegrationTest {

    @Test
    void unauthenticatedRequestGetsAProblemDocumentNotALoginPage() throws Exception {
        mvc.perform(get("/api/users/me").with(fromIp(clientIp)))
                .andExpect(problem(ErrorCode.AUTH_REQUIRED))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void unknownRouteIsACatalogedNotFound() throws Exception {
        createPlayer("kari");
        mvc.perform(as(get("/api/does-not-exist"), login("kari"))).andExpect(problem(ErrorCode.NOT_FOUND));
    }

    @Test
    void unknownRouteDoesNotRevealItselfToAnonymousCallers() throws Exception {
        mvc.perform(get("/api/does-not-exist").with(fromIp(clientIp))).andExpect(problem(ErrorCode.AUTH_REQUIRED));
    }

    @Test
    void malformedJsonIsReportedWithoutParserInternals() throws Exception {
        MvcResult result = mvc.perform(jsonPost("/api/auth/login", "{\"username\": \"kari\", "))
                .andExpect(problem(ErrorCode.MALFORMED_REQUEST)).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Jackson", "JsonParseException", "line:", "column:", "java.", "org.springframework");
    }

    @Test
    void wrongContentTypeIsAMalformedRequest() throws Exception {
        mvc.perform(post("/api/auth/login").with(fromIp(clientIp)).contentType(MediaType.TEXT_PLAIN).content("kari"))
                .andExpect(problem(ErrorCode.MALFORMED_REQUEST));
    }

    @Test
    void wrongMethodIsCataloged() throws Exception {
        mvc.perform(get("/api/auth/login").with(fromIp(clientIp))).andExpect(problem(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @Test
    void validationFailureListsTheOffendingFields() throws Exception {
        mvc.perform(jsonPost("/api/auth/login", Map.of("username", "", "password", "")))
                .andExpect(problem(ErrorCode.VALIDATION_FAILED))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    @Test
    void nonNumericPathIdIsAMalformedRequestNotAServerError() throws Exception {
        createPlayer("kari");
        mvc.perform(as(get("/api/games/not-a-number"), login("kari"))).andExpect(problem(ErrorCode.MALFORMED_REQUEST));
        mvc.perform(as(get("/api/games/99999999999999999999999"), login("kari"))).andExpect(problem(ErrorCode.MALFORMED_REQUEST));
    }

    @Test
    void traceIdInTheBodyMatchesTheResponseHeader() throws Exception {
        MvcResult result = mvc.perform(get("/api/users/me").with(fromIp(clientIp))).andReturn();
        String traceId = body(result).get("traceId").asText();
        assertThat(traceId).matches("[A-Za-z0-9-]{8,64}");
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(traceId);
    }

    @Test
    void successfulResponsesAlsoCarryATraceId() throws Exception {
        createPlayer("kari");
        MvcResult result = mvc.perform(as(get("/api/users/me"), login("kari"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("X-Trace-Id")).matches("[A-Za-z0-9-]{8,64}");
    }

    @Test
    void wellFormedInboundTraceIdIsPropagated() throws Exception {
        mvc.perform(get("/api/users/me").with(fromIp(clientIp)).header("X-Trace-Id", "frontend-3f9c2a17"))
                .andExpect(jsonPath("$.traceId").value("frontend-3f9c2a17"));
    }

    /** A client-supplied trace id ends up in log lines and the debug-info box: it must not be attacker text. */
    @Test
    void hostileInboundTraceIdIsReplaced() throws Exception {
        for (String hostile : new String[] {"<script>alert(1)</script>", "x".repeat(500), "abc def", "a\tb", "';DROP TABLE game;--"}) {
            MvcResult result = mvc.perform(get("/api/users/me").with(fromIp(clientIp)).header("X-Trace-Id", hostile)).andReturn();
            assertThat(body(result).get("traceId").asText()).as("input %s", hostile)
                    .isNotEqualTo(hostile).matches("[A-Za-z0-9-]{8,64}");
        }
    }

    @Test
    void timestampIsACurrentIsoInstant() throws Exception {
        MvcResult result = mvc.perform(get("/api/users/me").with(fromIp(clientIp))).andReturn();
        Instant timestamp = Instant.parse(body(result).get("timestamp").asText());
        assertThat(timestamp).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(5));
    }

    @Test
    void userFacingDetailIsNorwegian() throws Exception {
        mvc.perform(get("/api/users/me").with(fromIp(clientIp)))
                .andExpect(jsonPath("$.detail").value(ErrorCode.AUTH_REQUIRED.defaultDetail()));
    }
}
