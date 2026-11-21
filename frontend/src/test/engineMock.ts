import { vi } from 'vitest';
import type { EngineResult } from '../analysis/engine';

/**
 * Stands in for analysis/engine in page tests: a fake Stockfish that answers instantly.
 * Use with vi.mock('../analysis/engine', () => import('../test/engineMock')).
 */
export const evaluate = vi.fn(async (): Promise<EngineResult> => ({ score: { cp: 35 }, best: 'e2e4', pv: ['e2e4'], depth: 14 }));
export const terminate = vi.fn();

export function createBrowserEngine() {
  return { driver: { evaluate }, terminate };
}

export class EngineDriver {}
