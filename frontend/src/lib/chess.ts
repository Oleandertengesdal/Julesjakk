const START_COUNTS: Record<string, number> = { p: 8, n: 2, b: 2, r: 2, q: 1 };
const VALUES: Record<string, number> = { p: 1, n: 3, b: 3, r: 5, q: 9 };
const GLYPHS: Record<string, string> = { p: '♟', n: '♞', b: '♝', r: '♜', q: '♛' };
const WHITE_GLYPHS: Record<string, string> = { p: '♙', n: '♘', b: '♗', r: '♖', q: '♕' };

export interface Captured {
  /** Black pieces White has taken, most valuable first (white pieces Black took are shown as white glyphs). */
  byWhite: string[];
  byBlack: string[];
  /** Material balance from White's point of view, in pawns. */
  balance: number;
}

/**
 * Captured pieces read from a FEN placement. Promotions can make a side have more of a piece than it
 * started with; such surpluses are counted as material, never as negative captures.
 */
export function capturedPieces(fen: string): Captured {
  const placement = fen.split(' ')[0] ?? '';
  const counts: Record<string, number> = {};
  for (const char of placement) {
    if (/[pnbrqPNBRQ]/.test(char)) counts[char] = (counts[char] ?? 0) + 1;
  }
  const byWhite: string[] = [];
  const byBlack: string[] = [];
  let white = 0;
  let black = 0;
  for (const piece of ['q', 'r', 'b', 'n', 'p']) {
    const blackLeft = counts[piece] ?? 0;
    const whiteLeft = counts[piece.toUpperCase()] ?? 0;
    white += whiteLeft * VALUES[piece];
    black += blackLeft * VALUES[piece];
    for (let i = blackLeft; i < START_COUNTS[piece]; i++) byWhite.push(GLYPHS[piece]);
    for (let i = whiteLeft; i < START_COUNTS[piece]; i++) byBlack.push(WHITE_GLYPHS[piece]);
  }
  return { byWhite, byBlack, balance: white - black };
}

/** The squares of the last move in UCI notation, for highlighting. */
export function lastMoveSquares(uci: string | undefined): [string, string] | null {
  if (!uci || !/^[a-h][1-8][a-h][1-8]/.test(uci)) return null;
  return [uci.slice(0, 2), uci.slice(2, 4)];
}

export const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
