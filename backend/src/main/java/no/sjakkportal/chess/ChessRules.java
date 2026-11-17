package no.sjakkportal.chess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.rating.GameResult;

/**
 * The single entry point for chess-rule questions on the server. Everything that arrives from
 * a client (PGN, FEN, a live move) goes through here before it is stored or broadcast.
 * All failures are {@link ChessException}s with a CHESS-PGN/FEN/GAME code;
 * no input, however malformed, may cause any other exception.
 */
public final class ChessRules {

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    /** Maximum accepted PGN size in characters. */
    public static final int MAX_PGN_LENGTH = 100_000;

    private static final int MAX_TAGS = 50;
    private static final int MAX_TAG_VALUE_LENGTH = 255;
    private static final int MAX_MOVE_INPUT_LENGTH = 10;
    private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Pattern MOVE_NUMBER = Pattern.compile("^[0-9]+\\.+");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern NAG = Pattern.compile("\\$[0-9]*");
    private static final Pattern MARKUP = Pattern.compile("<[^>]*>");

    private ChessRules() {
    }

    // ------------------------------------------------------------------------------------------
    // PGN
    // ------------------------------------------------------------------------------------------

    /** Parses and fully validates a single-game PGN. */
    public static ParsedGame parsePgn(String pgn) {
        if (pgn == null) {
            throw new ChessException(ErrorCode.PGN_MALFORMED);
        }
        if (pgn.length() > MAX_PGN_LENGTH) {
            throw new ChessException(ErrorCode.PGN_TOO_LARGE);
        }
        try {
            return parse(pgn);
        } catch (ChessException e) {
            throw e;
        } catch (RuntimeException e) {
            // Defensive: nothing a client sends may surface as an uncataloged error.
            throw new ChessException(ErrorCode.PGN_MALFORMED, ErrorCode.PGN_MALFORMED.defaultDetail(), e);
        }
    }

    private static final class Scan {
        final Map<String, String> tags = new LinkedHashMap<>();
        final List<String> moves = new ArrayList<>();
        String terminator;
    }

    private static ParsedGame parse(String pgn) {
        Scan scan = scan(pgn);
        if (scan.moves.isEmpty()) {
            throw new ChessException(ErrorCode.PGN_MALFORMED);
        }
        String initialFen = scan.tags.containsKey("FEN") ? Fen.validate(scan.tags.get("FEN")) : START_FEN;
        Line line = new Line(initialFen);
        for (int i = 0; i < scan.moves.size(); i++) {
            if (!line.playSan(scan.moves.get(i))) {
                throw new ChessException(ErrorCode.PGN_ILLEGAL_MOVE,
                        "Trekk nummer " + moveLabel(initialFen, i) + " i PGN-teksten er ikke lovlig.");
            }
        }
        Line.Outcome outcome = line.outcome();

        Optional<GameResult> fromTerminator = result(scan.terminator);
        Optional<GameResult> fromTag = result(scan.tags.get("Result"));
        if (fromTerminator.isPresent() && fromTag.isPresent() && fromTerminator.get() != fromTag.get()) {
            throw new ChessException(ErrorCode.PGN_RESULT_MISMATCH);
        }
        Optional<GameResult> declared = fromTerminator.isPresent() ? fromTerminator : fromTag;
        if (declared.isPresent() && outcome.boardOutcome().isPresent() && declared.get() != outcome.boardOutcome().get()) {
            throw new ChessException(ErrorCode.PGN_RESULT_MISMATCH);
        }

        Map<String, String> tags = new LinkedHashMap<>(scan.tags);
        if (tags.containsKey("FEN")) {
            tags.put("FEN", initialFen);
        }
        String resultToken = token(declared.or(outcome::boardOutcome));
        if (tags.containsKey("Result")) {
            tags.put("Result", resultToken);
        }
        return new ParsedGame(List.copyOf(line.sanMoves), List.copyOf(line.uciMoves), List.copyOf(line.fens), initialFen,
                declared, outcome.boardOutcome(), outcome.termination(), java.util.Collections.unmodifiableMap(tags),
                render(tags, initialFen, line.sanMoves, resultToken));
    }

