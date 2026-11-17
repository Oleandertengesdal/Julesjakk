package no.sjakkportal.importer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import no.sjakkportal.chess.ChessRules;
import no.sjakkportal.chess.ParsedGame;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.game.GameService;
import no.sjakkportal.game.GameView;
import no.sjakkportal.rating.GameResult;
import no.sjakkportal.rating.TimeCategory;
import no.sjakkportal.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Imports a finished online game between two family members, identified through the Lichess or
 * Chess.com usernames linked on their profiles. Imported games are for analysis: always unrated.
 */
@Service
public class ImportService {

    private static final DateTimeFormatter PGN_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final ExternalGameFetcher fetcher;
    private final GameService gameService;
    private final JdbcClient jdbc;

    public ImportService(ExternalGameFetcher fetcher, GameService gameService, JdbcClient jdbc) {
        this.fetcher = fetcher;
        this.gameService = gameService;
        this.jdbc = jdbc;
    }

    public GameView importGame(CurrentUser actor, String url, String pgn) {
        ExternalGameRef ref;
        String text;
        if (url != null) {
            ref = ExternalLinks.parse(url).orElseThrow(() -> new ChessException(ErrorCode.IMPORT_UNSUPPORTED_SOURCE));
            requireNotImported(ref);
            text = fetcher.fetchPgn(ref);
        } else {
            text = pgn;
            ref = null;
        }
        ParsedGame parsed = ChessRules.parsePgn(text);
        if (ref == null) {
            ref = identify(parsed).orElseThrow(() -> new ChessException(ErrorCode.IMPORT_UNSUPPORTED_SOURCE,
                    "PGN-teksten viser ikke hvilket Lichess- eller Chess.com-parti den kommer fra."));
            requireNotImported(ref);
        }
        GameResult result = parsed.declaredResult().or(parsed::boardOutcome)
                .orElseThrow(() -> new ChessException(ErrorCode.GAME_INVALID_STATE, "Bare ferdigspilte partier kan importeres."));

        String column = ref.source().equals("LICHESS") ? "lichess_username" : "chess_com_username";
        long white = familyMember(column, parsed.tags().get("White"));
        long black = familyMember(column, parsed.tags().get("Black"));
        if (white == black) {
            throw new ChessException(ErrorCode.IMPORT_NOT_FAMILY_GAME);
        }
        if (!actor.isAdmin() && actor.id() != white && actor.id() != black) {
            throw new ChessException(ErrorCode.GAME_NOT_PARTICIPANT);
        }
        String timeControl = timeControl(parsed.tags().get("TimeControl"));
        try {
            return gameService.importFinished(actor, new GameService.ImportCommand(white, black, result, parsed, ref.source(), ref.id(),
                    TimeCategory.fromTimeControl(timeControl), timeControl, playedAt(parsed)));
        } catch (DataIntegrityViolationException e) {
            throw new ChessException(ErrorCode.GAME_DUPLICATE_IMPORT);   // lost a race with an identical import
        }
    }

    private void requireNotImported(ExternalGameRef ref) {
        boolean exists = jdbc.sql("SELECT count(*) FROM game WHERE source = ? AND external_id = ?").params(ref.source(), ref.id())
                .query(Integer.class).single() > 0;
        if (exists) {
            throw new ChessException(ErrorCode.GAME_DUPLICATE_IMPORT);
        }
    }

    /** Pasted PGNs carry their origin: Lichess in the Site tag, Chess.com in the Link tag. */
    private static Optional<ExternalGameRef> identify(ParsedGame parsed) {
        return ExternalLinks.parse(parsed.tags().get("Site")).or(() -> ExternalLinks.parse(parsed.tags().get("Link")));
    }

    private long familyMember(String column, String siteUsername) {
        if (siteUsername == null || siteUsername.isBlank()) {
            throw new ChessException(ErrorCode.IMPORT_NOT_FAMILY_GAME);
        }
        return jdbc.sql("SELECT id FROM app_user WHERE lower(" + column + ") = lower(?) AND enabled").param(siteUsername.strip())
                .query(Long.class).optional().orElseThrow(() -> new ChessException(ErrorCode.IMPORT_NOT_FAMILY_GAME));
    }

    /** PGN time controls are in seconds ({@code 300+3}); ours are minutes ({@code 5+3}). Correspondence becomes "no clock". */
    static String timeControl(String pgnTimeControl) {
        if (pgnTimeControl == null || !pgnTimeControl.matches("[0-9]{1,6}(\\+[0-9]{1,4})?")) {
            return null;
        }
        String[] parts = pgnTimeControl.split("\\+");
        int minutes = Math.max(1, (Integer.parseInt(parts[0]) + 59) / 60);
        int increment = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        if (minutes > TimeCategory.MAX_MINUTES || increment > TimeCategory.MAX_INCREMENT_SECONDS) {
            return null;
        }
        return increment == 0 ? minutes + "+0" : minutes + "+" + increment;
    }

    private static Instant playedAt(ParsedGame parsed) {
        String date = Optional.ofNullable(parsed.tags().get("UTCDate")).orElse(parsed.tags().get("Date"));
        if (date == null) {
            return Instant.now();
        }
        try {
            LocalDate day = LocalDate.parse(date, PGN_DATE);
            LocalTime time = Optional.ofNullable(parsed.tags().get("UTCTime")).map(LocalTime::parse).orElse(LocalTime.NOON);
            Instant instant = day.atTime(time).toInstant(ZoneOffset.UTC);
            return instant.isAfter(Instant.now()) ? Instant.now() : instant;
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }
}
