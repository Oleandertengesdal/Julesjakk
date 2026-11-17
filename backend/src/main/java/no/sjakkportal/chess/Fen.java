package no.sjakkportal.chess;

import java.util.regex.Pattern;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;

/**
 * Strict FEN validation and the few board questions the rules library does not answer.
 * The library accepts positions such as an empty board, so everything is checked here first.
 */
final class Fen {

    private static final int MAX_LENGTH = 100;
    private static final Pattern CASTLING = Pattern.compile("-|K?Q?k?q?");
    private static final Pattern EN_PASSANT = Pattern.compile("-|[a-h][36]");
    private static final Pattern HALF_MOVES = Pattern.compile("[0-9]{1,3}");
    private static final Pattern FULL_MOVES = Pattern.compile("[1-9][0-9]{0,3}");
    private static final String PIECES = "KQRBNPkqrbnp";

    private Fen() {
    }

    /** @return the FEN with single spaces between its six fields */
    static String validate(String fen) {
        if (fen == null || fen.length() > MAX_LENGTH) {
            throw invalid();
        }
        String[] fields = fen.strip().split("\\s+");
        if (fields.length != 6) {
            throw invalid();
        }
        char[][] board = parseBoard(fields[0]);
        boolean whiteToMove = switch (fields[1]) {
            case "w" -> true;
            case "b" -> false;
            default -> throw invalid();
        };
        if (fields[2].isEmpty() || !CASTLING.matcher(fields[2]).matches() || !EN_PASSANT.matcher(fields[3]).matches()
                || !HALF_MOVES.matcher(fields[4]).matches() || !FULL_MOVES.matcher(fields[5]).matches()) {
            throw invalid();
        }
        checkPieces(board);
        checkCastling(board, fields[2]);
        checkEnPassant(board, fields[3], whiteToMove);

        // The side that just moved cannot have left its own king in check.
        int[] waitingKing = find(board, whiteToMove ? 'k' : 'K');
        if (isAttacked(board, waitingKing[0], waitingKing[1], whiteToMove)) {
            throw invalid();
        }
        return String.join(" ", fields);
    }

    /** @return board[row][file] with row 0 = rank 8; empty squares are {@code '.'} */
    static char[][] parseBoard(String placement) {
        String[] ranks = placement.split("/", -1);
        if (ranks.length != 8) {
            throw invalid();
        }
        char[][] board = new char[8][8];
        for (int row = 0; row < 8; row++) {
            int file = 0;
            boolean previousWasDigit = false;
            for (char c : ranks[row].toCharArray()) {
                if (c >= '1' && c <= '8') {
                    if (previousWasDigit) {
                        throw invalid();
                    }
                    previousWasDigit = true;
                    for (int k = 0; k < c - '0'; k++) {
                        if (file >= 8) {
                            throw invalid();
                        }
                        board[row][file++] = '.';
                    }
                } else if (PIECES.indexOf(c) >= 0) {
                    previousWasDigit = false;
                    if (file >= 8) {
                        throw invalid();
                    }
                    board[row][file++] = c;
                } else {
                    throw invalid();
                }
            }
            if (file != 8) {
                throw invalid();
            }
        }
        return board;
    }

