package no.sjakkportal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Audit: the live-update socket, attacked from outside. Written from docs/API_AND_ERRORS.md#websocket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSecurityAuditTest extends ApiIntegrationTest {

    @LocalServerPort
    private int port;

    private final List<StompSession> sessions = new ArrayList<>();
    private WebSocketStompClient client;
    private String kariToken;

    @BeforeEach
    void setUp() throws Exception {
        createAdmin("admin");
        createPlayer("kari");
        createPlayer("ola");
        kariToken = login("kari");
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
    }

    private StompSession connect(String authorization, String origin) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin(origin);
        StompHeaders connect = new StompHeaders();
        if (authorization != null) {
            connect.add("Authorization", authorization);
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", handshake, connect,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<Object> subscribe(StompSession session, String topic) {
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add(payload);
            }
        });
        return queue;
    }

    /** True when the server refuses the connection outright or closes it straight after CONNECT. */
    private boolean refused(String authorization) throws Exception {
        StompSession s;
        try {
            s = connect(authorization, "http://localhost:5173");
        } catch (Exception refusedAtConnect) {
            return true;
        }
        BlockingQueue<Object> q = subscribe(s, "/topic/tv");
        Thread.sleep(700);
        return !s.isConnected();
    }

    @Test
    void aValidTokenConnects() throws Exception {
        assertThat(refused("Bearer " + kariToken)).isFalse();
    }

    @Test
    void connectingWithoutATokenIsRefused() throws Exception {
        assertThat(refused(null)).isTrue();
    }

    @Test
    void connectingWithAForgedTokenIsRefused() throws Exception {
        assertThat(refused("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.Zm9yZ2Vk")).isTrue();
        assertThat(refused("Bearer " + kariToken.substring(0, kariToken.length() - 3) + "abc")).isTrue();
    }

    @Test
    void theHandshakeFromAForeignSiteIsRefused() {
        assertThatThrownBy(() -> connect("Bearer " + kariToken, "https://evil.example")).isInstanceOf(Exception.class)
                .isNotInstanceOf(AssertionError.class);
    }

    /** With a plain simple broker, a client SEND to /topic/** would be relayed to every subscriber. */
    @Test
    void clientsCannotPublishFakeUpdatesToOtherViewers() throws Exception {
        StompSession tv = connect("Bearer " + login("ola"), "http://localhost:5173");
        BlockingQueue<Object> received = subscribe(tv, "/topic/tv");
        StompSession attacker = connect("Bearer " + kariToken, "http://localhost:5173");
        Thread.sleep(300);
        for (String destination : List.of("/topic/tv", "/topic/games/1", "/app/topic/tv")) {
            try {
                attacker.send(destination, Map.of("white", Map.of("displayName", "Hacked")));
            } catch (RuntimeException ignored) {
                // a refused send is fine too
            }
        }
        assertThat(received.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }
}
