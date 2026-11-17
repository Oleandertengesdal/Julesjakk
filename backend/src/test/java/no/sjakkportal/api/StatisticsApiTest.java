package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Profiles, rating graphs, leaderboards and the head-to-head card on the match page. */
class StatisticsApiTest extends ApiIntegrationTest {

    private long kari;
    private long ola;
    private long per;
    private String adminToken;
    private String kariToken;

    @BeforeEach
    void setUp() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        per = createPlayer("per");
        adminToken = login("admin");
        kariToken = login("kari");
    }

    private long game(long white, long black, String result, String category) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("whiteId", white);
        body.put("blackId", black);
        body.put("result", result);
        body.put("category", category);
        MvcResult created = mvc.perform(as(jsonPost("/api/games", body), adminToken)).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return body(created).get("id").asLong();
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mvc.perform(as(get(url), kariToken)).andReturn();
        assertThat(result.getResponse().getStatus()).as(url + " " + result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    /** Kari: W W W L(as black v per) D, then 5 wins in a row against Ola. */
    private void playSeason() throws Exception {
        game(kari, ola, "WHITE_WIN", "RAPID");
        game(ola, kari, "BLACK_WIN", "RAPID");
        game(kari, per, "WHITE_WIN", "RAPID");
        game(per, kari, "WHITE_WIN", "RAPID");
        game(kari, per, "DRAW", "RAPID");
        game(per, kari, "WHITE_WIN", "BLITZ");
        game(per, kari, "WHITE_WIN", "BLITZ");
        game(per, kari, "WHITE_WIN", "BLITZ");
        for (int i = 0; i < 5; i++) {
            game(kari, ola, "WHITE_WIN", "CLASSICAL");
        }
    }

    @Test
    void profileStatisticsCountEveryFinishedGame() throws Exception {
        playSeason();
        JsonNode stats = getJson("/api/users/" + kari + "/stats");

        assertThat(stats.get("games").asInt()).isEqualTo(13);
        assertThat(stats.get("wins").asInt()).isEqualTo(8);
        assertThat(stats.get("draws").asInt()).isEqualTo(1);
        assertThat(stats.get("losses").asInt()).isEqualTo(4);
        assertThat(stats.at("/asWhite/games").asInt()).isEqualTo(8);
        assertThat(stats.at("/asWhite/wins").asInt()).isEqualTo(7);
        assertThat(stats.at("/asBlack/losses").asInt()).isEqualTo(4);
        assertThat(stats.get("longestWinStreak").asInt()).isEqualTo(5);
        assertThat(stats.at("/currentStreak/type").asText()).isEqualTo("WIN");
        assertThat(stats.at("/currentStreak/length").asInt()).isEqualTo(5);
        assertThat(stats.get("recentForm")).hasSize(5);
        assertThat(stats.get("recentForm").get(0).asText()).isEqualTo("W");
        // Per has beaten Kari four times in seven games: the nemesis. Ola is the most frequent opponent.
        assertThat(stats.at("/nemesis/user/username").asText()).isEqualTo("per");
        assertThat(stats.at("/favouriteOpponent/user/username").asText()).isEqualTo("ola");
        assertThat(stats.at("/favouriteOpponent/games").asInt()).isEqualTo(7);
    }

    @Test
    void statisticsCanBeLimitedToOneCategory() throws Exception {
        playSeason();
        JsonNode blitz = getJson("/api/users/" + kari + "/stats?category=BLITZ");
        assertThat(blitz.get("games").asInt()).isEqualTo(3);
        assertThat(blitz.get("losses").asInt()).isEqualTo(3);
        JsonNode bad = body(mvc.perform(as(get("/api/users/" + kari + "/stats?category=BULLET"), kariToken)).andReturn());
        assertThat(bad.get("code").asText()).isEqualTo(ErrorCode.MALFORMED_REQUEST.code());
    }

    @Test
    void ratingsShowCurrentAndPeakPerCategory() throws Exception {
        playSeason();
        JsonNode stats = getJson("/api/users/" + kari + "/stats");
        assertThat(stats.at("/ratings/BLITZ/rating").asInt()).isLessThan(1200);
        assertThat(stats.at("/ratings/BLITZ/peak").asInt()).isEqualTo(1200);
        assertThat(stats.at("/ratings/CLASSICAL/peak").asInt()).isEqualTo(stats.at("/ratings/CLASSICAL/rating").asInt());
        assertThat(stats.at("/ratings/CLASSICAL/ratedGames").asInt()).isEqualTo(5);
    }

    @Test
    void ratingHistoryStartsAtTheInitialRatingAndChainsGameByGame() throws Exception {
        playSeason();
        JsonNode history = getJson("/api/users/" + kari + "/rating-history?category=CLASSICAL");
        assertThat(history).hasSize(6);
        assertThat(history.get(0).get("rating").asInt()).isEqualTo(1200);
        assertThat(history.get(0).get("gameId").isNull()).isTrue();
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).get("rating").asInt()).isGreaterThan(history.get(i - 1).get("rating").asInt());
        }
        assertThat(history.get(5).get("rating").asInt()).isEqualTo(getJson("/api/users/" + kari).at("/ratings/CLASSICAL/rating").asInt());
    }

    @Test
    void headToHeadCardHasRecordFormOddsAndStakes() throws Exception {
        playSeason();
        JsonNode card = getJson("/api/matchups?a=" + kari + "&b=" + per + "&category=RAPID");

        assertThat(card.at("/headToHead/games").asInt()).isEqualTo(6);
        assertThat(card.at("/headToHead/aWins").asInt()).isEqualTo(1);
        assertThat(card.at("/headToHead/draws").asInt()).isEqualTo(1);
        assertThat(card.at("/headToHead/bWins").asInt()).isEqualTo(4);
        assertThat(card.get("recentGames").size()).isEqualTo(6);
        assertThat(card.get("formA").size()).isLessThanOrEqualTo(5);
        double total = card.at("/probabilities/aWin").asDouble() + card.at("/probabilities/draw").asDouble()
                + card.at("/probabilities/bWin").asDouble();
        assertThat(total).isCloseTo(1.0, within(1e-6));
        int ratingA = card.get("ratingA").asInt();
        int ratingB = card.get("ratingB").asInt();
        assertThat(card.at("/stakesA/onWin").asInt()).isPositive();
        assertThat(card.at("/stakesA/onLoss").asInt()).isNegative();
        if (ratingA < ratingB) {
            assertThat(card.at("/stakesA/onWin").asInt()).isGreaterThan(-card.at("/stakesA/onLoss").asInt());
        }
    }

    @Test
    void matchPagePreviewUsesTheGamesPlayersAndCategory() throws Exception {
        long game = game(kari, ola, "WHITE_WIN", "BLITZ");
        JsonNode preview = getJson("/api/games/" + game + "/preview");
        assertThat(preview.at("/playerA/id").asLong()).isEqualTo(kari);
        assertThat(preview.at("/playerB/id").asLong()).isEqualTo(ola);
        assertThat(preview.get("category").asText()).isEqualTo("BLITZ");
    }

    @Test
    void matchupNeedsTwoDifferentExistingPlayers() throws Exception {
        assertThat(body(mvc.perform(as(get("/api/matchups?a=" + kari + "&b=" + kari + "&category=RAPID"), kariToken)).andReturn())
                .get("code").asText()).isEqualTo(ErrorCode.GAME_SAME_PLAYER.code());
        assertThat(body(mvc.perform(as(get("/api/matchups?a=" + kari + "&b=987654&category=RAPID"), kariToken)).andReturn())
                .get("code").asText()).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
    }

    @Test
    void leaderboardListsOnlyRatedPlayersBestFirst() throws Exception {
        game(kari, ola, "WHITE_WIN", "BLITZ");
        game(kari, ola, "WHITE_WIN", "BLITZ");
        JsonNode board = getJson("/api/leaderboard?category=BLITZ");
        assertThat(board).hasSize(2);
        assertThat(board.get(0).at("/user/username").asText()).isEqualTo("kari");
        assertThat(board.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(board.get(1).at("/user/username").asText()).isEqualTo("ola");
        assertThat(getJson("/api/leaderboard?category=RAPID")).isEmpty();
    }

    @Test
    void gameListsArePagedNewestFirst() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(game(kari, ola, "DRAW", "RAPID"));
        }
        game(ola, per, "DRAW", "RAPID");
        JsonNode mine = getJson("/api/users/" + kari + "/games?limit=3");
        assertThat(mine).hasSize(3);
        assertThat(mine.get(0).get("id").asLong()).isEqualTo(created.get(4));
        assertThat(mine.get(0).has("moves")).isFalse();
        JsonNode older = getJson("/api/users/" + kari + "/games?limit=3&before=" + mine.get(2).get("id").asLong());
        assertThat(older).hasSize(2);
        assertThat(getJson("/api/games?limit=10")).hasSize(6);
        assertThat(mvc.perform(as(get("/api/games?limit=100000"), kariToken)).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void badgesAreAwardedFromWhatHappened() throws Exception {
        playSeason();
        JsonNode badges = getJson("/api/users/" + kari + "/stats").get("badges");
        List<String> codes = new ArrayList<>();
        badges.forEach(b -> codes.add(b.get("code").asText()));
        assertThat(codes).contains("FIRST_WIN", "TEN_GAMES", "WIN_STREAK_5", "ALL_ROUNDER");
        assertThat(codes).doesNotContain("FIFTY_GAMES", "TOURNAMENT_WINNER");
        badges.forEach(b -> {
            assertThat(b.get("title").asText()).isNotBlank();
            assertThat(b.get("description").asText()).isNotBlank();
        });
        JsonNode fresh = getJson("/api/users/" + per + "/stats?category=BLITZ");
        assertThat(fresh.get("games").asInt()).isEqualTo(3);
    }

    @Test
    void giantKillerNeedsAWinAgainstSomeoneRatedFarHigher() throws Exception {
        jdbc.update("INSERT INTO player_rating (user_id, category, rating, rated_games) VALUES (?, 'RAPID', 1600, 30)", ola);
        game(kari, ola, "WHITE_WIN", "RAPID");
        List<String> codes = new ArrayList<>();
        getJson("/api/users/" + kari + "/stats").get("badges").forEach(b -> codes.add(b.get("code").asText()));
        assertThat(codes).contains("GIANT_KILLER");
    }

    @Test
    void accuracyAndBlunderRateComeFromSavedAnalyses() throws Exception {
        long game = game(kari, ola, "WHITE_WIN", "RAPID");
        jdbc.update("""
                INSERT INTO game_analysis (game_id, engine, depth, white_accuracy, black_accuracy, white_acpl, black_acpl,
                    white_blunders, black_blunders, white_brilliancies, moves, submitted_by)
                VALUES (?, 'Stockfish', 16, 92.5, 40, 12, 150, 0, 3, 1, '[]'::jsonb, ?)""", game, kari);
        JsonNode stats = getJson("/api/users/" + kari + "/stats");
        assertThat(stats.get("averageAccuracy").asDouble()).isEqualTo(92.5);
        assertThat(stats.get("analysedGames").asInt()).isEqualTo(1);
        assertThat(stats.get("blundersPerGame").asDouble()).isZero();
        List<String> codes = new ArrayList<>();
        stats.get("badges").forEach(b -> codes.add(b.get("code").asText()));
        assertThat(codes).contains("SHARPSHOOTER", "BRILLIANT");
        assertThat(getJson("/api/users/" + ola + "/stats").get("blundersPerGame").asDouble()).isEqualTo(3.0);
    }

    @Test
    void playerWithoutGamesHasEmptyButCompleteStatistics() throws Exception {
        JsonNode stats = getJson("/api/users/" + per + "/stats");
        assertThat(stats.get("games").asInt()).isZero();
        assertThat(stats.get("averageAccuracy").isNull()).isTrue();
        assertThat(stats.get("nemesis").isNull()).isTrue();
        assertThat(stats.get("badges")).isEmpty();
        assertThat(stats.at("/ratings/RAPID/rating").asInt()).isEqualTo(1200);
        assertThat(body(mvc.perform(as(get("/api/users/987654/stats"), kariToken)).andReturn()).get("code").asText())
                .isEqualTo(ErrorCode.USER_NOT_FOUND.code());
    }
}
