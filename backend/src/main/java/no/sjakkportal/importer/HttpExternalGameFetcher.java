package no.sjakkportal.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Fetches PGNs from the public endpoints of Lichess and Chess.com.
 *
 * <ul>
 *   <li>Lichess has a documented single-game export: {@code GET https://lichess.org/game/export/{id}}.</li>
 *   <li>Chess.com's public API has no single-game export. The game page's JSON
 *       ({@code /callback/live/game/{id}}, not an official API) gives the players and the date; the PGN is then
 *       taken from White's monthly archive in the public API. If Chess.com changes that page, pasting the PGN
 *       still works.</li>
 * </ul>
 */
@Component
public class HttpExternalGameFetcher implements ExternalGameFetcher {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String USER_AGENT = "Sjakkportal/1.0 (family chess club; game import)";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper objectMapper;

    public HttpExternalGameFetcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String fetchPgn(ExternalGameRef ref) {
        return switch (ref.source()) {
            case "LICHESS" -> get("https://lichess.org/game/export/" + ref.id() + "?clocks=false&evals=false&literate=false",
                    "application/x-chess-pgn");
            case "CHESS_COM" -> chessCom(ref.id());
            default -> throw new ChessException(ErrorCode.IMPORT_UNSUPPORTED_SOURCE);
        };
    }

    private String chessCom(String id) {
        boolean daily = id.startsWith("daily:");
        String number = daily ? id.substring(6) : id;
        JsonNode page = json(get("https://www.chess.com/callback/" + (daily ? "daily" : "live") + "/game/" + number, "application/json"));
        JsonNode headers = page.path("game").path("pgnHeaders");
        String white = headers.path("White").asText("");
        String date = headers.path("Date").asText("");   // yyyy.MM.dd
        if (!white.matches("[A-Za-z0-9_-]{2,50}") || !date.matches("\\d{4}\\.\\d{2}\\.\\d{2}")) {
            throw new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED);
        }
        String archive = "https://api.chess.com/pub/player/" + URLEncoder.encode(white.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8)
                + "/games/" + date.substring(0, 4) + "/" + date.substring(5, 7);
        for (JsonNode game : json(get(archive, "application/json")).path("games")) {
            if (game.path("url").asText("").endsWith("/" + number) && game.hasNonNull("pgn")) {
                return game.get("pgn").asText();
            }
        }
        throw new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED);
    }

    private String get(String url, String accept) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Accept", accept).header("User-Agent", USER_AGENT).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED);
                }
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED);
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED, ErrorCode.IMPORT_UPSTREAM_FAILED.defaultDetail(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED);
        }
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED);
        }
    }
}