    private static void checkPieces(char[][] board) {
        int whiteKings = 0;
        int blackKings = 0;
        int whitePawns = 0;
        int blackPawns = 0;
        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                char c = board[row][file];
                if (c == 'K') {
                    whiteKings++;
                } else if (c == 'k') {
                    blackKings++;
                } else if (c == 'P' || c == 'p') {
                    if (row == 0 || row == 7) {
                        throw invalid();
                    }
                    if (c == 'P') {
                        whitePawns++;
                    } else {
                        blackPawns++;
                    }
                }
            }
        }
        if (whiteKings != 1 || blackKings != 1 || whitePawns > 8 || blackPawns > 8) {
            throw invalid();
        }
        int[] white = find(board, 'K');
        int[] black = find(board, 'k');
        if (Math.abs(white[0] - black[0]) <= 1 && Math.abs(white[1] - black[1]) <= 1) {
            throw invalid();
        }
    }

    private static void checkCastling(char[][] board, String rights) {
        if (rights.equals("-")) {
            return;
        }
        boolean ok = true;
        if (rights.indexOf('K') >= 0) {
            ok &= board[7][4] == 'K' && board[7][7] == 'R';
        }
        if (rights.indexOf('Q') >= 0) {
            ok &= board[7][4] == 'K' && board[7][0] == 'R';
        }
        if (rights.indexOf('k') >= 0) {
            ok &= board[0][4] == 'k' && board[0][7] == 'r';
        }
        if (rights.indexOf('q') >= 0) {
            ok &= board[0][4] == 'k' && board[0][0] == 'r';
        }
        if (!ok) {
            throw invalid();
        }
    }

    private static void checkEnPassant(char[][] board, String square, boolean whiteToMove) {
        if (square.equals("-")) {
            return;
        }
        int file = square.charAt(0) - 'a';
        // White to move means Black just pushed a pawn two squares: target on rank 6, pawn on rank 5.
        boolean ok = whiteToMove
                ? square.charAt(1) == '6' && board[3][file] == 'p' && board[2][file] == '.' && board[1][file] == '.'
                : square.charAt(1) == '3' && board[4][file] == 'P' && board[5][file] == '.' && board[6][file] == '.';
        if (!ok) {
            throw invalid();
        }
    }

    private static int[] find(char[][] board, char piece) {
        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                if (board[row][file] == piece) {
                    return new int[] {row, file};
                }
            }
        }
        throw invalid();
    }

    private static boolean isAttacked(char[][] board, int row, int file, boolean byWhite) {
        char pawn = byWhite ? 'P' : 'p';
        char knight = byWhite ? 'N' : 'n';
        char bishop = byWhite ? 'B' : 'b';
        char rook = byWhite ? 'R' : 'r';
        char queen = byWhite ? 'Q' : 'q';
        // White pawns attack towards rank 8, i.e. they stand one row below (higher index) their target.
        int pawnRow = byWhite ? row + 1 : row - 1;
        if (at(board, pawnRow, file - 1) == pawn || at(board, pawnRow, file + 1) == pawn) {
            return true;
        }
        int[][] knightJumps = {{1, 2}, {2, 1}, {-1, 2}, {-2, 1}, {1, -2}, {2, -1}, {-1, -2}, {-2, -1}};
        for (int[] jump : knightJumps) {
            if (at(board, row + jump[0], file + jump[1]) == knight) {
                return true;
            }
        }
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int d = 0; d < directions.length; d++) {
            char slider = d < 4 ? rook : bishop;
            int r = row + directions[d][0];
            int f = file + directions[d][1];
            while (at(board, r, f) == '.') {
                r += directions[d][0];
                f += directions[d][1];
            }
            char hit = at(board, r, f);
            if (hit == slider || hit == queen) {
                return true;
            }
        }
        return false;
    }

    private static char at(char[][] board, int row, int file) {
        return row < 0 || row > 7 || file < 0 || file > 7 ? '#' : board[row][file];
    }

    /**
     * Identity of a position for the repetition rule: placement, side to move, castling rights and
     * the en-passant square, but the latter only when a capture is actually available.
     */
    static String repetitionKey(String fen) {
        String[] fields = fen.split(" ");
        String enPassant = fields[3];
        if (!enPassant.equals("-")) {
            char[][] board = parseBoard(fields[0]);
            boolean whiteToMove = fields[1].equals("w");
            int file = enPassant.charAt(0) - 'a';
            int capturerRow = whiteToMove ? 3 : 4;
            char capturer = whiteToMove ? 'P' : 'p';
            if (at(board, capturerRow, file - 1) != capturer && at(board, capturerRow, file + 1) != capturer) {
                enPassant = "-";
            }
        }
        return fields[0] + ' ' + fields[1] + ' ' + fields[2] + ' ' + enPassant;
    }

    /** True for dead positions: bare kings, a lone minor piece, or only bishops on one square colour. */
    static boolean insufficientMaterial(String fen) {
        char[][] board = parseBoard(fen.split(" ")[0]);
        int minors = 0;
        int bishopsOnLight = 0;
        int bishopsOnDark = 0;
        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                char c = Character.toUpperCase(board[row][file]);
                if (c == '.' || c == 'K') {
                    continue;
                }
                if (c == 'B') {
                    minors++;
                    if ((row + file) % 2 == 0) {
                        bishopsOnLight++;
                    } else {
                        bishopsOnDark++;
                    }
                } else if (c == 'N') {
                    minors++;
                } else {
                    return false;
                }
            }
        }
        if (minors <= 1) {
            return true;
        }
        int bishops = bishopsOnLight + bishopsOnDark;
        return bishops == minors && (bishopsOnLight == 0 || bishopsOnDark == 0);
    }

    static int halfMoveClock(String fen) {
        return Integer.parseInt(fen.split(" ")[4]);
    }

    static boolean whiteToMove(String fen) {
        return fen.split(" ")[1].equals("w");
    }

    static int fullMoveNumber(String fen) {
        return Integer.parseInt(fen.split(" ")[5]);
    }

    private static ChessException invalid() {
        return new ChessException(ErrorCode.FEN_MALFORMED);
    }
}
