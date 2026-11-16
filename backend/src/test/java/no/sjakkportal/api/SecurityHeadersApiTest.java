package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersApiTest extends ApiIntegrationTest {

    private static final String FRONTEND = "http://localhost:5173";

    private MockHttpServletResponse preflight(String origin, String method) throws Exception {
        return mvc.perform(options("/api/games").with(fromIp(clientIp))
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")).andReturn().getResponse();
    }

    @Test
    void everyResponseCarriesHardeningHeaders() throws Exception {
        createPlayer("kari");
        MockHttpServletResponse ok = mvc.perform(as(get("/api/users/me"), login("kari")).secure(true)).andReturn().getResponse();
        MockHttpServletResponse denied = mvc.perform(get("/api/users/me").with(fromIp(clientIp)).secure(true)).andReturn().getResponse();

        for (MockHttpServletResponse response : new MockHttpServletResponse[] {ok, denied}) {
            // The API only ever serves JSON, so it may load and frame nothing at all.
            assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("frame-ancestors 'none'");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("Strict-Transport-Security")).contains("max-age=");
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getHeader("Server")).isNull();
            assertThat(response.getHeader("X-Powered-By")).isNull();
        }
    }

    @Test
    void configuredFrontendOriginPassesPreflightWithCredentials() throws Exception {
        MockHttpServletResponse response = preflight(FRONTEND, "POST");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(FRONTEND);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).containsIgnoringCase("authorization");
    }

    @Test
    void otherOriginsAreNeverAllowed() throws Exception {
        for (String origin : new String[] {"https://evil.example", "http://localhost:5173.evil.example", "http://localhost:51730",
                "https://localhost:5173", "null", "http://LOCALHOST:5173@evil.example"}) {
            MockHttpServletResponse response = preflight(origin, "POST");
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).as("origin %s", origin).isNull();
        }
    }

    @Test
    void wildcardOriginIsNeverCombinedWithCredentials() throws Exception {
        MockHttpServletResponse response = preflight(FRONTEND, "GET");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNotEqualTo("*");
        assertThat(response.getHeader(HttpHeaders.VARY)).containsIgnoringCase("Origin");
    }

    @Test
    void actuatorExposesNothingButHealthToAnonymousCallers() throws Exception {
        assertThat(mvc.perform(get("/actuator/health").with(fromIp(clientIp))).andReturn().getResponse().getStatus()).isEqualTo(200);
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/configprops",
                "/actuator/mappings", "/actuator"}) {
            assertThat(mvc.perform(get(path).with(fromIp(clientIp))).andReturn().getResponse().getStatus())
                    .as(path).isIn(401, 403, 404);
        }
        assertThat(mvc.perform(get("/actuator/health").with(fromIp(clientIp))).andReturn().getResponse().getContentAsString())
                .doesNotContain("postgres", "jdbc", "diskSpace");
    }
}
