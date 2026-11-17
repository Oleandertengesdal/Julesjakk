package no.sjakkportal.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** The schema is the last line of defence; these tests attack it directly with SQL. */
class SchemaMigrationTest extends ApiIntegrationTest {

    private long game(long white, long black, String status, String result) {
        return jdbc.queryForObject("""
                INSERT INTO game (white_id, black_id, status, result, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, white, black, status, result, white);
    }

    @Test
    void flywayAppliedTheBaseline() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void allExpectedTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("app_user", "player_rating", "invite_code", "invite_redemption", "refresh_token",
                "password_reset_token", "audit_log", "tournament", "tournament_participant", "tournament_round",
                "tournament_pairing", "game", "game_move", "game_analysis", "rating_history");
    }

    @Test
    void concurrentlyUpdatedTablesHaveAVersionColumnForOptimisticLocking() {
        List<String> versioned = jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'version' AND is_nullable = 'NO'""", String.class);
        assertThat(versioned).contains("app_user", "player_rating", "invite_code", "game", "game_analysis", "tournament",
                "tournament_participant", "tournament_pairing");
    }

    @Test
    void everyForeignKeyColumnIsIndexed() {
        // An unindexed FK makes deletes and joins scan whole tables. Checks the leading index column.
        List<String> unindexed = jdbc.queryForList("""
                SELECT c.conrelid::regclass || '.' || a.attname
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
                  AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1])
                  AND a.attname NOT IN ('created_by', 'submitted_by')""", String.class);
        assertThat(unindexed).isEmpty();
    }

