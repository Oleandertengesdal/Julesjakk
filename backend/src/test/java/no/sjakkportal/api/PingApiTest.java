package no.sjakkportal.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/** The browser polls /api/ping while a sleeping free-tier server starts; it must be public, cheap and uncached. */
class PingApiTest extends ApiIntegrationTest {

    @Test
    void answersWithoutLoginAndIsNeverCached() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
}
