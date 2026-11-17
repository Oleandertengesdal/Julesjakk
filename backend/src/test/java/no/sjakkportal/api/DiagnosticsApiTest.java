package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/** Admin-only view of how the server sees a request, for setting up the client-address rule after deploying. */
class DiagnosticsApiTest extends ApiIntegrationTest {

    @Test
    void showsTheAddressUsedForRateLimitsAndTheForwardingHeaders() throws Exception {
        createAdmin("admin");
        JsonNode body = body(mvc.perform(as(get("/api/admin/diagnostics/request"), login("admin"))
                .header("X-Forwarded-For", "198.51.100.7").header("Cookie", "sjakkportal_refresh=secret")).andReturn());
        assertThat(body.get("remoteAddress").asText()).isEqualTo(clientIp);
        assertThat(body.get("clientAddressUsedForRateLimits").asText()).isEqualTo(clientIp);
        assertThat(body.at("/forwardingHeaders/X-Forwarded-For").asText()).isEqualTo("198.51.100.7");
        // Credentials never echo back, even to an admin.
        assertThat(body.toString()).doesNotContain("secret", "Bearer");
    }

    @Test
    void isAdminOnly() throws Exception {
        createPlayer("kari");
        mvc.perform(as(get("/api/admin/diagnostics/request"), login("kari"))).andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));
    }
}
