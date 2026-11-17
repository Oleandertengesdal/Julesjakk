package no.sjakkportal.game;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.text.Sanitizer;
import no.sjakkportal.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the summary of a Stockfish analysis computed in a player's browser. The server cannot
 * re-run the engine, so it accepts the numbers only from the game's players or an admin and checks
 * that they are at least well-formed and cover exactly the moves of the game.
 */
@Service
public class AnalysisService {

    private static final Set<String> CLASSIFICATIONS =
            Set.of("BRILLIANT", "GREAT", "BEST", "EXCELLENT", "GOOD", "BOOK", "INACCURACY", "MISTAKE", "BLUNDER");
    private static final Pattern UCI_MOVE = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");

    public record MoveAnalysis(Integer ply, Integer cp, Integer mate, String best, String classification) {
    }

    public record AnalysisPayload(String engine, Integer depth, BigDecimal whiteAccuracy, BigDecimal blackAccuracy,
                                  Integer whiteAcpl, Integer blackAcpl, List<MoveAnalysis> moves) {
    }

    public record AnalysisView(long gameId, String engine, int depth, BigDecimal whiteAccuracy, BigDecimal blackAccuracy,
                               int whiteAcpl, int blackAcpl, int whiteBrilliancies, int blackBrilliancies,
                               int whiteInaccuracies, int blackInaccuracies, int whiteMistakes, int blackMistakes,
                               int whiteBlunders, int blackBlunders, JsonNode moves) {
    }

    private final GameService gameService;
    private final GameRepository games;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AnalysisService(GameService gameService, GameRepository games, JdbcClient jdbc, ObjectMapper objectMapper) {
        this.gameService = gameService;
        this.games = games;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AnalysisView save(CurrentUser actor, long gameId, AnalysisPayload payload) {
        Game game = gameService.lockForChange(actor, gameId);
        if (game.status != GameStatus.FINISHED || game.plyCount == 0) {
            throw new ChessException(ErrorCode.ANALYSIS_GAME_NOT_FINISHED);
        }
        validate(payload, game.plyCount);

        boolean whiteMovesFirst = game.initialFen == null || game.initialFen.contains(" w ");
        int[][] counts = new int[2][4];   // [white, black] x [brilliant, inaccuracy, mistake, blunder]
        for (MoveAnalysis move : payload.moves()) {
            boolean whiteMoved = (move.ply() % 2 == 1) == whiteMovesFirst;
            int column = switch (move.classification()) {
                case "BRILLIANT" -> 0;
                case "INACCURACY" -> 1;
                case "MISTAKE" -> 2;
                case "BLUNDER" -> 3;
                default -> -1;
            };
            if (column >= 0) {
                counts[whiteMoved ? 0 : 1][column]++;
            }
        }
        String movesJson;
        try {
            movesJson = objectMapper.writeValueAsString(payload.moves());
        } catch (JsonProcessingException e) {
            throw new ChessException(ErrorCode.ANALYSIS_INVALID);
        }
        // One statement, so several browsers saving at once still leave exactly one row.
        jdbc.sql("""
                        INSERT INTO game_analysis (game_id, engine, depth, white_accuracy, black_accuracy, white_acpl, black_acpl,
                            white_brilliancies, black_brilliancies, white_inaccuracies, black_inaccuracies,
                            white_mistakes, black_mistakes, white_blunders, black_blunders, moves, submitted_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (game_id) DO UPDATE SET engine = EXCLUDED.engine, depth = EXCLUDED.depth,
                            white_accuracy = EXCLUDED.white_accuracy, black_accuracy = EXCLUDED.black_accuracy,
                            white_acpl = EXCLUDED.white_acpl, black_acpl = EXCLUDED.black_acpl,
                            white_brilliancies = EXCLUDED.white_brilliancies, black_brilliancies = EXCLUDED.black_brilliancies,
                            white_inaccuracies = EXCLUDED.white_inaccuracies, black_inaccuracies = EXCLUDED.black_inaccuracies,
                            white_mistakes = EXCLUDED.white_mistakes, black_mistakes = EXCLUDED.black_mistakes,
                            white_blunders = EXCLUDED.white_blunders, black_blunders = EXCLUDED.black_blunders,
                            moves = EXCLUDED.moves, submitted_by = EXCLUDED.submitted_by,
                            version = game_analysis.version + 1, created_at = now()""")
                .params(gameId, Sanitizer.plainText(payload.engine(), 40), payload.depth(), payload.whiteAccuracy(),
                        payload.blackAccuracy(), payload.whiteAcpl(), payload.blackAcpl(),
                        counts[0][0], counts[1][0], counts[0][1], counts[1][1], counts[0][2], counts[1][2], counts[0][3], counts[1][3],
                        movesJson, actor.id())
                .update();
        return get(gameId);
    }

    @Transactional(readOnly = true)
    public AnalysisView get(long gameId) {
        if (!games.existsById(gameId)) {
            throw new ChessException(ErrorCode.GAME_NOT_FOUND);
        }
        return jdbc.sql("SELECT * FROM game_analysis WHERE game_id = ?").param(gameId)
                .query((rs, row) -> new AnalysisView(gameId, rs.getString("engine"), rs.getInt("depth"),
                        rs.getBigDecimal("white_accuracy"), rs.getBigDecimal("black_accuracy"), rs.getInt("white_acpl"),
                        rs.getInt("black_acpl"), rs.getInt("white_brilliancies"), rs.getInt("black_brilliancies"),
                        rs.getInt("white_inaccuracies"), rs.getInt("black_inaccuracies"), rs.getInt("white_mistakes"),
                        rs.getInt("black_mistakes"), rs.getInt("white_blunders"), rs.getInt("black_blunders"),
                        readJson(rs.getString("moves"))))
                .optional().orElseThrow(() -> new ChessException(ErrorCode.NOT_FOUND, "Partiet er ikke analysert ennå."));
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored analysis is not valid JSON", e);
        }
    }

    private static void validate(AnalysisPayload payload, int plyCount) {
        boolean ok = payload.engine() != null && !Sanitizer.plainText(payload.engine(), 40).isBlank()
                && payload.depth() != null && payload.depth() >= 1 && payload.depth() <= 60
                && percentage(payload.whiteAccuracy()) && percentage(payload.blackAccuracy())
                && payload.whiteAcpl() != null && payload.whiteAcpl() >= 0 && payload.whiteAcpl() <= 100_000
                && payload.blackAcpl() != null && payload.blackAcpl() >= 0 && payload.blackAcpl() <= 100_000
                && payload.moves() != null && payload.moves().size() == plyCount;
        if (ok) {
            for (int i = 0; i < payload.moves().size() && ok; i++) {
                MoveAnalysis move = payload.moves().get(i);
                ok = move != null && move.ply() != null && move.ply() == i + 1
                        && move.classification() != null && CLASSIFICATIONS.contains(move.classification())
                        && (move.cp() == null || Math.abs(move.cp()) <= 100_000)
                        && (move.mate() == null || Math.abs(move.mate()) <= 500)
                        && (move.best() == null || UCI_MOVE.matcher(move.best()).matches());
            }
        }
        if (!ok) {
            throw new ChessException(ErrorCode.ANALYSIS_INVALID);
        }
    }

    private static boolean percentage(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0;
    }
}
