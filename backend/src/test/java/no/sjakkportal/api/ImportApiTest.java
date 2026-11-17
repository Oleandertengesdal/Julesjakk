package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.importer.ExternalGameFetcher;
import no.sjakkportal.importer.ExternalGameRef;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Importing games between two family members from Lichess and Chess.com, for analysis only.
 * The external sites are replaced by a stand-in: the tests must never depend on the internet.
 */
class ImportApiTest extends ApiIntegrationTest {

    private static final String LICHESS_PGN = """
            [Event "Casual blitz game"]
            [Site "https://lichess.org/AbCd1234"]
            [Date "2026.11.14"]
            [White "KariSjakk"]
            [Black "olaN"]
            [Result "1-0"]
            [UTCDate "2026.11.14"]
            [UTCTime "18:30:00"]
            [TimeControl "300+3"]

            1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0
            """;

    private static final String CHESS_COM_PGN = """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [Date "2026.11.15"]
            [White "ola_chesscom"]
            [Black "kari_cc"]
            [Result "1/2-1/2"]
            [TimeControl "900+10"]
            [Link "https://www.chess.com/game/live/123456789"]

            1. d4 d5 2. c4 e6 1/2-1/2
            """;

    @MockitoBean
    private ExternalGameFetcher fetcher;

    private long kari;
    private long ola;
    private String kariToken;

