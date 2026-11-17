import { describe, expect, it } from 'vitest';
import {
  accuracyFromWinDrop,
  classifyMove,
  summarize,
  toWhitePov,
  winPercent,
  type PositionEval,
} from './evaluation';

const START = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

describe('winPercent', () => {
  it('is 50 for an equal position and symmetric around it', () => {
    expect(winPercent({ cp: 0 })).toBe(50);
    expect(winPercent({ cp: 300 }) + winPercent({ cp: -300 })).toBeCloseTo(100, 10);
  });

  it('grows with the advantage but never reaches 0 or 100 for centipawns', () => {
    expect(winPercent({ cp: 100 })).toBeGreaterThan(55);
    expect(winPercent({ cp: 5000 })).toBeLessThan(100);
    expect(winPercent({ cp: -5000 })).toBeGreaterThan(0);
  });

  it('treats a forced mate as decided', () => {
    expect(winPercent({ mate: 4 })).toBe(100);
    expect(winPercent({ mate: -1 })).toBe(0);
  });
});

describe('toWhitePov', () => {
  it('flips scores reported from Black to move', () => {
    expect(toWhitePov({ cp: 40 }, 'b')).toEqual({ cp: -40 });
    expect(toWhitePov({ mate: 2 }, 'b')).toEqual({ mate: -2 });
    expect(toWhitePov({ cp: 40 }, 'w')).toEqual({ cp: 40 });
  });
});

describe('accuracyFromWinDrop', () => {
  it('is about 100 for a move that loses nothing and falls with the size of the mistake', () => {
    expect(accuracyFromWinDrop(0)).toBeGreaterThan(99.9);
    expect(accuracyFromWinDrop(10)).toBeLessThan(accuracyFromWinDrop(5));
    expect(accuracyFromWinDrop(60)).toBeLessThan(10);
  });

  it('stays within 0 and 100, even for a move that gained (engine noise)', () => {
    expect(accuracyFromWinDrop(-15)).toBe(100);
    expect(accuracyFromWinDrop(100)).toBeGreaterThanOrEqual(0);
  });
});

describe('classifyMove', () => {
  const equal = { cp: 0 };

  it('marks the engine move as best', () => {
    expect(classifyMove({ fenBefore: START, uci: 'e2e4', bestUci: 'e2e4', before: equal, after: { cp: 30 } })).toBe('BEST');
  });

  it('grades by how much winning chance the mover gave away', () => {
    const grade = (afterCp: number) => classifyMove({ fenBefore: START, uci: 'a2a3', bestUci: 'e2e4', before: equal, after: { cp: afterCp } });
    expect(grade(-10)).toBe('EXCELLENT');
    expect(grade(-40)).toBe('GOOD');
    expect(grade(-80)).toBe('INACCURACY');
    expect(grade(-170)).toBe('MISTAKE');
    expect(grade(-500)).toBe('BLUNDER');
  });

  it('judges Black moves from Black’s side of the board', () => {
    const blackToMove = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';
    // White-POV eval rising from 0 to +500 after a Black move is a Black blunder.
    expect(classifyMove({ fenBefore: blackToMove, uci: 'f7f6', bestUci: 'e7e5', before: equal, after: { cp: 500 } })).toBe('BLUNDER');
  });

  it('does not call a move a blunder when the game was already lost either way', () => {
    expect(classifyMove({ fenBefore: START, uci: 'a2a3', bestUci: 'e2e4', before: { mate: -3 }, after: { mate: -2 } })).not.toBe('BLUNDER');
  });

  it('calls a best-move sacrifice that keeps the position sound brilliant', () => {
    // Rook to h7 into an attack by the king: a sacrifice, and the engine's choice, keeping a winning position.
    const fen = '6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1';
    expect(classifyMove({ fenBefore: fen, uci: 'a1a8', bestUci: 'a1a8', before: { cp: 600 }, after: { mate: 1 } })).toBe('BEST');
    const sac = 'r5k1/5ppp/8/8/8/8/5PPP/3Q2K1 w - - 0 1';
    expect(classifyMove({ fenBefore: sac, uci: 'd1d8', bestUci: 'd1d8', before: { cp: 150 }, after: { cp: 160 } })).toBe('BRILLIANT');
  });
});

describe('summarize', () => {
  const evals: PositionEval[] = [
    { score: { cp: 20 }, best: 'e2e4' },
    { score: { cp: 25 }, best: 'e7e5' },
    { score: { cp: 30 }, best: 'g1f3' },
    { score: { cp: 600 }, best: 'd1h5' },
  ];
  const moves = [
    { uci: 'e2e4', fenBefore: START },
    { uci: 'e7e5', fenBefore: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1' },
    { uci: 'f7f6', fenBefore: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2'.replace(' w ', ' b ') },
  ];

  it('produces one entry per move with classification and the engine move', () => {
    const summary = summarize(evals, moves, 16);
    expect(summary.moves).toHaveLength(3);
    expect(summary.moves[0]).toMatchObject({ ply: 1, best: 'e2e4', classification: 'BEST' });
    expect(summary.moves[2].classification).toBe('BLUNDER');
  });

  it('computes accuracy and average centipawn loss per side', () => {
    const summary = summarize(evals, moves, 16);
    expect(summary.whiteAccuracy).toBeGreaterThan(95);
    expect(summary.blackAccuracy).toBeLessThan(summary.whiteAccuracy);
    expect(summary.blackAcpl).toBeGreaterThan(summary.whiteAcpl);
    expect(summary.whiteAccuracy).toBeLessThanOrEqual(100);
    expect(summary.blackAccuracy).toBeGreaterThanOrEqual(0);
    expect(summary.depth).toBe(16);
  });

  it('refuses mismatched input instead of producing a wrong report', () => {
    expect(() => summarize(evals.slice(0, 2), moves, 16)).toThrow();
  });

  it('caps the loss counted for a single move so one disaster does not swamp the average', () => {
    const blunderEvals: PositionEval[] = [{ score: { cp: 0 }, best: 'e2e4' }, { score: { mate: 1 }, best: 'd8h4' }];
    const summary = summarize(blunderEvals, [{ uci: 'f2f3', fenBefore: START }], 12);
    expect(summary.whiteAcpl).toBeLessThanOrEqual(1000);
  });
});
