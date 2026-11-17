package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tournaments over HTTP. Results are reported on the scheduled game that belongs to each pairing,
 * through the same confirmation flow as any other game; the tournament advances when games are confirmed.
 */
class TournamentApiTest extends ApiIntegrationTest {

    private String adminToken;
    private final Map<String, Long> ids = new HashMap<>();
    private final Map<String, String> tokens = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        createAdmin("admin");
        adminToken = login("admin");
        for (String name : new String[] {"anne", "bjorn", "cecilie", "dag", "eva", "frode"}) {
            ids.put(name, createPlayer(name));
        }
    }

    private String token(String name) throws Exception {
        if (!tokens.containsKey(name)) {
            tokens.put(name, login(name));
        }
        return tokens.get(name);
    }

    private long id(String name) {
        return ids.get(name);
    }

    private Map<String, Object> tournament(String format) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Julecup " + format);
        body.put("format", format);
        body.put("category", "RAPID");
        body.put("timeControl", "15+10");
        if (format.endsWith("ELIMINATION")) {
            body.put("drawResolution", "REPLAY");
        }
        return body;
    }

    private MvcResult post(String token, String url, Object body) throws Exception {
        return mvc.perform(as(jsonPost(url, body), token)).andReturn();
    }

    private long create(Map<String, Object> body) throws Exception {
        MvcResult result = post(adminToken, "/api/tournaments", body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    /** Creates, signs up the named players (in this order) and starts the tournament. */
    private long started(Map<String, Object> config, String... players) throws Exception {
        long tournament = create(config);
        for (String player : players) {
            MvcResult added = post(adminToken, "/api/tournaments/" + tournament + "/participants", Map.of("userId", id(player)));
            assertThat(added.getResponse().getStatus()).as(added.getResponse().getContentAsString()).isEqualTo(201);
        }
        MvcResult start = post(adminToken, "/api/tournaments/" + tournament + "/start", Map.of());
        assertThat(start.getResponse().getStatus()).as(start.getResponse().getContentAsString()).isEqualTo(200);
        return tournament;
    }

    private JsonNode detail(long tournament) throws Exception {
        MvcResult result = mvc.perform(as(get("/api/tournaments/" + tournament), adminToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    /** Games of pairings that are waiting for a result, in board order. */
    private List<JsonNode> openGames(long tournament) throws Exception {
        List<JsonNode> open = new ArrayList<>();
        for (JsonNode round : detail(tournament).get("rounds")) {
            for (JsonNode pairing : round.get("pairings")) {
                JsonNode game = pairing.get("currentGame");
                if (game != null && !game.isNull() && game.get("status").asText().equals("SCHEDULED")) {
                    open.add(game);
                }
            }
        }
        return open;
    }

    private MvcResult report(String token, long gameId, String result) throws Exception {
        return post(token, "/api/games/" + gameId + "/report", Map.of("result", result));
    }

    /** Admin reports are final at once. White wins unless told otherwise. */
    private void adminReport(long gameId, String result) throws Exception {
        MvcResult reported = report(adminToken, gameId, result);
        assertThat(reported.getResponse().getStatus()).as(reported.getResponse().getContentAsString()).isEqualTo(200);
    }

    private long white(JsonNode game) {
        return game.at("/white/id").asLong();
    }

    private long black(JsonNode game) {
        return game.at("/black/id").asLong();
    }

    private String nameOf(long userId) {
        return ids.entrySet().stream().filter(e -> e.getValue() == userId).map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    @Nested
    class Setup {

        @Test
        void onlyAdminsCreateTournaments() throws Exception {
            assertThat(body(post(token("anne"), "/api/tournaments", tournament("SWISS"))).get("code").asText())
                    .isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
            long id = create(tournament("SWISS"));
            mvc.perform(as(get("/api/tournaments"), token("anne")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(id))
                    .andExpect(jsonPath("$[0].status").value("DRAFT"));
        }

        @Test
        void invalidConfigurationsAreRejected() throws Exception {
            Map<String, Object> knockoutWithoutRule = tournament("SINGLE_ELIMINATION");
            knockoutWithoutRule.remove("drawResolution");
            Map<String, Object> ruleOnSwiss = tournament("SWISS");
            ruleOnSwiss.put("drawResolution", "ARMAGEDDON");
            Map<String, Object> arenaWithoutWindow = tournament("ARENA");
            Map<String, Object> clockContradictsCategory = tournament("SWISS");
            clockContradictsCategory.put("timeControl", "3+2");
            Map<String, Object> unknownFormat = tournament("SWISS");
            unknownFormat.put("format", "BATTLE_ROYALE");
            Map<String, Object> zeroRounds = tournament("SWISS");
            zeroRounds.put("rounds", 0);
            Map<String, Object> noName = tournament("SWISS");
            noName.put("name", " ");

            for (Map<String, Object> bad : List.of(knockoutWithoutRule, ruleOnSwiss, arenaWithoutWindow,
                    clockContradictsCategory, unknownFormat, zeroRounds, noName)) {
                MvcResult result = post(adminToken, "/api/tournaments", bad);
                assertThat(body(result).get("code").asText()).as("config %s", bad).isIn(
                        ErrorCode.TOURNAMENT_INVALID_CONFIG.code(), ErrorCode.VALIDATION_FAILED.code(),
                        ErrorCode.MALFORMED_REQUEST.code(), ErrorCode.GAME_INVALID_TIME_CONTROL.code());
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tournament", Integer.class)).isZero();
        }

        @Test
        void playersJoinAndLeaveThemselvesWhileItIsADraft() throws Exception {
            long tournament = create(tournament("SWISS"));
            assertThat(post(token("anne"), "/api/tournaments/" + tournament + "/join", Map.of()).getResponse().getStatus()).isEqualTo(200);
            assertThat(body(post(token("anne"), "/api/tournaments/" + tournament + "/join", Map.of())).get("code").asText())
                    .isEqualTo(ErrorCode.TOURNAMENT_ALREADY_REGISTERED.code());
            assertThat(post(token("anne"), "/api/tournaments/" + tournament + "/leave", Map.of()).getResponse().getStatus()).isEqualTo(200);
            assertThat(detail(tournament).get("participants")).isEmpty();
        }

        @Test
        void managedPlayersAreSignedUpByAnAdmin() throws Exception {
            long lillebror = createManagedPlayer("lillebror");
            long tournament = create(tournament("SWISS"));
            assertThat(post(adminToken, "/api/tournaments/" + tournament + "/participants", Map.of("userId", lillebror))
                    .getResponse().getStatus()).isEqualTo(201);
            assertThat(body(post(token("anne"), "/api/tournaments/" + tournament + "/participants", Map.of("userId", id("bjorn"))))
                    .get("code").asText()).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
        }

        @Test
        void cannotStartWithFewerThanTwoPlayersOrTwice() throws Exception {
            long tournament = create(tournament("SWISS"));
            post(adminToken, "/api/tournaments/" + tournament + "/participants", Map.of("userId", id("anne")));
            assertThat(body(post(adminToken, "/api/tournaments/" + tournament + "/start", Map.of())).get("code").asText())
                    .isEqualTo(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS.code());
            post(adminToken, "/api/tournaments/" + tournament + "/participants", Map.of("userId", id("bjorn")));
            assertThat(post(adminToken, "/api/tournaments/" + tournament + "/start", Map.of()).getResponse().getStatus()).isEqualTo(200);
            assertThat(body(post(adminToken, "/api/tournaments/" + tournament + "/start", Map.of())).get("code").asText())
                    .isEqualTo(ErrorCode.TOURNAMENT_INVALID_STATE.code());
            assertThat(body(post(token("cecilie"), "/api/tournaments/" + tournament + "/join", Map.of())).get("code").asText())
                    .isEqualTo(ErrorCode.TOURNAMENT_INVALID_STATE.code());
        }

        @Test
        void playersAreSeededByTheirRatingInTheTournamentCategory() throws Exception {
            jdbc.update("INSERT INTO player_rating (user_id, category, rating) VALUES (?, 'RAPID', 1500), (?, 'RAPID', 1700), (?, 'BLITZ', 2000)",
                    id("bjorn"), id("cecilie"), id("anne"));
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn", "cecilie", "dag");
            JsonNode participants = detail(tournament).get("participants");
            assertThat(participants.get(0).at("/user/username").asText()).isEqualTo("cecilie");
            assertThat(participants.get(0).get("seed").asInt()).isEqualTo(1);
            assertThat(participants.get(0).get("seedRating").asInt()).isEqualTo(1700);
            assertThat(participants.get(1).at("/user/username").asText()).isEqualTo("bjorn");
        }

        @Test
        void concurrentSignUpsGiveEveryoneAUniqueSeed() throws Exception {
            long tournament = create(tournament("SWISS"));
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (String name : new String[] {"anne", "bjorn", "cecilie", "dag", "eva", "frode"}) {
                String playerToken = token(name);
                tasks.add(() -> post(playerToken, "/api/tournaments/" + tournament + "/join", Map.of()).getResponse().getStatus());
            }
            assertThat(runConcurrently(tasks)).containsOnly(200);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT seed) FROM tournament_participant WHERE tournament_id = ?",
                    Integer.class, tournament)).isEqualTo(6);
        }

        @Test
        void cancellingAbortsTheOpenGames() throws Exception {
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn", "cecilie", "dag");
            assertThat(post(token("anne"), "/api/tournaments/" + tournament + "/cancel", Map.of()).getResponse().getStatus()).isEqualTo(403);
            assertThat(post(adminToken, "/api/tournaments/" + tournament + "/cancel", Map.of()).getResponse().getStatus()).isEqualTo(200);
            assertThat(detail(tournament).get("status").asText()).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM game WHERE status = 'SCHEDULED'", Integer.class)).isZero();
        }

        @Test
        void unknownTournamentIsNotFound() throws Exception {
            mvc.perform(as(get("/api/tournaments/424242"), adminToken)).andExpect(problem(ErrorCode.TOURNAMENT_NOT_FOUND));
        }
    }

    @Nested
    class RoundRobin {

        @Test
        void wholeTournamentAdvancesRoundByRoundAndFinishes() throws Exception {
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn", "cecilie", "dag");
            JsonNode first = detail(tournament);
            assertThat(first.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(first.get("totalRounds").asInt()).isEqualTo(3);
            assertThat(first.get("currentRound").asInt()).isEqualTo(1);

            Set<String> meetings = new HashSet<>();
            for (int round = 1; round <= 3; round++) {
                List<JsonNode> games = openGames(tournament);
                assertThat(games).as("round %d", round).hasSize(2);
                assertThat(detail(tournament).get("currentRound").asInt()).isEqualTo(round);
                for (JsonNode game : games) {
                    assertThat(game.get("category").asText()).isEqualTo("RAPID");
                    assertThat(meetings.add(Math.min(white(game), black(game)) + "-" + Math.max(white(game), black(game)))).isTrue();
                    // The lower id always wins, so the final order is predictable.
                    adminReport(game.get("id").asLong(), white(game) < black(game) ? "WHITE_WIN" : "BLACK_WIN");
                }
            }

            JsonNode done = detail(tournament);
            assertThat(done.get("status").asText()).isEqualTo("FINISHED");
            assertThat(openGames(tournament)).isEmpty();
            JsonNode standings = done.get("standings");
            assertThat(standings.get(0).at("/user/username").asText()).isEqualTo("anne");
            assertThat(standings.get(0).get("score").asDouble()).isEqualTo(3.0);
            assertThat(standings.get(3).get("score").asDouble()).isEqualTo(0.0);
            assertThat(standings.get(0).get("rank").asInt()).isEqualTo(1);
            assertThat(standings.get(0).has("buchholz")).isTrue();
            // Tournament games are ordinary rated games in the tournament's category.
            assertThat(ratedGames(id("anne"), "RAPID")).isEqualTo(3);
            assertThat(ratedGames(id("anne"), "CLASSICAL")).isZero();
        }

        @Test
        void oddFieldGetsAByeEachRoundThatScoresNothing() throws Exception {
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn", "cecilie");
            assertThat(detail(tournament).get("totalRounds").asInt()).isEqualTo(3);
            for (int round = 1; round <= 3; round++) {
                for (JsonNode game : openGames(tournament)) {
                    adminReport(game.get("id").asLong(), "DRAW");
                }
            }
            JsonNode standings = detail(tournament).get("standings");
            for (JsonNode row : standings) {
                assertThat(row.get("score").asDouble()).isEqualTo(1.0);
                assertThat(row.get("played").asInt()).isEqualTo(2);
            }
        }

        @Test
        void doubleRoundRobinPlaysEveryPairingTwice() throws Exception {
            long tournament = started(tournament("DOUBLE_ROUND_ROBIN"), "anne", "bjorn", "cecilie");
            assertThat(detail(tournament).get("totalRounds").asInt()).isEqualTo(6);
            int games = 0;
            while (!openGames(tournament).isEmpty()) {
                for (JsonNode game : openGames(tournament)) {
                    adminReport(game.get("id").asLong(), "WHITE_WIN");
                    games++;
                }
            }
            assertThat(games).isEqualTo(6);
            assertThat(detail(tournament).get("status").asText()).isEqualTo("FINISHED");
        }

        @Test
        void playerReportedResultCountsOnlyOnceTheOpponentConfirms() throws Exception {
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn");
            JsonNode game = openGames(tournament).get(0);
            long gameId = game.get("id").asLong();
            String whiteName = nameOf(white(game));
            String blackName = nameOf(black(game));

            assertThat(report(token(whiteName), gameId, "WHITE_WIN").getResponse().getStatus()).isEqualTo(200);
            assertThat(detail(tournament).get("status").asText()).isEqualTo("ACTIVE");
            assertThat(detail(tournament).get("standings").get(0).get("score").asDouble()).isZero();

            assertThat(post(token(blackName), "/api/games/" + gameId + "/confirm", Map.of()).getResponse().getStatus()).isEqualTo(200);
            JsonNode done = detail(tournament);
            assertThat(done.get("status").asText()).isEqualTo("FINISHED");
            assertThat(done.get("standings").get(0).at("/user/username").asText()).isEqualTo(whiteName);
        }

        @Test
        void rejectedResultGoesBackToBeingPlayable() throws Exception {
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn");
            JsonNode game = openGames(tournament).get(0);
            long gameId = game.get("id").asLong();
            report(token(nameOf(white(game))), gameId, "WHITE_WIN");
            assertThat(post(token(nameOf(black(game))), "/api/games/" + gameId + "/reject", Map.of("reason", "Jeg vant!"))
                    .getResponse().getStatus()).isEqualTo(200);

            assertThat(jdbc.queryForObject("SELECT status FROM game WHERE id = ?", String.class, gameId)).isEqualTo("SCHEDULED");
            assertThat(report(token(nameOf(black(game))), gameId, "BLACK_WIN").getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void outsidersCannotReportAndResultsCannotBeReportedTwice() throws Exception {
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn", "cecilie", "dag");
            JsonNode game = openGames(tournament).get(0);
            long gameId = game.get("id").asLong();
            Set<Long> playing = Set.of(white(game), black(game));
            String outsider = ids.entrySet().stream().filter(e -> !playing.contains(e.getValue())).findFirst().orElseThrow().getKey();

            assertThat(body(report(token(outsider), gameId, "WHITE_WIN")).get("code").asText())
                    .isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            adminReport(gameId, "DRAW");
            assertThat(body(report(adminToken, gameId, "WHITE_WIN")).get("code").asText())
                    .isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
        }

        /** Every game of the round is confirmed at once: the next round must be created exactly once. */
        @Test
        void simultaneousConfirmationsCreateTheNextRoundOnce() throws Exception {
            long tournament = started(tournament("ROUND_ROBIN"), "anne", "bjorn", "cecilie", "dag", "eva", "frode");
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (JsonNode game : openGames(tournament)) {
                long gameId = game.get("id").asLong();
                tasks.add(() -> report(adminToken, gameId, "DRAW").getResponse().getStatus());
            }
            assertThat(runConcurrently(tasks)).containsOnly(200);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM tournament_round WHERE tournament_id = ?", Integer.class, tournament))
                    .isEqualTo(2);
            assertThat(openGames(tournament)).hasSize(3);
            assertThat(detail(tournament).get("currentRound").asInt()).isEqualTo(2);
        }
    }

    @Nested
    class Swiss {

        @Test
        void playsTheRecommendedRoundsWithoutRematchesAndScoresByesAsAPoint() throws Exception {
            long tournament = started(tournament("SWISS"), "anne", "bjorn", "cecilie", "dag", "eva");
            assertThat(detail(tournament).get("totalRounds").asInt()).isEqualTo(3);

            Set<String> meetings = new HashSet<>();
            int byes = 0;
            for (int round = 1; round <= 3; round++) {
                JsonNode current = detail(tournament).get("rounds").get(round - 1);
                for (JsonNode pairing : current.get("pairings")) {
                    if (pairing.get("bye").asBoolean()) {
                        byes++;
                        assertThat(pairing.get("result").asText()).isEqualTo("BYE");
                        continue;
                    }
                    JsonNode game = pairing.get("currentGame");
                    assertThat(meetings.add(Math.min(white(game), black(game)) + "-" + Math.max(white(game), black(game))))
                            .as("rematch in round %d", round).isTrue();
                    adminReport(game.get("id").asLong(), white(game) < black(game) ? "WHITE_WIN" : "BLACK_WIN");
                }
            }
            assertThat(byes).isEqualTo(3);

            JsonNode done = detail(tournament);
            assertThat(done.get("status").asText()).isEqualTo("FINISHED");
            double total = 0;
            for (JsonNode row : done.get("standings")) {
                total += row.get("score").asDouble();
            }
            // Six decisive games plus three byes.
            assertThat(total).isEqualTo(9.0);
        }

        @Test
        void customRoundCountIsRespected() throws Exception {
            Map<String, Object> config = tournament("SWISS");
            config.put("rounds", 2);
            long tournament = started(config, "anne", "bjorn", "cecilie", "dag", "eva", "frode");
            for (int round = 1; round <= 2; round++) {
                for (JsonNode game : openGames(tournament)) {
                    adminReport(game.get("id").asLong(), "DRAW");
                }
            }
            assertThat(detail(tournament).get("status").asText()).isEqualTo("FINISHED");
            assertThat(detail(tournament).get("rounds")).hasSize(2);
        }
    }

    @Nested
    class Knockout {

        private Map<String, Object> knockout(String format, String drawResolution) {
            Map<String, Object> config = tournament(format);
            config.put("drawResolution", drawResolution);
            return config;
        }

        @Test
        void singleEliminationProducesAChampion() throws Exception {
            long tournament = started(knockout("SINGLE_ELIMINATION", "REPLAY"), "anne", "bjorn", "cecilie", "dag");
            assertThat(openGames(tournament)).hasSize(2);
            int played = 0;
            while (!openGames(tournament).isEmpty()) {
                for (JsonNode game : openGames(tournament)) {
                    adminReport(game.get("id").asLong(), white(game) < black(game) ? "WHITE_WIN" : "BLACK_WIN");
                    played++;
                }
            }
            JsonNode done = detail(tournament);
            assertThat(played).isEqualTo(3);
            assertThat(done.get("status").asText()).isEqualTo("FINISHED");
            assertThat(done.at("/champion/username").asText()).isEqualTo("anne");
            assertThat(done.get("bracket")).isNotEmpty();
        }

        @Test
        void byesAdvanceTheTopSeedsWithoutAGame() throws Exception {
            long tournament = started(knockout("SINGLE_ELIMINATION", "REPLAY"), "anne", "bjorn", "cecilie");
            // Seed 1 has a bye: only seeds 2 and 3 play in the first round.
            assertThat(openGames(tournament)).hasSize(1);
        }

        @Test
        void replayRuleCreatesARematchWithColoursReversed() throws Exception {
            long tournament = started(knockout("SINGLE_ELIMINATION", "REPLAY"), "anne", "bjorn");
            JsonNode game = openGames(tournament).get(0);
            adminReport(game.get("id").asLong(), "DRAW");

            List<JsonNode> open = openGames(tournament);
            assertThat(open).hasSize(1);
            JsonNode replay = open.get(0);
            assertThat(replay.get("id").asLong()).isNotEqualTo(game.get("id").asLong());
            assertThat(white(replay)).isEqualTo(black(game));
            assertThat(replay.get("stage").asText()).isEqualTo("MAIN");
            assertThat(detail(tournament).get("status").asText()).isEqualTo("ACTIVE");

            adminReport(replay.get("id").asLong(), "BLACK_WIN");
            assertThat(detail(tournament).at("/champion/id").asLong()).isEqualTo(black(replay));
        }

        @Test
        void blitzPlayoffThenArmageddon() throws Exception {
            long tournament = started(knockout("SINGLE_ELIMINATION", "BLITZ_PLAYOFF"), "anne", "bjorn");
            adminReport(openGames(tournament).get(0).get("id").asLong(), "DRAW");

            JsonNode playoff1 = openGames(tournament).get(0);
            assertThat(playoff1.get("stage").asText()).isEqualTo("PLAYOFF");
            assertThat(playoff1.get("category").asText()).isEqualTo("BLITZ");
            assertThat(playoff1.get("rated").asBoolean()).isTrue();
            adminReport(playoff1.get("id").asLong(), "WHITE_WIN");

            JsonNode playoff2 = openGames(tournament).get(0);
            assertThat(playoff2.get("stage").asText()).isEqualTo("PLAYOFF");
            assertThat(white(playoff2)).isEqualTo(black(playoff1));
            adminReport(playoff2.get("id").asLong(), "WHITE_WIN");   // 1-1: still level

            JsonNode armageddon = openGames(tournament).get(0);
            assertThat(armageddon.get("stage").asText()).isEqualTo("ARMAGEDDON");
            assertThat(armageddon.get("rated").asBoolean()).isFalse();
            adminReport(armageddon.get("id").asLong(), "DRAW");

            // A drawn armageddon goes to Black.
            JsonNode done = detail(tournament);
            assertThat(done.get("status").asText()).isEqualTo("FINISHED");
            assertThat(done.at("/champion/id").asLong()).isEqualTo(black(armageddon));
            assertThat(ratedGames(id("anne"), "BLITZ")).isEqualTo(2);
        }

        @Test
        void blitzPlayoffWinnerAdvancesWithoutArmageddon() throws Exception {
            long tournament = started(knockout("SINGLE_ELIMINATION", "BLITZ_PLAYOFF"), "anne", "bjorn");
            adminReport(openGames(tournament).get(0).get("id").asLong(), "DRAW");
            JsonNode playoff1 = openGames(tournament).get(0);
            adminReport(playoff1.get("id").asLong(), "WHITE_WIN");
            JsonNode playoff2 = openGames(tournament).get(0);
            adminReport(playoff2.get("id").asLong(), "DRAW");   // 1.5-0.5 to white of game 1

            assertThat(openGames(tournament)).isEmpty();
            assertThat(detail(tournament).at("/champion/id").asLong()).isEqualTo(white(playoff1));
        }

        @Test
        void armageddonRuleGoesStraightToArmageddon() throws Exception {
            long tournament = started(knockout("SINGLE_ELIMINATION", "ARMAGEDDON"), "anne", "bjorn");
            adminReport(openGames(tournament).get(0).get("id").asLong(), "DRAW");
            JsonNode armageddon = openGames(tournament).get(0);
            assertThat(armageddon.get("stage").asText()).isEqualTo("ARMAGEDDON");
            adminReport(armageddon.get("id").asLong(), "WHITE_WIN");
            assertThat(detail(tournament).at("/champion/id").asLong()).isEqualTo(white(armageddon));
        }

        @Test
        void doubleEliminationRunsToAChampion() throws Exception {
            long tournament = started(knockout("DOUBLE_ELIMINATION", "REPLAY"), "anne", "bjorn", "cecilie", "dag");
            int played = 0;
            while (!openGames(tournament).isEmpty()) {
                for (JsonNode game : openGames(tournament)) {
                    adminReport(game.get("id").asLong(), white(game) < black(game) ? "WHITE_WIN" : "BLACK_WIN");
                    played++;
                }
                assertThat(played).isLessThan(20);
            }
            assertThat(played).isEqualTo(6);
            assertThat(detail(tournament).at("/champion/username").asText()).isEqualTo("anne");
        }
    }

    @Nested
    class Arena {

        private long openArena(String... players) throws Exception {
            Map<String, Object> config = tournament("ARENA");
            config.put("startsAt", Instant.now().minusSeconds(60).toString());
            config.put("endsAt", Instant.now().plusSeconds(3600).toString());
            return started(config, players);
        }

        private MvcResult arenaGame(String token, long tournament, String white, String black, String result) throws Exception {
            return post(token, "/api/tournaments/" + tournament + "/arena-games",
                    Map.of("whiteId", id(white), "blackId", id(black), "result", result));
        }

        @Test
        void streaksDoubleThePointsFromTheThirdWin() throws Exception {
            long tournament = openArena("anne", "bjorn", "cecilie");
            for (String[] game : new String[][] {{"anne", "bjorn"}, {"anne", "cecilie"}, {"bjorn", "anne"}, {"cecilie", "anne"}}) {
                String result = game[0].equals("anne") ? "WHITE_WIN" : "BLACK_WIN";
                assertThat(arenaGame(adminToken, tournament, game[0], game[1], result).getResponse().getStatus()).isEqualTo(201);
            }
            // Anne: 2 + 2 + 4 + 4 = 12, and on a streak of four.
            JsonNode top = detail(tournament).get("standings").get(0);
            assertThat(top.at("/user/username").asText()).isEqualTo("anne");
            assertThat(top.get("score").asDouble()).isEqualTo(12.0);
            assertThat(top.get("streak").asInt()).isEqualTo(4);
            assertThat(top.get("onFire").asBoolean()).isTrue();
        }

        @Test
        void playerReportedArenaGamesCountAfterConfirmation() throws Exception {
            long tournament = openArena("anne", "bjorn");
            MvcResult reported = arenaGame(token("anne"), tournament, "anne", "bjorn", "WHITE_WIN");
            assertThat(reported.getResponse().getStatus()).isEqualTo(201);
            assertThat(detail(tournament).get("standings").get(0).get("score").asDouble()).isZero();
            post(token("bjorn"), "/api/games/" + body(reported).get("id").asLong() + "/confirm", Map.of());
            assertThat(detail(tournament).get("standings").get(0).get("score").asDouble()).isEqualTo(2.0);
        }

        @Test
        void gamesOutsideTheWindowOrWithOutsidersAreRefused() throws Exception {
            long tournament = openArena("anne", "bjorn");
            assertThat(body(arenaGame(adminToken, tournament, "anne", "cecilie", "DRAW")).get("code").asText())
                    .isEqualTo(ErrorCode.TOURNAMENT_NOT_PARTICIPANT.code());
            assertThat(body(arenaGame(token("cecilie"), tournament, "anne", "bjorn", "DRAW")).get("code").asText())
                    .isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());

            jdbc.update("UPDATE tournament SET ends_at = now() - interval '1 second', starts_at = now() - interval '1 hour' WHERE id = ?",
                    tournament);
            assertThat(body(arenaGame(adminToken, tournament, "anne", "bjorn", "DRAW")).get("code").asText())
                    .isEqualTo(ErrorCode.TOURNAMENT_ARENA_CLOSED.code());
        }

        @Test
        void arenaGamesAreRefusedForOtherFormats() throws Exception {
            long tournament = started(tournament("SWISS"), "anne", "bjorn");
            assertThat(body(post(adminToken, "/api/tournaments/" + tournament + "/arena-games",
                    Map.of("whiteId", id("anne"), "blackId", id("bjorn"), "result", "DRAW"))).get("code").asText())
                    .isEqualTo(ErrorCode.TOURNAMENT_INVALID_STATE.code());
        }

        @Test
        void adminFinishesTheArena() throws Exception {
            long tournament = openArena("anne", "bjorn");
            arenaGame(adminToken, tournament, "anne", "bjorn", "WHITE_WIN");
            assertThat(post(adminToken, "/api/tournaments/" + tournament + "/finish", Map.of()).getResponse().getStatus()).isEqualTo(200);
            JsonNode done = detail(tournament);
            assertThat(done.get("status").asText()).isEqualTo("FINISHED");
            assertThat(done.at("/champion/username").asText()).isEqualTo("anne");
        }
    }
}
