import { describe, expect, it } from 'vitest';
import { capturedPieces, lastMoveSquares, START_FEN } from './chess';
import { categoryForTimeControl, percentages, resultMark, signed } from './format';

describe('capturedPieces', () => {
  it('finds nothing captured at the start', () => {
    expect(capturedPieces(START_FEN)).toEqual({ byWhite: [], byBlack: [], balance: 0 });
  });

  it('lists captures most valuable first and computes the balance', () => {
    // White has taken a black queen and a pawn; Black has taken a white knight.
    const fen = 'rnb1kbnr/ppp1pppp/8/8/8/8/PPPPPPPP/R1BQKBNR w KQkq - 0 1';
    const captured = capturedPieces(fen);
    expect(captured.byWhite).toEqual(['♛', '♟']);
    expect(captured.byBlack).toEqual(['♘']);
    expect(captured.balance).toBe(10 - 3);
  });

  it('never reports negative captures after a promotion', () => {
    const twoQueens = 'QQ2k3/8/8/8/8/8/8/4K3 w - - 0 1';
    expect(capturedPieces(twoQueens).byBlack).not.toContain('♕');
  });
});

describe('format helpers', () => {
  it('writes results and rating changes with typographic signs', () => {
    expect(resultMark('WHITE_WIN')).toBe('1–0');
    expect(resultMark('DRAW')).toBe('½–½');
    expect(signed(12)).toBe('+12');
    expect(signed(-8)).toBe('−8');
    expect(signed(0)).toBe('±0');
  });

  it('classifies time controls exactly like the server', () => {
    expect(categoryForTimeControl('3+2')).toBe('BLITZ');
    expect(categoryForTimeControl('5+5')).toBe('RAPID');
    expect(categoryForTimeControl('15 + 10')).toBe('RAPID');
    expect(categoryForTimeControl('60')).toBe('CLASSICAL');
    expect(categoryForTimeControl('')).toBe('CLASSICAL');
    expect(categoryForTimeControl('fem minutter')).toBeNull();
    expect(categoryForTimeControl('0+0')).toBeNull();
  });

  it('rounds percentages so they always total 100', () => {
    for (const values of [[1 / 3, 1 / 3, 1 / 3], [0.455, 0.09, 0.455], [0.999, 0.0005, 0.0005]]) {
      expect(percentages(values).reduce((a, b) => a + b, 0)).toBe(100);
    }
  });

  it('reads last-move squares only from valid moves', () => {
    expect(lastMoveSquares('e7e8q')).toEqual(['e7', 'e8']);
    expect(lastMoveSquares('xx')).toBeNull();
  });
});
