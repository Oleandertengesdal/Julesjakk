/** Parsing of the UCI text protocol that Stockfish speaks. */

/** An evaluation. {@code cp} is centipawns; {@code mate} is moves to mate (negative: being mated). */
export type Score = { cp: number } | { mate: number };

export interface SearchInfo {
  depth: number;
  score: Score;
  pv: string[];
}

const UCI_MOVE = /^[a-h][1-8][a-h][1-8][qrbn]?$/;

/**
 * Reads an {@code info} line. Returns null for lines without a final score: strings, currmove updates,
 * bound scores (which are provisional for their depth) and secondary multi-pv lines.
 */
export function parseInfo(line: string): SearchInfo | null {
  const tokens = line.trim().split(/\s+/);
  if (tokens[0] !== 'info') return null;
  let depth: number | null = null;
  let score: Score | null = null;
  let pv: string[] = [];
  for (let i = 1; i < tokens.length; i++) {
    switch (tokens[i]) {
      case 'depth':
        depth = toInt(tokens[++i]);
        break;
      case 'multipv':
        if (toInt(tokens[++i]) !== 1) return null;
        break;
      case 'score': {
        const kind = tokens[++i];
        const value = toInt(tokens[++i]);
        if (value === null || (kind !== 'cp' && kind !== 'mate')) return null;
        score = kind === 'cp' ? { cp: value } : { mate: value };
        if (tokens[i + 1] === 'lowerbound' || tokens[i + 1] === 'upperbound') return null;
        break;
      }
      case 'pv':
        pv = tokens.slice(i + 1).filter((move) => UCI_MOVE.test(move));
        i = tokens.length;
        break;
      default:
        break;
    }
  }
  return depth !== null && score !== null ? { depth, score, pv } : null;
}

/** @returns the move, null for "(none)" (no legal moves), or undefined if the line is not a bestmove line */
export function parseBestMove(line: string): string | null | undefined {
  const match = /^bestmove\s+(\S+)/.exec(line.trim());
  if (!match) return undefined;
  if (match[1] === '(none)') return null;
  return UCI_MOVE.test(match[1]) ? match[1] : undefined;
}

function toInt(token: string | undefined): number | null {
  if (token === undefined || !/^-?\d+$/.test(token)) return null;
  return Number.parseInt(token, 10);
}

/** Engines report from the side to move; we always store White's view. */
export function toWhitePovScore(score: Score, sideToMove: 'w' | 'b'): Score {
  if (sideToMove === 'w') return score;
  return 'mate' in score ? { mate: -score.mate } : { cp: -score.cp };
}