    /**
     * Tokenises the PGN in one iterative pass (no recursion, so nesting depth cannot overflow the stack).
     * Comments, NAGs and variations are dropped; only tags, main-line moves and the result survive.
     */
    private static Scan scan(String pgn) {
        String text = clean(pgn);
        Scan scan = new Scan();
        int depth = 0;
        boolean finished = false;
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '{') {
                int end = text.indexOf('}', i + 1);
                if (end < 0) {
                    throw new ChessException(ErrorCode.PGN_MALFORMED);
                }
                i = end + 1;
            } else if (c == ';') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                if (depth == 0) {
                    throw new ChessException(ErrorCode.PGN_MALFORMED);
                }
                depth--;
                i++;
            } else if (c == '}' || c == ']') {
                throw new ChessException(ErrorCode.PGN_MALFORMED);
            } else if (c == '[') {
                int end = readTag(text, i, depth == 0 ? scan : null);
                if (depth == 0 && (finished || !scan.moves.isEmpty())) {
                    throw new ChessException(ErrorCode.PGN_MULTIPLE_GAMES);
                }
                i = end;
            } else {
                int end = i;
                while (end < length && !Character.isWhitespace(text.charAt(end)) && "{}()[];".indexOf(text.charAt(end)) < 0) {
                    end++;
                }
                String token = text.substring(i, end);
                i = end;
                if (depth > 0 || NAG.matcher(token).matches()) {
                    continue;
                }
                if (isResultToken(token)) {
                    if (finished) {
                        throw new ChessException(ErrorCode.PGN_MULTIPLE_GAMES);
                    }
                    scan.terminator = token;
                    finished = true;
                    continue;
                }
                String move = MOVE_NUMBER.matcher(token).replaceFirst("");
                if (move.isEmpty() || DIGITS.matcher(move).matches()) {
                    continue;   // a bare move number such as "12." or "12"
                }
                if (finished) {
                    throw new ChessException(ErrorCode.PGN_MULTIPLE_GAMES);
                }
                if (move.length() > MAX_MOVE_INPUT_LENGTH || !Line.SAN.matcher(move).matches()) {
                    throw new ChessException(ErrorCode.PGN_MALFORMED);
                }
                scan.moves.add(move);
            }
        }
        if (depth != 0) {
            throw new ChessException(ErrorCode.PGN_MALFORMED);
        }
        return scan;
    }

    /**
     * Reads {@code [Name "value"]} starting at {@code start}.
     *
     * @param into where to store the tag, or {@code null} to skip it (inside a variation)
     * @return index just past the closing bracket
     */
    private static int readTag(String text, int start, Scan into) {
        int quote = text.indexOf('"', start + 1);
        int lineEnd = text.indexOf('\n', start);
        if (quote < 0 || (lineEnd >= 0 && quote > lineEnd)) {
            throw new ChessException(ErrorCode.PGN_MALFORMED);
        }
        String name = text.substring(start + 1, quote).strip();
        StringBuilder value = new StringBuilder();
        int i = quote + 1;
        boolean closed = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == '"' || text.charAt(i + 1) == '\\')) {
                value.append(text.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                closed = true;
                i++;
                break;
            } else if (c == '\n') {
                break;
            } else {
                value.append(c);
                i++;
            }
        }
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        if (!closed || i >= text.length() || text.charAt(i) != ']') {
            throw new ChessException(ErrorCode.PGN_MALFORMED);
        }
        if (into != null && TAG_NAME.matcher(name).matches() && into.tags.size() < MAX_TAGS) {
            into.tags.putIfAbsent(name, sanitize(value.toString()));
        }
        return i + 1;
    }

    /** Drops the byte-order mark and turns control characters into spaces. */
    private static String clean(String pgn) {
        StringBuilder cleaned = new StringBuilder(pgn.length());
        for (int i = 0; i < pgn.length(); i++) {
            char c = pgn.charAt(i);
            if (c == '﻿') {
                continue;
            }
            cleaned.append(c == '\n' || c == '\t' || !Character.isISOControl(c) ? c : (c == '\r' ? '\n' : ' '));
        }
        return cleaned.toString();
    }

    /** Tag values are shown in the UI, so markup is removed here rather than trusted to be escaped later. */
    private static String sanitize(String value) {
        String text = MARKUP.matcher(value).replaceAll("").replace("<", "").replace(">", "").strip();
        return text.length() > MAX_TAG_VALUE_LENGTH ? text.substring(0, MAX_TAG_VALUE_LENGTH) : text;
    }

    private static boolean isResultToken(String token) {
        return token.equals("*") || token.equals("1-0") || token.equals("0-1") || token.equals("1/2-1/2");
    }

    private static Optional<GameResult> result(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return switch (token) {
            case "1-0" -> Optional.of(GameResult.WHITE_WIN);
            case "0-1" -> Optional.of(GameResult.BLACK_WIN);
            case "1/2-1/2" -> Optional.of(GameResult.DRAW);
            default -> Optional.empty();
        };
    }

    private static String token(Optional<GameResult> result) {
        return result.map(r -> switch (r) {
            case WHITE_WIN -> "1-0";
            case BLACK_WIN -> "0-1";
            case DRAW -> "1/2-1/2";
        }).orElse("*");
    }

    private static String moveLabel(String initialFen, int plyIndex) {
        int ply = plyIndex + (Fen.whiteToMove(initialFen) ? 0 : 1);
        return (Fen.fullMoveNumber(initialFen) + ply / 2) + (ply % 2 == 0 ? "" : "...");
    }

    private static String render(Map<String, String> tags, String initialFen, List<String> sanMoves, String resultToken) {
        StringBuilder out = new StringBuilder();
        tags.forEach((name, value) -> out.append('[').append(name).append(" \"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]\n"));
        if (!tags.isEmpty()) {
            out.append('\n');
        }
        boolean whiteToMove = Fen.whiteToMove(initialFen);
        int moveNumber = Fen.fullMoveNumber(initialFen);
        for (int i = 0; i < sanMoves.size(); i++) {
            if (whiteToMove) {
                out.append(moveNumber).append(". ");
            } else if (i == 0) {
                out.append(moveNumber).append("... ");
            }
            out.append(sanMoves.get(i)).append(' ');
            if (!whiteToMove) {
                moveNumber++;
            }
            whiteToMove = !whiteToMove;
        }
        return out.append(resultToken).toString();
    }

    // ------------------------------------------------------------------------------------------
    // FEN and live moves
    // ------------------------------------------------------------------------------------------

    /** Validates a FEN string and returns it normalised (single spaces, trimmed). */
    public static String validateFen(String fen) {
        try {
            String normalized = Fen.validate(fen);
            new Line(normalized);   // the rules library must be able to load it too
            return normalized;
        } catch (ChessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ChessException(ErrorCode.FEN_MALFORMED, ErrorCode.FEN_MALFORMED.defaultDetail(), e);
        }
    }

    /**
     * Applies {@code move} (SAN such as {@code Nf3}/{@code e8=Q}, or UCI such as {@code g1f3}/{@code e7e8q})
     * to the position reached from {@code initialFen} after {@code sanMovesSoFar}.
     * The full history is required so threefold repetition can be detected.
     */
    public static MoveOutcome applyMove(String initialFen, List<String> sanMovesSoFar, String move) {
        Line line = new Line(validateFen(initialFen));
        try {
            for (String played : sanMovesSoFar) {
                if (played == null || !line.playSan(played)) {
                    throw new ChessException(ErrorCode.GAME_INVALID_STATE);
                }
            }
            String input = move == null ? "" : move.strip();
            boolean played = !input.isEmpty() && input.length() <= MAX_MOVE_INPUT_LENGTH
                    && (Line.UCI.matcher(input).matches() ? line.playUci(input) : line.playSan(input));
            if (!played) {
                throw new ChessException(ErrorCode.GAME_ILLEGAL_MOVE);
            }
        } catch (ChessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ChessException(ErrorCode.GAME_ILLEGAL_MOVE, ErrorCode.GAME_ILLEGAL_MOVE.defaultDetail(), e);
        }
        Line.Outcome outcome = line.outcome();
        int last = line.sanMoves.size() - 1;
        return new MoveOutcome(line.sanMoves.get(last), line.uciMoves.get(last), line.fens.get(last),
                line.lastMoveGaveCheck(), outcome.boardOutcome(), outcome.termination());
    }
}
