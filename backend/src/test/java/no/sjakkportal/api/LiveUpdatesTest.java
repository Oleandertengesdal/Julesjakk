package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Spectators and TV mode follow games over STOMP on {@code /ws}. Topics:
 * {@code /topic/games/{id}}, {@code /topic/tv} and {@code /topic/tournaments/{id}}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveUpdatesTest extends ApiIntegrationTest {

    @LocalServerPort
    private int port;

    private final List<StompSession> sessions = new ArrayList<>();
    private WebSocketStompClient client;
    private long kari;
    private long ola;
    private String kariToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        kariToken = login("kari");
        adminToken = login("admin");
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String topic) throws Exception {
        BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
        session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((JsonNode) payload);
            }
        });
        // Give the broker a moment to register the subscription before anything is published.
        Thread.sleep(150);
        return queue;
    }

    /** Waits for a message that satisfies the condition, skipping older updates. */
    private JsonNode await(BlockingQueue<JsonNode> queue, java.util.function.Predicate<JsonNode> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (message != null && condition.test(message)) {
                return message;
            }
        }
        throw new AssertionError("No matching message within 5 seconds");
    }

    private long startLive(long white, long black) throws Exception {
        MvcResult result = mvc.perform(as(jsonPost("/api/games/live", Map.of("whiteId", white, "blackId", black)), adminToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private MvcResult move(long game, String move, int expectedPly) throws Exception {
        return mvc.perform(as(jsonPost("/api/games/" + game + "/moves", Map.of("move", move, "expectedPly", expectedPly)), adminToken))
                .andReturn();
    }

    @Test
    void connectingWithoutAValidTokenIsRefused() {
        assertThat(catchThrowable(() -> connect(null))).isNotNull();
        assertThat(catchThrowable(() -> connect("not-a-token"))).isNotNull();
    }

    @Test
    void connectingWithADisabledAccountIsRefused() throws Exception {
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", kari);
        assertThat(catchThrowable(() -> connect(kariToken))).isNotNull();
    }

    @Test
    void spectatorsReceiveEveryMoveAsItIsEntered() throws Exception {
        long game = startLive(kari, ola);
        BlockingQueue<JsonNode> updates = subscribe(connect(kariToken), "/topic/games/" + game);

        assertThat(move(game, "e4", 0).getResponse().getStatus()).isEqualTo(200);
        JsonNode update = await(updates, m -> m.at("/game/plyCount").asInt() == 1);
        assertThat(update.get("type").asText()).isEqualTo("game");
        assertThat(update.at("/game/moves/0/san").asText()).isEqualTo("e4");
        assertThat(update.at("/game/currentFen").asText()).startsWith("rnbqkbnr/pppppppp/8/8/4P3/");
        assertThat(update.toString()).doesNotContain("password", "tokenVersion");

        move(game, "e5", 1);
        assertThat(await(updates, m -> m.at("/game/plyCount").asInt() == 2).at("/game/moves/1/san").asText()).isEqualTo("e5");
    }

    /** The TV and spectators show the clock the phone at the board is running. */
    @Test
    void clockPressesReachTheGamePageAndTheTv() throws Exception {
        MvcResult started = mvc.perform(as(jsonPost("/api/games/live", Map.of("whiteId", kari, "blackId", ola, "timeControl", "5+3")),
                adminToken)).andReturn();
        long game = body(started).get("id").asLong();
        StompSession session = connect(kariToken);
        BlockingQueue<JsonNode> page = subscribe(session, "/topic/games/" + game);
        BlockingQueue<JsonNode> tv = subscribe(session, "/topic/tv");

        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/games/" + game + "/clock")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"whiteMs\":297000,\"blackMs\":300000,\"running\":\"BLACK\",\"sequence\":2}"), kariToken));

        JsonNode onPage = await(page, m -> m.at("/game/clock/sequence").asInt() == 2);
        assertThat(onPage.at("/game/clock/running").asText()).isEqualTo("BLACK");
        assertThat(onPage.at("/game/clock/whiteMs").asLong()).isEqualTo(297_000);
        JsonNode onTv = await(tv, m -> m.at("/game/clock/sequence").asInt() == 2);
        assertThat(onTv.at("/game/clock/blackMs").asLong()).isEqualTo(300_000);
    }

    @Test
    void rejectedMovesAreNeverBroadcast() throws Exception {
        long game = startLive(kari, ola);
        BlockingQueue<JsonNode> updates = subscribe(connect(kariToken), "/topic/games/" + game);
        assertThat(move(game, "e5", 0).getResponse().getStatus()).isEqualTo(422);
        assertThat(updates.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void tvFollowsTheFeaturedGameAndSwitchesWhenItEnds() throws Exception {
        long featured = startLive(kari, ola);
        long other = startLive(ola, kari);
        mvc.perform(as(jsonPost("/api/admin/games/" + featured + "/feature", Map.of("featured", true)), adminToken));

        MvcResult tv = mvc.perform(as(get("/api/tv"), kariToken)).andReturn();
        assertThat(body(tv).at("/game/id").asLong()).isEqualTo(featured);
        assertThat(body(tv).get("reason").asText()).isEqualTo("FEATURED");
        assertThat(body(tv).get("whiteRating").asInt()).isEqualTo(1200);

        BlockingQueue<JsonNode> tvUpdates = subscribe(connect(kariToken), "/topic/tv");
        move(featured, "e4", 0);
        assertThat(await(tvUpdates, m -> m.at("/game/plyCount").asInt() == 1).at("/game/id").asLong()).isEqualTo(featured);

        // The featured game ends: the TV moves on to the game still being played.
        move(other, "d4", 0);
        mvc.perform(as(jsonPost("/api/games/" + featured + "/finish", Map.of("result", "DRAW", "termination", "AGREEMENT")), adminToken));
        JsonNode switched = await(tvUpdates, m -> m.at("/game/id").asLong() == other);
        assertThat(switched.get("reason").asText()).isEqualTo("LIVE");
    }

    @Test
    void tvShowsTheLatestFinishedGameWhenNothingIsLive() throws Exception {
        mvc.perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "WHITE_WIN")), adminToken));
        JsonNode tv = body(mvc.perform(as(get("/api/tv"), kariToken)).andReturn());
        assertThat(tv.get("reason").asText()).isEqualTo("RECENT");
        assertThat(tv.at("/game/result").asText()).isEqualTo("WHITE_WIN");
    }

    @Test
    void tvIsEmptyBeforeAnyGame() throws Exception {
        JsonNode tv = body(mvc.perform(as(get("/api/tv"), kariToken)).andReturn());
        assertThat(tv.get("game").isNull()).isTrue();
    }

    @Test
    void tournamentFollowersSeeStandingsChange() throws Exception {
        MvcResult created = mvc.perform(as(jsonPost("/api/tournaments",
                Map.of("name", "Julecup", "format", "ROUND_ROBIN", "category", "RAPID")), adminToken)).andReturn();
        long tournament = body(created).get("id").asLong();
        for (long player : new long[] {kari, ola}) {
            mvc.perform(as(jsonPost("/api/tournaments/" + tournament + "/participants", Map.of("userId", player)), adminToken));
        }
        mvc.perform(as(jsonPost("/api/tournaments/" + tournament + "/start", Map.of()), adminToken));
        long game = body(mvc.perform(as(get("/api/tournaments/" + tournament), adminToken)).andReturn())
                .at("/rounds/0/pairings/0/currentGame/id").asLong();

        BlockingQueue<JsonNode> updates = subscribe(connect(kariToken), "/topic/tournaments/" + tournament);
        mvc.perform(as(jsonPost("/api/games/" + game + "/report", Map.of("result", "DRAW")), adminToken));
        JsonNode update = await(updates, m -> m.at("/tournament/status").asText().equals("FINISHED"));
        assertThat(update.get("type").asText()).isEqualTo("tournament");
        assertThat(update.at("/tournament/standings/0/score").asDouble()).isEqualTo(0.5);
    }
}