    @BeforeEach
    void setUp() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        createPlayer("per");
        jdbc.update("UPDATE app_user SET lichess_username = 'karisjakk', chess_com_username = 'Kari_CC' WHERE id = ?", kari);
        jdbc.update("UPDATE app_user SET lichess_username = 'OlaN', chess_com_username = 'ola_chesscom' WHERE id = ?", ola);
        kariToken = login("kari");
    }

    private MvcResult importGame(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(as(jsonPost("/api/games/import", body), token)).andReturn();
    }

    @Test
    void lichessLinkImportsAnUnratedFinishedGameForBothPlayers() throws Exception {
        when(fetcher.fetchPgn(new ExternalGameRef("LICHESS", "AbCd1234"))).thenReturn(LICHESS_PGN);
        MvcResult result = importGame(kariToken, Map.of("url", "https://lichess.org/AbCd1234/white#7"));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        var game = body(result);
        assertThat(game.get("status").asText()).isEqualTo("FINISHED");
        assertThat(game.get("source").asText()).isEqualTo("LICHESS");
        assertThat(game.get("rated").asBoolean()).isFalse();
        assertThat(game.get("category").asText()).isEqualTo("BLITZ");
        assertThat(game.get("timeControl").asText()).isEqualTo("5+3");
        assertThat(game.at("/white/id").asLong()).isEqualTo(kari);
        assertThat(game.at("/black/id").asLong()).isEqualTo(ola);
        assertThat(game.get("plyCount").asInt()).isEqualTo(7);
        assertThat(game.get("playedAt").asText()).startsWith("2026-11-14T18:30");
        // Imports are for analysis: nobody's rating moves.
        assertThat(ratedGames(kari, "BLITZ")).isZero();
    }

    @Test
    void chessComLinkMapsChessComUsernames() throws Exception {
        when(fetcher.fetchPgn(new ExternalGameRef("CHESS_COM", "123456789"))).thenReturn(CHESS_COM_PGN);
        MvcResult result = importGame(kariToken, Map.of("url", "https://www.chess.com/game/live/123456789"));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(result).at("/white/id").asLong()).isEqualTo(ola);
        assertThat(body(result).get("category").asText()).isEqualTo("RAPID");
        assertThat(body(result).get("result").asText()).isEqualTo("DRAW");
    }

    @Test
    void pastedPgnIsIdentifiedByItsSiteTags() throws Exception {
        MvcResult result = importGame(kariToken, Map.of("pgn", LICHESS_PGN));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("source").asText()).isEqualTo("LICHESS");
        verify(fetcher, never()).fetchPgn(any());
    }

    @Test
    void theSameGameCannotBeImportedTwice() throws Exception {
        when(fetcher.fetchPgn(new ExternalGameRef("LICHESS", "AbCd1234"))).thenReturn(LICHESS_PGN);
        importGame(kariToken, Map.of("url", "https://lichess.org/AbCd1234"));
        assertThat(body(importGame(kariToken, Map.of("pgn", LICHESS_PGN))).get("code").asText())
                .isEqualTo(ErrorCode.GAME_DUPLICATE_IMPORT.code());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game", Integer.class)).isEqualTo(1);
    }

    @Test
    void gamesAgainstStrangersAreRefused() throws Exception {
        String stranger = LICHESS_PGN.replace("[Black \"olaN\"]", "[Black \"SomeoneElse\"]");
        when(fetcher.fetchPgn(new ExternalGameRef("LICHESS", "AbCd1234"))).thenReturn(stranger);
        assertThat(body(importGame(kariToken, Map.of("url", "https://lichess.org/AbCd1234"))).get("code").asText())
                .isEqualTo(ErrorCode.IMPORT_NOT_FAMILY_GAME.code());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game", Integer.class)).isZero();
    }

    @Test
    void onlyTheTwoPlayersOrAnAdminMayImport() throws Exception {
        when(fetcher.fetchPgn(new ExternalGameRef("LICHESS", "AbCd1234"))).thenReturn(LICHESS_PGN);
        assertThat(body(importGame(login("per"), Map.of("url", "https://lichess.org/AbCd1234"))).get("code").asText())
                .isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
        assertThat(importGame(login("admin"), Map.of("url", "https://lichess.org/AbCd1234")).getResponse().getStatus()).isEqualTo(201);
    }

    /** The server builds the fetch URL from a parsed id; it never requests an address a user typed. */
    @Test
    void unsupportedOrHostileLinksAreRejectedWithoutAnyRequest() throws Exception {
        for (String url : new String[] {"http://169.254.169.254/latest/meta-data", "https://lichess.org.evil.example/AbCd1234",
                "https://evil.example/lichess.org/AbCd1234", "file:///etc/passwd", "https://lichess.org/", "https://www.chess.com/member/kari",
                "https://lichess.org/AbCd12!@", "javascript:alert(1)", "https://www.chess.com/game/live/12ab"}) {
            assertThat(body(importGame(kariToken, Map.of("url", url))).get("code").asText()).as(url)
                    .isEqualTo(ErrorCode.IMPORT_UNSUPPORTED_SOURCE.code());
        }
        verify(fetcher, never()).fetchPgn(any());
    }

    @Test
    void upstreamFailureIsReportedAsSuch() throws Exception {
        when(fetcher.fetchPgn(any())).thenThrow(new ChessException(ErrorCode.IMPORT_UPSTREAM_FAILED));
        MvcResult result = importGame(kariToken, Map.of("url", "https://lichess.org/AbCd1234"));
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.IMPORT_UPSTREAM_FAILED.code());
    }

    @Test
    void unfinishedOrIllegalGamesAreRefused() throws Exception {
        String unfinished = LICHESS_PGN.replace("4. Qxf7# 1-0", "4. Qxf7# *").replace("[Result \"1-0\"]", "[Result \"*\"]")
                .replace("4. Qxf7#", "4. d3");
        when(fetcher.fetchPgn(new ExternalGameRef("LICHESS", "AbCd1234"))).thenReturn(unfinished);
        assertThat(body(importGame(kariToken, Map.of("url", "https://lichess.org/AbCd1234"))).get("code").asText())
                .isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
        assertThat(body(importGame(kariToken, Map.of("pgn", LICHESS_PGN.replace("Qxf7#", "Qxf9#")))).get("code").asText())
                .isIn(ErrorCode.PGN_MALFORMED.code(), ErrorCode.PGN_ILLEGAL_MOVE.code());
    }

    @Test
    void exactlyOneOfUrlAndPgnIsRequired() throws Exception {
        assertThat(importGame(kariToken, Map.of()).getResponse().getStatus()).isEqualTo(400);
        assertThat(importGame(kariToken, Map.of("url", "https://lichess.org/AbCd1234", "pgn", LICHESS_PGN)).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void playersCanLinkTheirOwnOnlineAccounts() throws Exception {
        MvcResult result = mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/me/accounts")
                .contentType("application/json").content("{\"lichessUsername\":\"Per_Spiller\",\"chessComUsername\":\"\"}"),
                login("per"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("lichessUsername").asText()).isEqualTo("Per_Spiller");
        assertThat(body(result).get("chessComUsername").isNull()).isTrue();

        // Already linked to Kari, ignoring case: refused.
        MvcResult taken = mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/me/accounts")
                .contentType("application/json").content("{\"lichessUsername\":\"KARISJAKK\"}"), login("per"))).andReturn();
        assertThat(taken.getResponse().getStatus()).isEqualTo(409);
        MvcResult invalid = mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/me/accounts")
                .contentType("application/json").content("{\"lichessUsername\":\"<script>\"}"), login("per"))).andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
    }
}
