package no.sjakkportal.game;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chess clock of a live game. The phone at the board keeps time, so a slow network or a
 * sleeping server never stops the clock; on every press, pause or flag it sends the clock's state
 * here. The server stores the newest state (by the phone's sequence number) and broadcasts it with
 * the game, and viewers count down from {@code updatedAt}. The server never ticks the clock; when
 * the game ends, {@link GameService} stops it where it stands.
 */
@Service
public class ChessClockService {

    public enum Side { WHITE, BLACK }

    public record Snapshot(long whiteMs, long blackMs, Side running, int sequence) {
    }

    private static final Pattern TIME_CONTROL = Pattern.compile("(\\d{1,3})(?:\\+(\\d{1,3}))?");

    private record Row(int initialMs, int incrementMs, int whiteMs, int blackMs, String running, int sequence, Timestamp updatedAt) {
        GameView.Clock view() {
            return new GameView.Clock(initialMs, incrementMs, whiteMs, blackMs, running, sequence, updatedAt.toInstant(), Instant.now());
        }
    }

    private final GameService games;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public ChessClockService(GameService games, JdbcClient jdbc, ApplicationEventPublisher events) {
        this.games = games;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public GameView.Clock update(CurrentUser actor, long gameId, Snapshot snapshot) {
        Game game = games.lockForChange(actor, gameId);
        if (game.status != GameStatus.LIVE) {
            throw new ChessException(ErrorCode.GAME_INVALID_STATE, "Klokken går bare mens partiet pågår.");
        }
        int[] control = control(game.timeControl).orElseThrow(() -> new ChessException(ErrorCode.GAME_INVALID_TIME_CONTROL,
                "Partiet har ingen tidskontroll, så det kan ikke ha sjakkur."));
        Optional<Row> stored = jdbc.sql("""
                        INSERT INTO game_clock (game_id, initial_ms, increment_ms, white_ms, black_ms, running, sequence, updated_by)
                        VALUES (:game, :initial, :increment, :white, :black, :running, :sequence, :by)
                        ON CONFLICT (game_id) DO UPDATE SET white_ms = EXCLUDED.white_ms, black_ms = EXCLUDED.black_ms,
                            running = EXCLUDED.running, sequence = EXCLUDED.sequence, updated_by = EXCLUDED.updated_by, updated_at = now()
                        WHERE game_clock.sequence < EXCLUDED.sequence
                        RETURNING initial_ms, increment_ms, white_ms, black_ms, running, sequence, updated_at""")
                .param("game", gameId).param("initial", control[0]).param("increment", control[1])
                .param("white", snapshot.whiteMs()).param("black", snapshot.blackMs())
                .param("running", snapshot.running() == null ? null : snapshot.running().name())
                .param("sequence", snapshot.sequence()).param("by", actor.id())
                .query(Row.class).optional();
        Row row = stored.orElseThrow(() -> new ChessException(ErrorCode.GAME_CONCURRENT_UPDATE,
                "Klokken har allerede en nyere stilling."));
        events.publishEvent(new GameChangedEvent(gameId));
        return row.view();
    }

    /** {@code [initialMs, incrementMs]} from a time control such as {@code 15+10} or {@code 30}. */
    static Optional<int[]> control(String timeControl) {
        if (timeControl == null) {
            return Optional.empty();
        }
        Matcher m = TIME_CONTROL.matcher(timeControl.strip());
        if (!m.matches() || Integer.parseInt(m.group(1)) == 0) {
            return Optional.empty();
        }
        int minutes = Integer.parseInt(m.group(1));
        int increment = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return Optional.of(new int[] {minutes * 60_000, increment * 1000});
    }
}
