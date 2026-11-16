package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Game submission is limited per user (default 30/min); the limit is lowered here to reach the boundary quickly. */
@TestPropertySource(properties = {
        "sjakkportal.rate-limit.game-submission.capacity=3",
        "sjakkportal.rate-limit.game-submission.period=1m"})
class GameSubmissionRateLimitApiTest extends ApiIntegrationTest {

    @Test
    void submissionsBeyondTheLimitAreRejectedWithoutSideEffects() throws Exception {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        String kariToken = login("kari");
        Map<String, Object> game = Map.of("whiteId", kari, "blackId", ola, "result", "WHITE_WIN");

        for (int i = 1; i <= 3; i++) {
            assertThat(mvc.perform(as(jsonPost("/api/games", game), kariToken)).andReturn().getResponse().getStatus())
                    .as("submission %d", i).isEqualTo(201);
        }
        MvcResult fourth = mvc.perform(as(jsonPost("/api/games", game), kariToken)).andReturn();

        assertThat(fourth.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(fourth).get("code").asText()).isEqualTo(ErrorCode.RATE_LIMITED.code());
        assertThat(fourth.getResponse().getHeader("Retry-After")).matches("\\d+");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT rated_games FROM app_user WHERE id = ?", Integer.class, kari)).isEqualTo(3);
    }

    @Test
    void theLimitFollowsTheUserNotTheAddress() throws Exception {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        String kariToken = login("kari");
        String olaToken = login("ola");
        Map<String, Object> game = Map.of("whiteId", kari, "blackId", ola, "result", "DRAW");

        for (int i = 0; i < 3; i++) {
            mvc.perform(as(jsonPost("/api/games", game), kariToken));
        }
        // Kari hopping to another network is still limited; Ola on Kari's network is not.
        clientIp = nextIp();
        assertThat(mvc.perform(as(jsonPost("/api/games", game), kariToken)).andReturn().getResponse().getStatus()).isEqualTo(429);
        assertThat(mvc.perform(as(jsonPost("/api/games", game), olaToken)).andReturn().getResponse().getStatus()).isEqualTo(201);
    }
}
