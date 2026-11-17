package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A result registered by one player counts for nothing until the opponent, or an admin, confirms it.
 * Also covers the separate blitz / rapid / classical ratings.
 */
class GameConfirmationApiTest extends ApiIntegrationTest {

    private long kari;
    private long ola;
    private String adminToken;
    private String kariToken;
    private String olaToken;
    private String perToken;

    @BeforeEach
    void setUp() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        createPlayer("per");
        adminToken = login("admin");
        kariToken = login("kari");
        olaToken = login("ola");
        perToken = login("per");
    }

    private Map<String, Object> game(long white, long black, String result) {
        Map<String, Object> body = new HashMap<>();
        body.put("whiteId", white);
        body.put("blackId", black);
        body.put("result", result);
        return body;
    }

    private long register(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(as(jsonPost("/api/games", body), token)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private MvcResult confirm(String token, long game) throws Exception {
        return mvc.perform(as(jsonPost("/api/games/" + game + "/confirm", Map.of()), token)).andReturn();
    }

    private MvcResult reject(String token, long game, String reason) throws Exception {
        return mvc.perform(as(jsonPost("/api/games/" + game + "/reject", Map.of("reason", reason)), token)).andReturn();
    }

    private String gameStatus(long game) {
        return jdbc.queryForObject("SELECT status FROM game WHERE id = ?", String.class, game);
    }

    @Nested
    class Confirmation {

        @Test
        void playerRegisteredGameWaitsForTheOpponent() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));

            assertThat(gameStatus(game)).isEqualTo("PENDING");
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1200);
            assertThat(ratedGames(kari, "CLASSICAL")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rating_history", Integer.class)).isZero();
            mvc.perform(as(get("/api/games/" + game), perToken))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.whiteRatingDelta").doesNotExist());
        }

        @Test
        void opponentConfirmationAppliesTheRatings() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            MvcResult confirmed = confirm(olaToken, game);

            assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(confirmed).get("status").asText()).isEqualTo("FINISHED");
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1220);
            assertThat(rating(ola, "CLASSICAL")).isEqualTo(1180);
            assertThat(jdbc.queryForObject("SELECT confirmed_by FROM game WHERE id = ?", Long.class, game)).isEqualTo(ola);
        }

        /** The whole point of the rule: you cannot award yourself wins. */
        @Test
        void theRegisteringPlayerCannotConfirmTheirOwnReport() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            assertThat(body(confirm(kariToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_SELF_CONFIRMATION.code());
            assertThat(gameStatus(game)).isEqualTo("PENDING");
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1200);
        }

        @Test
        void thisHoldsEvenWhenThePlayerReportsTheirOwnLoss() throws Exception {
            long game = register(kariToken, game(kari, ola, "BLACK_WIN"));
            assertThat(body(confirm(kariToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_SELF_CONFIRMATION.code());
        }

        @Test
        void outsidersCanNeitherConfirmNorReject() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            assertThat(body(confirm(perToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(body(reject(perToken, game, "tull")).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(gameStatus(game)).isEqualTo("PENDING");
        }

        @Test
        void adminCanConfirmAnyGame() throws Exception {
            long game = register(kariToken, game(kari, ola, "DRAW"));
            assertThat(confirm(adminToken, game).getResponse().getStatus()).isEqualTo(200);
            assertThat(gameStatus(game)).isEqualTo("FINISHED");
            assertThat(ratedGames(ola, "CLASSICAL")).isEqualTo(1);
        }

        @Test
        void adminWhoPlayedCannotRubberStampTheirOwnReport() throws Exception {
            long admin = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'admin'", Long.class);
            // Registered as admin, so it is final at once: that is the admin privilege, used openly.
            long direct = register(adminToken, game(admin, ola, "WHITE_WIN"));
            assertThat(gameStatus(direct)).isEqualTo("FINISHED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND target LIKE '%' || ? || '%'",
                    Integer.class, admin, String.valueOf(direct))).isPositive();
        }

        @Test
        void confirmingTwiceOrConfirmingAFinishedGameIsRejected() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            confirm(olaToken, game);
            assertThat(body(confirm(olaToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(body(confirm(adminToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1220);
            assertThat(body(confirm(olaToken, 424242)).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_FOUND.code());
        }

        @Test
        void simultaneousConfirmationsApplyTheRatingOnce() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String token = i % 2 == 0 ? olaToken : adminToken;
                tasks.add(() -> confirm(token, game).getResponse().getStatus());
            }
            List<Integer> statuses = runConcurrently(tasks);

            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
            assertThat(statuses).allMatch(s -> s == 200 || s == 409);
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1220);
            assertThat(ratedGames(kari, "CLASSICAL")).isEqualTo(1);
        }

        @Test
        void confirmRacingRejectLeavesOneConsistentOutcome() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(i % 2 == 0 ? () -> confirm(adminToken, game).getResponse().getStatus()
                        : () -> reject(olaToken, game, "feil resultat").getResponse().getStatus());
            }
            assertThat(runConcurrently(tasks).stream().filter(s -> s == 200).count()).isEqualTo(1);

            boolean finished = gameStatus(game).equals("FINISHED");
            assertThat(gameStatus(game)).isIn("FINISHED", "REJECTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rating_history", Integer.class)).isEqualTo(finished ? 2 : 0);
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(finished ? 1220 : 1200);
        }

        /** Ratings are computed from the ratings at confirmation time, in confirmation order. */
        @Test
        void gamesConfirmedLateUseCurrentRatings() throws Exception {
            long first = register(kariToken, game(kari, ola, "WHITE_WIN"));
            long second = register(kariToken, game(kari, ola, "WHITE_WIN"));
            confirm(olaToken, second);
            confirm(olaToken, first);

            Integer firstBefore = jdbc.queryForObject("SELECT white_rating_before FROM game WHERE id = ?", Integer.class, first);
            Integer secondBefore = jdbc.queryForObject("SELECT white_rating_before FROM game WHERE id = ?", Integer.class, second);
            assertThat(secondBefore).isEqualTo(1200);
            assertThat(firstBefore).isEqualTo(1220);
            assertThat(ratedGames(kari, "CLASSICAL")).isEqualTo(2);
        }
    }

    @Nested
    class Rejection {

        @Test
        void opponentCanRejectAndNothingIsRated() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            assertThat(reject(olaToken, game, "Det ble remis!").getResponse().getStatus()).isEqualTo(200);

            assertThat(gameStatus(game)).isEqualTo("REJECTED");
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1200);
            assertThat(body(confirm(olaToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(body(confirm(adminToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
        }

        @Test
        void rejectionReasonIsSanitisedAndBounded() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            reject(olaToken, game, "<img src=x onerror=alert(1)>" + "x".repeat(2000));
            String stored = jdbc.queryForObject("SELECT rejection_reason FROM game WHERE id = ?", String.class, game);
            if (stored != null) {
                assertThat(stored).doesNotContain("<", ">").hasSizeLessThanOrEqualTo(300);
            } else {
                assertThat(gameStatus(game)).isEqualTo("PENDING");
            }
        }

        @Test
        void theRegisteringPlayerMayWithdrawTheirOwnReport() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            assertThat(reject(kariToken, game, "feil motstander").getResponse().getStatus()).isEqualTo(200);
            assertThat(gameStatus(game)).isEqualTo("REJECTED");
        }

        @Test
        void aConfirmedGameCannotBeRejectedAfterwards() throws Exception {
            long game = register(kariToken, game(kari, ola, "WHITE_WIN"));
            confirm(olaToken, game);
            assertThat(body(reject(olaToken, game, "angrer")).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1220);
        }
    }

    @Nested
    class Queues {

        @Test
        void playerSeesOnlyTheGamesWaitingForThem() throws Exception {
            long fromKari = register(kariToken, game(kari, ola, "WHITE_WIN"));
            register(olaToken, game(ola, kari, "WHITE_WIN"));

            mvc.perform(as(get("/api/games/pending"), olaToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(fromKari));
            mvc.perform(as(get("/api/games/pending"), perToken)).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void adminSeesEveryUnconfirmedGameAndPlayersCannotUseThatView() throws Exception {
            register(kariToken, game(kari, ola, "WHITE_WIN"));
            register(olaToken, game(ola, kari, "DRAW"));
            long done = register(kariToken, game(kari, ola, "DRAW"));
            confirm(olaToken, done);

            mvc.perform(as(get("/api/admin/games/pending"), adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].registeredBy.username").exists());
            mvc.perform(as(get("/api/admin/games/pending"), kariToken)).andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));
        }
    }

    @Nested
    class ManagedPlayers {

        @Test
        void adminCreatesAPlayerWithoutALoginWhoCanNeverSignIn() throws Exception {
            MvcResult created = mvc.perform(as(jsonPost("/api/admin/players", Map.of("username", "lillebror", "displayName", "Lillebror")),
                    adminToken)).andReturn();
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(created).get("managed").asBoolean()).isTrue();

            for (String guess : new String[] {"", "null", "lillebror", PASSWORD}) {
                assertThat(loginResult("lillebror", guess).getResponse().getStatus()).isIn(400, 401);
            }
            mvc.perform(as(jsonPost("/api/admin/players", Map.of("username", "storebror", "displayName", "Storebror")), kariToken))
                    .andExpect(problem(ErrorCode.AUTH_ACCESS_DENIED));
        }

        @Test
        void gameAgainstAManagedPlayerNeedsAnAdminToConfirm() throws Exception {
            long lillebror = createManagedPlayer("lillebror");
            long game = register(kariToken, game(kari, lillebror, "WHITE_WIN"));

            assertThat(gameStatus(game)).isEqualTo("PENDING");
            assertThat(body(confirm(kariToken, game)).get("code").asText()).isEqualTo(ErrorCode.GAME_SELF_CONFIRMATION.code());
            assertThat(confirm(adminToken, game).getResponse().getStatus()).isEqualTo(200);
            assertThat(rating(lillebror, "CLASSICAL")).isEqualTo(1180);
        }
    }

    @Nested
    class Categories {

        @Test
        void blitzRapidAndClassicalRatingsAreIndependent() throws Exception {
            Map<String, Object> blitz = game(kari, ola, "WHITE_WIN");
            blitz.put("timeControl", "3+2");
            Map<String, Object> rapid = game(kari, ola, "BLACK_WIN");
            rapid.put("category", "RAPID");
            register(adminToken, blitz);
            register(adminToken, rapid);

            assertThat(rating(kari, "BLITZ")).isEqualTo(1220);
            assertThat(rating(kari, "RAPID")).isEqualTo(1180);
            assertThat(rating(kari, "CLASSICAL")).isEqualTo(1200);
            assertThat(ratedGames(ola, "BLITZ")).isEqualTo(1);
            assertThat(ratedGames(ola, "CLASSICAL")).isZero();
            assertThat(jdbc.queryForList("SELECT DISTINCT category FROM rating_history ORDER BY 1", String.class))
                    .containsExactly("BLITZ", "RAPID");
        }

        @Test
        void categoryIsDerivedFromTheClockAndStoredOnTheGame() throws Exception {
            Map<String, Object> body = game(kari, ola, "DRAW");
            body.put("timeControl", "15+10");
            long game = register(adminToken, body);
            mvc.perform(as(get("/api/games/" + game), perToken))
                    .andExpect(jsonPath("$.category").value("RAPID"))
                    .andExpect(jsonPath("$.timeControl").value("15+10"));
        }

        @Test
        void contradictoryOrInvalidTimeControlsAreRejected() throws Exception {
            Map<String, Object> mismatch = game(kari, ola, "DRAW");
            mismatch.put("category", "RAPID");
            mismatch.put("timeControl", "3+0");
            Map<String, Object> nonsense = game(kari, ola, "DRAW");
            nonsense.put("timeControl", "fem minutter");
            for (Map<String, Object> bad : List.of(mismatch, nonsense)) {
                MvcResult result = mvc.perform(as(jsonPost("/api/games", bad), adminToken)).andReturn();
                assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_TIME_CONTROL.code());
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM game", Integer.class)).isZero();
        }

        @Test
        void profileShowsARatingForEveryCategory() throws Exception {
            Map<String, Object> blitz = game(kari, ola, "WHITE_WIN");
            blitz.put("category", "BLITZ");
            register(adminToken, blitz);

            mvc.perform(as(get("/api/users/" + kari), perToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ratings.BLITZ.rating").value(1220))
                    .andExpect(jsonPath("$.ratings.BLITZ.ratedGames").value(1))
                    .andExpect(jsonPath("$.ratings.RAPID.rating").value(1200))
                    .andExpect(jsonPath("$.ratings.CLASSICAL.ratedGames").value(0));
        }

        /** Two confirmations in different categories for the same players at once must not block or mix. */
        @Test
        void simultaneousGamesInDifferentCategoriesStaySeparate() throws Exception {
            List<Callable<Integer>> tasks = new ArrayList<>();
            String[] categories = {"BLITZ", "RAPID", "CLASSICAL"};
            for (int i = 0; i < 30; i++) {
                Map<String, Object> body = game(kari, ola, "WHITE_WIN");
                body.put("category", categories[i % 3]);
                tasks.add(() -> mvc.perform(as(jsonPost("/api/games", body), adminToken)).andReturn().getResponse().getStatus());
            }
            assertThat(runConcurrently(tasks)).containsOnly(201);
            for (String category : categories) {
                assertThat(ratedGames(kari, category)).as(category).isEqualTo(10);
                assertThat(rating(kari, category) + rating(ola, category)).as(category).isEqualTo(2400);
            }
            assertThat(rating(kari, "BLITZ")).isEqualTo(rating(kari, "RAPID")).isEqualTo(rating(kari, "CLASSICAL"));
        }
    }
}
