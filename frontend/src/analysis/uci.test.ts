import { describe, expect, it } from 'vitest';
import { parseInfo, parseBestMove } from './uci';

describe('parseInfo', () => {
  it('reads depth, centipawn score and principal variation', () => {
    const info = parseInfo('info depth 14 seldepth 20 multipv 1 score cp 34 nodes 12345 nps 900000 time 13 pv e2e4 e7e5 g1f3');
    expect(info).toEqual({ depth: 14, score: { cp: 34 }, pv: ['e2e4', 'e7e5', 'g1f3'] });
  });

  it('reads mate scores in both directions', () => {
    expect(parseInfo('info depth 9 score mate 3 pv d8h4')?.score).toEqual({ mate: 3 });
    expect(parseInfo('info depth 9 score mate -2 pv g2g4')?.score).toEqual({ mate: -2 });
  });

  it('ignores bound scores, which are not final for that depth', () => {
    expect(parseInfo('info depth 12 score cp 50 lowerbound pv e2e4')).toBeNull();
    expect(parseInfo('info depth 12 score cp 50 upperbound pv e2e4')).toBeNull();
  });

  it('ignores lines that are not scored search info', () => {
    for (const line of ['info string NNUE evaluation using nn.nnue', 'info depth 3 currmove e2e4 currmovenumber 1',
      'readyok', 'uciok', '', 'bestmove e2e4', 'info depth x score cp y']) {
      expect(parseInfo(line)).toBeNull();
    }
  });

  it('only keeps the first line of a multi-pv search', () => {
    expect(parseInfo('info depth 10 multipv 2 score cp 10 pv d2d4')).toBeNull();
  });
});

describe('parseBestMove', () => {
  it('reads the move and ignores the ponder move', () => {
    expect(parseBestMove('bestmove e7e8q ponder a2a1')).toBe('e7e8q');
  });

  it('treats "(none)" as no move: checkmate or stalemate', () => {
    expect(parseBestMove('bestmove (none)')).toBeNull();
  });

  it('rejects anything that is not a bestmove line', () => {
    expect(parseBestMove('info depth 1')).toBeUndefined();
    expect(parseBestMove('bestmove zz99')).toBeUndefined();
  });
});
