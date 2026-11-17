// @vitest-environment node
import { createRequire } from 'node:module';
import { afterAll, describe, expect, it } from 'vitest';
import { EngineDriver, type EngineTransport } from './engine';

/** The real Stockfish build, run in Node through the same text protocol a browser worker uses. */
function nodeTransport(): EngineTransport & { kill(): void } {
  const require = createRequire(import.meta.url);
  let listener: (line: string) => void = () => {};
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const engine = require('stockfish')('lite-single', (line: string) => listener(line));
  return {
    send: (command: string) => engine.processCommand(command),
    onLine: (handler) => { listener = handler; },
    kill: () => engine.terminate?.(),
  };
}

describe('EngineDriver against real Stockfish', () => {
  const transport = nodeTransport();
  const driver = new EngineDriver(transport);
  afterAll(() => transport.kill());

  it('finds mate in one and reports it as a mate score from White’s side', async () => {
    // Scholar's mate pattern: Qxf7#.
    const fen = 'r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4';
    const result = await driver.evaluate(fen, 8);
    expect(result.best).toBe('h5f7');
    expect(result.score).toEqual({ mate: 1 });
  });

  it('reports scores from White’s side when Black is to move', async () => {
    // Black has just lost the queen for nothing: White is clearly better.
    const fen = 'rnb1kbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3'.replace('rnb1kbnr', 'rnb1kbnr');
    const result = await driver.evaluate(fen, 8);
    expect('cp' in result.score ? result.score.cp : 0).toBeGreaterThan(300);
  });

  it('handles checkmated and stalemated positions without waiting forever', async () => {
    const mated = await driver.evaluate('rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3', 6);
    expect(mated.best).toBeNull();
    expect(mated.score).toEqual({ mate: -0 });
    const stalemate = await driver.evaluate('7k/5Q2/6K1/8/8/8/8/8 b - - 0 1', 6);
    expect(stalemate.best).toBeNull();
    expect(stalemate.score).toEqual({ cp: 0 });
  });

  it('refuses malformed positions instead of sending them to the engine', async () => {
    for (const fen of ['', 'not a fen', '8/8/8/8/8/8/8/8 w - - 0 1', 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 x;quit']) {
      await expect(driver.evaluate(fen, 4)).rejects.toThrow();
    }
    // The engine is still alive afterwards.
    const after = await driver.evaluate('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1', 4);
    expect(after.best).toMatch(/^[a-h][1-8][a-h][1-8]/);
  });

  it('serialises concurrent requests so answers never get mixed up', async () => {
    const mateIn1 = 'r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4';
    const start = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
    const [a, b, c] = await Promise.all([driver.evaluate(start, 5), driver.evaluate(mateIn1, 5), driver.evaluate(start, 5)]);
    expect(b.best).toBe('h5f7');
    expect(a.best).not.toBe('h5f7');
    expect(c.best).not.toBe('h5f7');
  });
});