    @Test
    void usernamesAreUnique() {
        createPlayer("kari");
        assertThatThrownBy(() -> createPlayer("kari")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void usernamesMustBeLowercaseSoCaseVariantsCannotImpersonate() {
        assertThatThrownBy(() -> createPlayer("Kari")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> createPlayer("ka ri")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> createPlayer("k")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownRolesAreRejected() {
        assertThatThrownBy(() -> createUser("mallory", "SUPERUSER")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inviteCannotBeRedeemedBeyondItsLimitEvenBySql() {
        long admin = createAdmin("admin");
        long invite = createInvite(admin, "JUL-2026", 2, null);
        jdbc.update("UPDATE invite_code SET use_count = 2 WHERE id = ?", invite);
        assertThatThrownBy(() -> jdbc.update("UPDATE invite_code SET use_count = use_count + 1 WHERE id = ?", invite))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unlimitedInvitesHaveNoCeiling() {
        long admin = createAdmin("admin");
        long invite = createInvite(admin, "FAMILIE-TREFF", null, null);
        assertThat(jdbc.update("UPDATE invite_code SET use_count = 5000 WHERE id = ?", invite)).isEqualTo(1);
    }

    @Test
    void inviteWithZeroUsesIsMeaningless() {
        long admin = createAdmin("admin");
        assertThatThrownBy(() -> createInvite(admin, "NULL-BRUK", 0, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAccountCanOnlyComeFromOneInvite() {
        long admin = createAdmin("admin");
        long player = createPlayer("kari");
        long first = createInvite(admin, "KODE-EN", null, null);
        long second = createInvite(admin, "KODE-TO", null, null);
        jdbc.update("INSERT INTO invite_redemption (invite_code_id, user_id) VALUES (?, ?)", first, player);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO invite_redemption (invite_code_id, user_id) VALUES (?, ?)", second, player))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aPlayerCannotPlayThemselves() {
        long kari = createPlayer("kari");
        assertThatThrownBy(() -> game(kari, kari, "FINISHED", "DRAW")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void finishedGamesNeedAResultAndUnfinishedGamesMustNotHaveOne() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        assertThatThrownBy(() -> game(kari, ola, "FINISHED", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> game(kari, ola, "LIVE", "WHITE_WIN")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(game(kari, ola, "LIVE", null)).isPositive();
    }

    @Test
    void unknownResultsAreRejected() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        assertThatThrownBy(() -> game(kari, ola, "FINISHED", "KARI_WON")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aGameCanOnlyMoveAPlayersRatingOnce() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        long game = game(kari, ola, "FINISHED", "WHITE_WIN");
        jdbc.update("INSERT INTO rating_history (user_id, game_id, category, rating_before, rating_after) VALUES (?, ?, 'RAPID', 1200, 1220)", kari, game);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO rating_history (user_id, game_id, category, rating_before, rating_after) VALUES (?, ?, 'RAPID', 1220, 1240)", kari, game))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theSameOnlineGameCannotBeImportedTwice() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        String insert = """
                INSERT INTO game (white_id, black_id, status, result, source, external_id, created_by)
                VALUES (?, ?, 'FINISHED', 'DRAW', ?, ?, ?)""";
        jdbc.update(insert, kari, ola, "LICHESS", "abcd1234", kari);
        assertThatThrownBy(() -> jdbc.update(insert, ola, kari, "LICHESS", "abcd1234", ola)).isInstanceOf(DuplicateKeyException.class);
        // Same id on another site is a different game, and games without an external id never collide.
        assertThat(jdbc.update(insert, kari, ola, "CHESS_COM", "abcd1234", kari)).isEqualTo(1);
        assertThat(game(kari, ola, "FINISHED", "DRAW")).isPositive();
        assertThat(game(kari, ola, "FINISHED", "DRAW")).isPositive();
    }

    @Test
    void ratingsStayWithinSaneBounds() {
        long kari = createPlayer("kari");
        jdbc.update("INSERT INTO player_rating (user_id, category) VALUES (?, 'BLITZ')", kari);
        assertThatThrownBy(() -> jdbc.update("UPDATE player_rating SET rating = 50 WHERE user_id = ?", kari))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE player_rating SET rated_games = -1 WHERE user_id = ?", kari))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO player_rating (user_id, category) VALUES (?, 'BULLET')", kari))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPlayerHasExactlyOneRatingPerCategory() {
        long kari = createPlayer("kari");
        jdbc.update("INSERT INTO player_rating (user_id, category) VALUES (?, 'BLITZ')", kari);
        jdbc.update("INSERT INTO player_rating (user_id, category) VALUES (?, 'RAPID')", kari);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO player_rating (user_id, category) VALUES (?, 'BLITZ')", kari))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void unconfirmedGamesCarryAResultButNeverARatingChange() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        long pending = game(kari, ola, "PENDING", "WHITE_WIN");
        assertThatThrownBy(() -> game(kari, ola, "PENDING", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE game SET white_rating_delta = 20 WHERE id = ?", pending))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void managedPlayersHaveNoPasswordAndEveryoneElseMustHaveOne() {
        assertThat(createManagedPlayer("lillebror")).isPositive();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (username, display_name, managed, password_hash) VALUES ('snik', 'Snik', TRUE, 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user (username, display_name) VALUES ('tom', 'Tom')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (username, display_name, managed, role) VALUES ('sjef', 'Sjef', TRUE, 'ADMIN')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void linkedOnlineAccountsAreUniqueIgnoringCase() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        jdbc.update("UPDATE app_user SET lichess_username = 'MagnusFan' WHERE id = ?", kari);
        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET lichess_username = 'magnusfan' WHERE id = ?", ola))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.update("UPDATE app_user SET chess_com_username = 'magnusfan' WHERE id = ?", ola)).isEqualTo(1);
    }

    @Test
    void knockoutTournamentsMustSayHowDrawsAreSettled() {
        long admin = createAdmin("admin");
        String insert = "INSERT INTO tournament (name, format, draw_resolution, created_by) VALUES ('Cup', ?, ?, ?)";
        assertThatThrownBy(() -> jdbc.update(insert, "SINGLE_ELIMINATION", null, admin)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "SWISS", "ARMAGEDDON", admin)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "DOUBLE_ELIMINATION", "COIN_TOSS", admin)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, "DOUBLE_ELIMINATION", "BLITZ_PLAYOFF", admin)).isEqualTo(1);
    }

    @Test
    void liveMovesAreUniquePerPly() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        long game = game(kari, ola, "LIVE", null);
        String insert = "INSERT INTO game_move (game_id, ply, san, uci, fen_after) VALUES (?, ?, 'e4', 'e2e4', 'x')";
        jdbc.update(insert, game, 1);
        // Two spectators' browsers submitting "the next move" at once: only one can win.
        assertThatThrownBy(() -> jdbc.update(insert, game, 1)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(insert, game, 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPlayerJoinsATournamentOnlyOnce() {
        long admin = createAdmin("admin");
        long kari = createPlayer("kari");
        long tournament = jdbc.queryForObject(
                "INSERT INTO tournament (name, format, created_by) VALUES ('Julecup', 'SWISS', ?) RETURNING id", Long.class, admin);
        String insert = "INSERT INTO tournament_participant (tournament_id, user_id, seed, seed_rating) VALUES (?, ?, ?, 1200)";
        jdbc.update(insert, tournament, kari, 1);
        assertThatThrownBy(() -> jdbc.update(insert, tournament, kari, 2)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void unknownTournamentFormatsAreRejected() {
        long admin = createAdmin("admin");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tournament (name, format, created_by) VALUES ('X', 'BATTLE_ROYALE', ?)", admin))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAUserWithGamesIsBlockedSoHistoryCannotVanish() {
        long kari = createPlayer("kari");
        long ola = createPlayer("ola");
        game(kari, ola, "FINISHED", "DRAW");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM app_user WHERE id = ?", kari))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAUserRemovesTheirSessions() {
        long kari = createPlayer("kari");
        jdbc.update("""
                INSERT INTO refresh_token (user_id, token_hash, family_id, expires_at)
                VALUES (?, repeat('a', 64), gen_random_uuid(), now() + interval '7 days')""", kari);
        jdbc.update("DELETE FROM app_user WHERE id = ?", kari);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token", Integer.class)).isZero();
    }
}
