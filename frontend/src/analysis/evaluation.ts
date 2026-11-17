import { Chess, type Square } from 'chess.js';
import type { Score } from './uci';

/**
 * Game-analysis maths. All scores here are from White's point of view.
 * The formulas follow Lichess's published ones (win% from centipawns, accuracy from win% lost),
 * so the numbers feel familiar to anyone who uses Lichess.
 */

export type Classification = 'BRILLIANT' | 'BEST' | 'EXCELLENT' | 'GOOD' | 'INACCURACY' | 'MISTAKE' | 'BLUNDER';

export interface PositionEval {
  score: Score;
  /** Engine's best move in this position, or null when the game is over. */
  best: string | null;
}

export interface MoveInput {
  uci: string;
  fenBefore: string;
}

export interface MoveAnalysis {
  ply: number;
  cp: number | null;
  mate: number | null;
  best: string | null;
  classification: Classification;
}

export interface AnalysisSummary {
  depth: number;
  whiteAccuracy: number;
  blackAccuracy: number;
  whiteAcpl: number;
  blackAcpl: number;
  moves: MoveAnalysis[];
}

const MAX_CP_LOSS = 1000;
const PIECE_VALUE: Record<string, number> = { p: 1, n: 3, b: 3, r: 5, q: 9, k: 0 };

/** Chance of winning for White, 0–100. A forced mate counts as decided. */
export function winPercent(score: Score): number {
  if ('mate' in score) {
    return score.mate > 0 || Object.is(score.mate, 0) ? 100 : 0;
  }
  return 50 + 50 * (2 / (1 + Math.exp(-0.00368208 * score.cp)) - 1);
}

/** Engines report from the side to move; we always store White's view. */
export function toWhitePov(score: Score, sideToMove: 'w' | 'b'): Score {
  if (sideToMove === 'w') return score;
  return 'mate' in score ? { mate: -score.mate } : { cp: -score.cp };
}

/** Lichess move accuracy: ~100 for a perfect move, falling quickly with the winning chance thrown away. */
export function accuracyFromWinDrop(drop: number): number {
  if (drop <= 0) return 100;
  const raw = 103.1668 * Math.exp(-0.04354 * Math.max(0, drop)) - 3.1669;
  return Math.min(100, Math.max(0, raw));
}

/** Centipawns for averaging; mates become a large finite value. */
export function centipawns(score: Score): number {
  if ('mate' in score) return score.mate > 0 || Object.is(score.mate, 0) ? MAX_CP_LOSS : -MAX_CP_LOSS;
  return Math.max(-MAX_CP_LOSS, Math.min(MAX_CP_LOSS, score.cp));
}

interface ClassifyInput {
  fenBefore: string;
  uci: string;
  bestUci: string | null;
  before: Score;
  after: Score;
}

/**
 * Grades a move by the winning chance the mover gave away. "Brilliant" is a heuristic: the engine's
 * own choice, putting a non-pawn piece where the opponent can take it for less, without spoiling the position.
 */
export function classifyMove({ fenBefore, uci, bestUci, before, after }: ClassifyInput): Classification {
  const mover = fenBefore.split(' ')[1] === 'b' ? 'b' : 'w';
  const winBefore = mover === 'w' ? winPercent(before) : 100 - winPercent(before);
  const winAfter = mover === 'w' ? winPercent(after) : 100 - winPercent(after);
  const drop = Math.max(0, winBefore - winAfter);

  if (bestUci !== null && uci === bestUci) {
    return isSacrifice(fenBefore, uci) && winAfter >= 50 ? 'BRILLIANT' : 'BEST';
  }
  if (drop >= 20) return 'BLUNDER';
  if (drop >= 10) return 'MISTAKE';
  if (drop >= 5) return 'INACCURACY';
  if (drop >= 2) return 'GOOD';
  return 'EXCELLENT';
}

function isSacrifice(fenBefore: string, uci: string): boolean {
  try {
    const chess = new Chess(fenBefore);
    const from = uci.slice(0, 2) as Square;
    const to = uci.slice(2, 4) as Square;
    const piece = chess.get(from);
    if (!piece || piece.type === 'p' || piece.type === 'k') return false;
    const captured = chess.get(to);
    const move = chess.move({ from, to, promotion: uci.length > 4 ? uci[4] : undefined });
    if (!move) return false;
    const opponent = piece.color === 'w' ? 'b' : 'w';
    const capturedValue = captured ? PIECE_VALUE[captured.type] : 0;
    return chess.isAttacked(to, opponent) && PIECE_VALUE[piece.type] > capturedValue + 1;
  } catch {
    return false;
  }
}

/**
 * Turns per-position engine evaluations into the stored summary.
 *
 * @param evals one evaluation per position: the start, then after every move (moves.length + 1 entries)
 */
export function summarize(evals: PositionEval[], moves: MoveInput[], depth: number): AnalysisSummary {
  if (evals.length !== moves.length + 1) {
    throw new Error(`Expected ${moves.length + 1} evaluations, got ${evals.length}`);
  }
  const accuracy: Record<'w' | 'b', number[]> = { w: [], b: [] };
  const loss: Record<'w' | 'b', number[]> = { w: [], b: [] };
  const analysed: MoveAnalysis[] = moves.map((move, index) => {
    const before = evals[index];
    const after = evals[index + 1];
    const mover = move.fenBefore.split(' ')[1] === 'b' ? 'b' : 'w';
    const sign = mover === 'w' ? 1 : -1;
    const winBefore = mover === 'w' ? winPercent(before.score) : 100 - winPercent(before.score);
    const winAfter = mover === 'w' ? winPercent(after.score) : 100 - winPercent(after.score);
    accuracy[mover].push(accuracyFromWinDrop(winBefore - winAfter));
    loss[mover].push(Math.min(MAX_CP_LOSS, Math.max(0, sign * (centipawns(before.score) - centipawns(after.score)))));
    return {
      ply: index + 1,
      cp: 'cp' in after.score ? after.score.cp : null,
      mate: 'mate' in after.score ? after.score.mate : null,
      best: before.best,
      classification: classifyMove({ fenBefore: move.fenBefore, uci: move.uci, bestUci: before.best, before: before.score, after: after.score }),
    };
  });
  return {
    depth,
    whiteAccuracy: round(mean(accuracy.w, 100)),
    blackAccuracy: round(mean(accuracy.b, 100)),
    whiteAcpl: Math.round(mean(loss.w, 0)),
    blackAcpl: Math.round(mean(loss.b, 0)),
    moves: analysed,
  };
}

function mean(values: number[], empty: number): number {
  return values.length === 0 ? empty : values.reduce((a, b) => a + b, 0) / values.length;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
