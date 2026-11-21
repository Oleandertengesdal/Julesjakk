package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A phone at the board runs the chess clock for a live game and sends the clock's state on every
 * press. The server keeps the latest state and passes it on, so the TV and the game page can count
 * down too. The phone keeps time; the server never ticks the clock itself.
 */
class ChessClockApiTest extends ApiIntegrationTest {

    private long kari;
    private long ola;
    private String kariT;
    private String olaT;

    @BeforeEach
    void users() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        createPlayer("per");
        kariT = login("kari");
        olaT = login("ola");
    }

    private static String code(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(r.getResponse().getContentAsString());
        return m.find() ? m.group(1) : "status " + r.getResponse().getStatus();
    }

    private long liveGame(String timeControl) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("whiteId", kari, "blackId", ola));
        if (timeControl != null) {
            body.put("timeControl", timeControl);
        }
        return body(mvc.perform(as(jsonPost("/api/games/live", body), kariT)).andReturn()).get("id").asLong();
    }

    private MvcResult clock(String token, long game, Object snapshot) throws Exception {
        return mvc.perform(as(put("/api/games/" + game + "/clock").contentType(MediaType.APPLICATION_JSON)
                .content(snapshot instanceof String s ? s : json.writeValueAsString(snapshot)), token)).andReturn();
    }

    private static Map<String, Object> snapshot(long whiteMs, long blackMs, String running, int sequence) {
        Map<String, Object> s = new HashMap<>(Map.of("whiteMs", whiteMs, "blackMs", blackMs, "sequence", sequence));
        s.put("running", running);
        return s;
    }

    @Test
    void aPlayerSendsTheClockAndEveryoneCanReadIt() throws Exception {
        long g = liveGame("15+10");
        MvcResult r = clock(kariT, g, snapshot(900_000, 900_000, "WHITE", 1));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode c = body(r);
        assertThat(c.get("initialMs").asLong()).isEqualTo(900_000);
        assertThat(c.get("incrementMs").asLong()).isEqualTo(10_000);
        assertThat(c.get("whiteMs").asLong()).isEqualTo(900_000);
        assertThat(c.get("running").asText()).isEqualTo("WHITE");
        assertThat(c.get("sequence").asInt()).isEqualTo(1);
        Instant updated = Instant.parse(c.get("updatedAt").asText());
        Instant serverNow = Instant.parse(c.get("serverNow").asText());
        assertThat(Duration.between(updated, serverNow).toMillis()).isBetween(0L, 2_000L);

        JsonNode game = body(mvc.perform(as(get("/api/games/" + g), login("per"))).andReturn());
        assertThat(game.at("/clock/whiteMs").asLong()).isEqualTo(900_000);
        assertThat(game.at("/clock/running").asText()).isEqualTo("WHITE");
        assertThat(game.at("/clock/serverNow").asText()).isNotBlank();
    }

    @Test
    void aLiveGameWithoutAClockHasNoClockInItsView() throws Exception {
        long g = liveGame("15+10");
        assertThat(body(mvc.perform(as(get("/api/games/" + g), kariT)).andReturn()).has("clock")).isFalse();
    }

    @Test
    void eitherPlayerOrAnAdminCanRunTheClockButNobodyElse() throws Exception {
        long g = liveGame("5+3");
        assertThat(clock(olaT, g, snapshot(300_000, 300_000, "WHITE", 1)).getResponse().getStatus()).isEqualTo(200);
        assertThat(clock(login("admin"), g, snapshot(300_000, 300_000, "BLACK", 2)).getResponse().getStatus()).isEqualTo(200);
        assertThat(code(clock(login("per"), g, snapshot(1, 1, null, 3)))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
    }

    @Test
    void anOlderSnapshotArrivingLateIsRefusedAndChangesNothing() throws Exception {
        long g = liveGame("5+3");
        clock(kariT, g, snapshot(290_000, 300_000, "BLACK", 5));
        assertThat(code(clock(kariT, g, snapshot(300_000, 300_000, "WHITE", 4)))).isEqualTo(ErrorCode.GAME_CONCURRENT_UPDATE.code());
        assertThat(code(clock(kariT, g, snapshot(300_000, 300_000, "WHITE", 5)))).isEqualTo(ErrorCode.GAME_CONCURRENT_UPDATE.code());
        JsonNode game = body(mvc.perform(as(get("/api/games/" + g), kariT)).andReturn());
        assertThat(game.at("/clock/running").asText()).isEqualTo("BLACK");
        assertThat(game.at("/clock/whiteMs").asLong()).isEqualTo(290_000);
    }

    @Test
    void onlyLiveGamesWithATimeControlHaveAClock() throws Exception {
        long untimed = liveGame(null);
        assertThat(code(clock(kariT, untimed, snapshot(1000, 1000, "WHITE", 1)))).isEqualTo(ErrorCode.GAME_INVALID_TIME_CONTROL.code());
        long g = liveGame("5+3");
        mvc.perform(as(jsonPost("/api/games/" + g + "/abort", Map.of()), kariT));
        assertThat(code(clock(kariT, g, snapshot(1000, 1000, "WHITE", 1)))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
    }

    @Test
    void impossibleClockValuesAreRefused() throws Exception {
        long g = liveGame("5+3");
        assertThat(code(clock(kariT, g, snapshot(-1, 1000, "WHITE", 1)))).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        assertThat(code(clock(kariT, g, snapshot(25L * 3_600_000, 1000, "WHITE", 1)))).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        assertThat(code(clock(kariT, g, snapshot(1000, 1000, "WHITE", -1)))).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        assertThat(clock(kariT, g, "{\"whiteMs\":1,\"blackMs\":1,\"running\":\"GREEN\",\"sequence\":1}").getResponse().getStatus())
                .isBetween(400, 422);
    }

    @Test
    void finishingTheGameStopsTheClockWhereItWas() throws Exception {
        long g = liveGame("1+0");
        clock(kariT, g, snapshot(60_000, 60_000, "WHITE", 1));
        Thread.sleep(400);
        mvc.perform(as(jsonPost("/api/games/" + g + "/finish", Map.of("result", "BLACK_WIN", "termination", "RESIGNATION")), kariT));
        JsonNode c = body(mvc.perform(as(get("/api/games/" + g), kariT)).andReturn()).get("clock");
        assertThat(c.path("running").isNull() || c.path("running").isMissingNode()).isTrue();
        assertThat(c.get("whiteMs").asLong()).isBetween(1L, 59_700L);
        assertThat(c.get("blackMs").asLong()).isEqualTo(60_000);
    }

    @Test
    void aBusyClockIsNotSlowedByTheGameSubmissionLimit() throws Exception {
        long g = liveGame("3+2");
        for (int i = 1; i <= 60; i++) {
            MvcResult r = clock(i % 2 == 0 ? kariT : olaT, g, snapshot(180_000 - i * 100L, 180_000, i % 2 == 0 ? "WHITE" : "BLACK", i));
            assertThat(r.getResponse().getStatus()).as("press %d", i).isEqualTo(200);
        }
    }
}
