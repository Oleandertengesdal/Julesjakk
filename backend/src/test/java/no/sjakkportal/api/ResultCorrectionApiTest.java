package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * An admin can correct the result of a confirmed game, or annul it. Elo depends on the order of
 * games, so every rating in the game's category is replayed from the start: the outcome must be
 * exactly as if the right result had been registered in the first place.
 */
class ResultCorrectionApiTest extends ApiIntegrationTest {

    private String admin;
    private long kari;
    private long ola;
    private long per;
    // A second family that plays the same games with the right results from the start.
    private long anna;
    private long bodil;
    private long carl;

    @BeforeEach
    void users() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        per = createPlayer("per");
        anna = createPlayer("anna");
        bodil = createPlayer("bodil");
        carl = createPlayer("carl");
        admin = login("admin");
    }

    private static String code(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(r.getResponse().getContentAsString());
        return m.find() ? m.group(1) : "status " + r.getResponse().getStatus();
    }

    private long play(long white, long black, String result, String timeControl) throws Exception {
        return play(white, black, result, timeControl, null);
    }

    private long play(long white, long black, String result, String timeControl, String pgn) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("whiteId", white, "blackId", black, "result", result, "timeControl", timeControl));
        if (pgn != null) {
            body.put("pgn", pgn);
        }
        MvcResult r = mvc.perform(as(jsonPost("/api/games", body), admin)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    private MvcResult correct(long game, String result, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("result", result));
        if (reason != null) {
            body.put("reason", reason);
        }
        return mvc.perform(as(jsonPost("/api/admin/games/" + game + "/correct", body), admin)).andReturn();
    }

    private MvcResult annul(long game, String reason) throws Exception {
        return mvc.perform(as(jsonPost("/api/admin/games/" + game + "/annul", Map.of("reason", reason)), admin)).andReturn();
    }

    private List<Integer> history(long user, String category) {
        return jdbc.queryForList("SELECT rating_after FROM rating_history WHERE user_id = ? AND category = ? ORDER BY id",
                Integer.class, user, category);
    }

    @Nested
    class Correcting {

        @Test
        void ratingsEndUpExactlyAsIfTheRightResultHadBeenRegistered() throws Exception {
            // Kari's family: the first result is wrong, and two more games are played on top of it.
            long wrong = play(kari, ola, "WHITE_WIN", "5+3");
            play(ola, per, "DRAW", "5+3");
            play(per, kari, "BLACK_WIN", "5+3");
            // Anna's family: the same games, right from the start.
            play(anna, bodil, "BLACK_WIN", "5+3");
            play(bodil, carl, "DRAW", "5+3");
            play(carl, anna, "BLACK_WIN", "5+3");

            MvcResult r = correct(wrong, "BLACK_WIN", "Ola vant, ikke Kari");
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);

            assertThat(rating(kari, "BLITZ")).isEqualTo(rating(anna, "BLITZ"));
            assertThat(rating(ola, "BLITZ")).isEqualTo(rating(bodil, "BLITZ"));
            // Per never played the corrected game, but his rating depended on Ola's.
            assertThat(rating(per, "BLITZ")).isEqualTo(rating(carl, "BLITZ"));
            assertThat(history(kari, "BLITZ")).isEqualTo(history(anna, "BLITZ"));
            assertThat(history(per, "BLITZ")).isEqualTo(history(carl, "BLITZ"));
            assertThat(ratedGames(kari, "BLITZ")).isEqualTo(2);
        }

        @Test
        void theGameShowsTheCorrectedResultRatingsAndWhoCorrectedIt() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            JsonNode view = body(correct(g, "DRAW", "Det ble remis"));
            assertThat(view.get("result").asText()).isEqualTo("DRAW");
            assertThat(view.get("status").asText()).isEqualTo("FINISHED");
            assertThat(view.at("/correction/reason").asText()).isEqualTo("Det ble remis");
            assertThat(view.at("/correction/by/username").asText()).isEqualTo("admin");
            assertThat(view.at("/correction/at").asText()).isNotBlank();
            JsonNode fetched = body(mvc.perform(as(get("/api/games/" + g), login("kari"))).andReturn());
            assertThat(fetched.get("whiteRatingDelta").asInt()).isZero();
            assertThat(fetched.get("blackRatingDelta").asInt()).isZero();
            assertThat(rating(kari, "BLITZ")).isEqualTo(1200);
        }

        @Test
        void otherCategoriesAreLeftAlone() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            play(kari, ola, "BLACK_WIN", "15+10");
            List<Integer> rapidBefore = history(kari, "RAPID");
            correct(g, "BLACK_WIN", "Feil vinner");
            assertThat(history(kari, "RAPID")).isEqualTo(rapidBefore);
            assertThat(rating(kari, "RAPID")).isEqualTo(1180);
        }

        @Test
        void unratedGamesChangeResultButNoRatings() throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("whiteId", kari, "blackId", ola, "result", "WHITE_WIN", "timeControl", "5+3",
                    "rated", false));
            long g = body(mvc.perform(as(jsonPost("/api/games", body), admin)).andReturn()).get("id").asLong();
            correct(g, "BLACK_WIN", "Feil vinner");
            assertThat(jdbc.queryForObject("SELECT result FROM game WHERE id = ?", String.class, g)).isEqualTo("BLACK_WIN");
            assertThat(rating(kari, "BLITZ")).isEqualTo(1200);
            assertThat(history(kari, "BLITZ")).isEmpty();
        }

        @Test
        void theAuditLogKeepsTheOldAndNewResult() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            correct(g, "BLACK_WIN", "Feil vinner");
            String details = jdbc.queryForObject("SELECT detail FROM audit_log WHERE action = 'GAME_CORRECTED'", String.class);
            assertThat(details).contains("WHITE_WIN").contains("BLACK_WIN").contains("Feil vinner");
        }

        @Test
        void aResultTheMovesContradictIsRefused() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3", "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0");
            assertThat(code(correct(g, "BLACK_WIN", "Prøver"))).isEqualTo(ErrorCode.PGN_RESULT_MISMATCH.code());
            assertThat(rating(kari, "BLITZ")).isEqualTo(1220);
        }

        @Test
        void aReasonIsRequired() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            assertThat(code(correct(g, "BLACK_WIN", null))).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
            assertThat(code(correct(g, "BLACK_WIN", "   "))).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
            assertThat(code(correct(g, "BLACK_WIN", "x".repeat(301)))).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        }

        @Test
        void onlyConfirmedGamesCanBeCorrected() throws Exception {
            MvcResult pending = mvc.perform(as(jsonPost("/api/games", Map.of("whiteId", kari, "blackId", ola, "result", "WHITE_WIN",
                    "timeControl", "5+3")), login("kari"))).andReturn();
            long g = body(pending).get("id").asLong();
            assertThat(code(correct(g, "BLACK_WIN", "Feil"))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(code(correct(999_999, "BLACK_WIN", "Feil"))).isEqualTo(ErrorCode.GAME_NOT_FOUND.code());
        }

        @Test
        void playersCannotCorrectEvenTheirOwnGames() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            MvcResult r = mvc.perform(as(jsonPost("/api/admin/games/" + g + "/correct", Map.of("result", "BLACK_WIN", "reason", "x")),
                    login("ola"))).andReturn();
            assertThat(code(r)).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
        }

        @Test
        void correctingTheSameGameTwiceStillGivesTheRightRatings() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            play(ola, kari, "DRAW", "5+3");
            play(anna, bodil, "DRAW", "5+3");
            play(bodil, anna, "DRAW", "5+3");
            correct(g, "BLACK_WIN", "Første retting");
            correct(g, "DRAW", "Andre retting");
            assertThat(rating(kari, "BLITZ")).isEqualTo(rating(anna, "BLITZ"));
            assertThat(history(ola, "BLITZ")).isEqualTo(history(bodil, "BLITZ"));
        }

        @Test
        void gamesConfirmedLaterStillRateFromTheCorrectedNumbers() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            play(anna, bodil, "BLACK_WIN", "5+3");
            correct(g, "BLACK_WIN", "Feil vinner");
            play(kari, ola, "DRAW", "5+3");
            play(anna, bodil, "DRAW", "5+3");
            assertThat(rating(kari, "BLITZ")).isEqualTo(rating(anna, "BLITZ"));
            assertThat(ratedGames(kari, "BLITZ")).isEqualTo(2);
        }
    }

    @Nested
    class Annulling {

        @Test
        void anAnnulledGameIsAsIfItWasNeverPlayed() throws Exception {
            long mistake = play(kari, ola, "WHITE_WIN", "5+3");
            play(ola, per, "WHITE_WIN", "5+3");
            play(bodil, carl, "WHITE_WIN", "5+3");
            MvcResult r = annul(mistake, "Registrert to ganger");
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(body(r).get("status").asText()).isEqualTo("ANNULLED");
            assertThat(rating(kari, "BLITZ")).isEqualTo(1200);
            assertThat(ratedGames(kari, "BLITZ")).isZero();
            assertThat(rating(ola, "BLITZ")).isEqualTo(rating(bodil, "BLITZ"));
            assertThat(rating(per, "BLITZ")).isEqualTo(rating(carl, "BLITZ"));
        }

        @Test
        void annulledGamesLeaveStatisticsButStayVisible() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            annul(g, "Feilregistrert");
            String kariT = login("kari");
            JsonNode stats = body(mvc.perform(as(get("/api/users/" + kari + "/stats?category=BLITZ"), kariT)).andReturn());
            assertThat(stats.toString()).doesNotContain("\"wins\":1");
            JsonNode game = body(mvc.perform(as(get("/api/games/" + g), kariT)).andReturn());
            assertThat(game.get("status").asText()).isEqualTo("ANNULLED");
            assertThat(game.at("/correction/reason").asText()).isEqualTo("Feilregistrert");
        }

        @Test
        void anAnnulledGameCannotBeAnnulledOrCorrectedAgain() throws Exception {
            long g = play(kari, ola, "WHITE_WIN", "5+3");
            annul(g, "Feil");
            assertThat(code(annul(g, "Igjen"))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(code(correct(g, "DRAW", "Igjen"))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
        }
    }

    @Nested
    class Tournaments {

        private long tournament(Map<String, Object> extra, long... players) throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("name", "Julecup", "format", "ROUND_ROBIN", "timeControl", "5+3"));
            body.putAll(extra);
            long t = body(mvc.perform(as(jsonPost("/api/tournaments", body), admin)).andReturn()).get("id").asLong();
            for (long p : players) {
                mvc.perform(as(jsonPost("/api/tournaments/" + t + "/participants", Map.of("userId", p)), admin));
            }
            mvc.perform(as(jsonPost("/api/tournaments/" + t + "/start", Map.of()), admin));
            return t;
        }

        private JsonNode detail(long t) throws Exception {
            return body(mvc.perform(as(get("/api/tournaments/" + t), admin)).andReturn());
        }

        private long reportFirst(long t, String result) throws Exception {
            JsonNode pairing = detail(t).at("/rounds/0/pairings/0");
            long g = pairing.at("/currentGame/id").asLong();
            mvc.perform(as(jsonPost("/api/games/" + g + "/report", Map.of("result", result)), admin));
            return g;
        }

        @Test
        void correctingARoundRobinGameUpdatesTheStandings() throws Exception {
            long t = tournament(Map.of(), kari, ola);
            long g = reportFirst(t, "WHITE_WIN");
            String winnerBefore = detail(t).at("/standings/0/user/username").asText();
            correct(g, "BLACK_WIN", "Feil vinner");
            JsonNode after = detail(t);
            assertThat(after.at("/standings/0/user/username").asText()).isNotEqualTo(winnerBefore);
            assertThat(after.at("/rounds/0/pairings/0/result").asText()).isEqualTo("BLACK_WIN");
        }

        @Test
        void aKnockoutResultThatDecidedTheMatchCannotBeChanged() throws Exception {
            long t = tournament(Map.of("format", "SINGLE_ELIMINATION", "drawResolution", "ARMAGEDDON"), kari, ola);
            long g = reportFirst(t, "WHITE_WIN");
            assertThat(code(correct(g, "BLACK_WIN", "Feil"))).isEqualTo(ErrorCode.GAME_CORRECTION_NOT_ALLOWED.code());
            assertThat(code(annul(g, "Feil"))).isEqualTo(ErrorCode.GAME_CORRECTION_NOT_ALLOWED.code());
        }

        @Test
        void pairedTournamentGamesCanBeCorrectedButNotAnnulled() throws Exception {
            long t = tournament(Map.of(), kari, ola);
            long g = reportFirst(t, "WHITE_WIN");
            assertThat(code(annul(g, "Feil"))).isEqualTo(ErrorCode.GAME_CORRECTION_NOT_ALLOWED.code());
        }
    }
}
