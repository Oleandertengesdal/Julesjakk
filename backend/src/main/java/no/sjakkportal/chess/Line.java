package no.sjakkportal.chess;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.rating.GameResult;

/**
 * One game being played through move by move. Wraps the rules library and adds what it leaves
 * open: strict move matching, notation in both forms, and end-of-game detection that the
 * application controls (the library treats a threefold repetition as an automatic draw).
 */
final class Line {

    /** Syntactically valid SAN. Whether the move is legal is decided against the position. */
    static final Pattern SAN = Pattern.compile(
            "(?:(O-O-O|0-0-0)|(O-O|0-0)|([KQRBN])?([a-h])?([1-8])?(x)?([a-h][1-8])(?:=?([QRBNKP]))?)[+#]?[!?]{0,2}");
    static final Pattern UCI = Pattern.compile("([a-h][1-8])([a-h][1-8])([qrbn])?");

    record Outcome(Optional<GameResult> boardOutcome, Optional<Termination> termination) {
    }

    private final ChessGame game;
    private final String initialFen;
    private final Map<String, Integer> occurrences = new HashMap<>();
    final List<String> sanMoves = new ArrayList<>();
    final List<String> uciMoves = new ArrayList<>();
    final List<String> fens = new ArrayList<>();
    private boolean lastMoveGaveCheck;

    Line(String validatedFen) {
        this.initialFen = validatedFen;
        this.game = new ChessGame(validatedFen);
        occurrences.merge(Fen.repetitionKey(validatedFen), 1, Integer::sum);
    }

    String currentFen() {
        return fens.isEmpty() ? initialFen : fens.get(fens.size() - 1);
    }

    boolean lastMoveGaveCheck() {
        return lastMoveGaveCheck;
    }

    /** @return true if the SAN move was legal and unambiguous, and has been played */
    boolean playSan(String san) {
        Matcher matcher = SAN.matcher(san);
        if (!matcher.matches()) {
            return false;
        }
        Move chosen = null;
        for (Move move : game.getLegalMoves()) {
            if (matchesSan(move, matcher)) {
                if (chosen != null) {
                    return false;   // ambiguous: "Nd2" with two knights that can go there
                }
                chosen = move;
            }
        }
        if (chosen == null) {
            return false;
        }
        play(chosen);
        return true;
    }

    /** @return true if the UCI move was legal and has been played */
    boolean playUci(String uci) {
        Matcher matcher = UCI.matcher(uci);
        if (!matcher.matches()) {
            return false;
        }
        PieceType promotion = matcher.group(3) == null ? null : pieceType(Character.toUpperCase(matcher.group(3).charAt(0)));
        for (Move move : game.getLegalMoves()) {
            if (name(move.from).equals(matcher.group(1)) && name(move.to).equals(matcher.group(2)) && move.promotion == promotion) {
                play(move);
                return true;
            }
        }
        return false;
    }

    private boolean matchesSan(Move move, Matcher san) {
        Piece piece = game.getPiece(move.from);
        if (piece == null) {
            return false;
        }
        int fileDelta = move.to.x - move.from.x;
        boolean castling = piece.pieceType == PieceType.KING && Math.abs(fileDelta) == 2;
        if (san.group(1) != null) {
            return castling && fileDelta < 0;
        }
        if (san.group(2) != null) {
            return castling && fileDelta > 0;
        }
        if (castling) {
            return false;   // castling must be written O-O, never "Kg1"
        }
        PieceType wanted = san.group(3) == null ? PieceType.PAWN : pieceType(san.group(3).charAt(0));
        if (piece.pieceType != wanted || !name(move.to).equals(san.group(7))) {
            return false;
        }
        String from = name(move.from);
        if (san.group(4) != null && from.charAt(0) != san.group(4).charAt(0)) {
            return false;
        }
        if (san.group(5) != null && from.charAt(1) != san.group(5).charAt(0)) {
            return false;
        }
        if (wanted == PieceType.PAWN && san.group(4) == null && fileDelta != 0) {
            return false;   // "d5" is a push; a capture must name its file ("exd5")
        }
        PieceType promotion = san.group(8) == null ? null : pieceType(san.group(8).charAt(0));
        return move.promotion == promotion;
    }

    private void play(Move move) {
        String san = game.getNotation(NotationType.SAN, move).replaceAll("[+#]+$", "");
        String uci = name(move.from) + name(move.to) + (move.promotion == null ? "" : promotionLetter(move.promotion));
        game.playMove(move);
        String fen = game.getFen();
        boolean check = game.isKingAttacked();
        boolean noMoves = game.getLegalMoves().isEmpty();
        lastMoveGaveCheck = check;
        sanMoves.add(san + (check ? (noMoves ? "#" : "+") : ""));
        uciMoves.add(uci);
        fens.add(fen);
        occurrences.merge(Fen.repetitionKey(fen), 1, Integer::sum);
    }

    /**
     * How the current position ends the game, if it does. Checkmate, stalemate and dead positions
     * force a result; repetition and the fifty-move rule only allow a claim, so they are reported
     * as a termination without a forced outcome.
     */
    Outcome outcome() {
        String fen = currentFen();
        if (game.getLegalMoves().isEmpty()) {
            if (game.isKingAttacked()) {
                GameResult winner = Fen.whiteToMove(fen) ? GameResult.BLACK_WIN : GameResult.WHITE_WIN;
                return new Outcome(Optional.of(winner), Optional.of(Termination.CHECKMATE));
            }
            return new Outcome(Optional.of(GameResult.DRAW), Optional.of(Termination.STALEMATE));
        }
        if (Fen.insufficientMaterial(fen)) {
            return new Outcome(Optional.of(GameResult.DRAW), Optional.of(Termination.INSUFFICIENT_MATERIAL));
        }
        if (occurrences.getOrDefault(Fen.repetitionKey(fen), 0) >= 3) {
            return new Outcome(Optional.empty(), Optional.of(Termination.THREEFOLD_REPETITION));
        }
        if (Fen.halfMoveClock(fen) >= 100) {
            return new Outcome(Optional.empty(), Optional.of(Termination.FIFTY_MOVE_RULE));
        }
        return new Outcome(Optional.empty(), Optional.empty());
    }

    private static String name(io.github.wolfraam.chessgame.board.Square square) {
        return square.name.toLowerCase(Locale.ROOT);
    }

    private static PieceType pieceType(char letter) {
        return switch (letter) {
            case 'K' -> PieceType.KING;
            case 'Q' -> PieceType.QUEEN;
            case 'R' -> PieceType.ROOK;
            case 'B' -> PieceType.BISHOP;
            case 'N' -> PieceType.KNIGHT;
            default -> PieceType.PAWN;
        };
    }

    private static String promotionLetter(PieceType type) {
        return switch (type) {
            case QUEEN -> "q";
            case ROOK -> "r";
            case BISHOP -> "b";
            case KNIGHT -> "n";
            default -> "";
        };
    }
}
