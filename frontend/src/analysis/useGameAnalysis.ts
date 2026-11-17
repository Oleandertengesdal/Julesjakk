import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../lib/api';
import { START_FEN } from '../lib/chess';
import type { Game, SavedAnalysis } from '../lib/types';
import { createBrowserEngine } from './engine';
import { summarize, type AnalysisSummary, type PositionEval } from './evaluation';

export const ANALYSIS_DEPTH = 14;

/** A saved analysis, or one computed in this browser and (when allowed) saved for everyone. */
export interface GameAnalysis {
  whiteAccuracy: number;
  blackAccuracy: number;
  whiteAcpl: number;
  blackAcpl: number;
  depth: number;
  moves: { ply: number; cp: number | null; mate: number | null; best: string | null; classification: string }[];
}

export function useGameAnalysis(game: Game | null, canSave: boolean) {
  const [analysis, setAnalysis] = useState<GameAnalysis | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const cancelled = useRef(false);

  useEffect(() => {
    cancelled.current = false;
    setAnalysis(null);
    if (!game || game.status !== 'FINISHED' || game.plyCount === 0) return;
    api.get<SavedAnalysis>(`/api/games/${game.id}/analysis`).then(setAnalysis).catch(() => setAnalysis(null));
    return () => {
      cancelled.current = true;
    };
  }, [game?.id, game?.status, game?.plyCount]); // eslint-disable-line react-hooks/exhaustive-deps

  const run = useCallback(async () => {
    if (!game) return;
    setError(null);
    setProgress(0);
    const { driver, terminate } = createBrowserEngine();
    try {
      const fens = [START_FEN, ...game.moves.map((m) => m.fen)];
      const evals: PositionEval[] = [];
      for (let i = 0; i < fens.length; i++) {
        if (cancelled.current) return;
        const result = await driver.evaluate(fens[i], ANALYSIS_DEPTH);
        evals.push({ score: result.score, best: result.best });
        setProgress((i + 1) / fens.length);
      }
      const summary: AnalysisSummary = summarize(evals, game.moves.map((m, i) => ({ uci: m.uci, fenBefore: fens[i] })), ANALYSIS_DEPTH);
      setAnalysis(summary);
      if (canSave) {
        await api.put(`/api/games/${game.id}/analysis`, { engine: 'Stockfish 19 (lite)', ...summary });
      }
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'CHESS-CLIENT-002', 'Analysen stoppet uventet. Prøv igjen.', null));
    } finally {
      terminate();
      setProgress(null);
    }
  }, [game, canSave]);

  return { analysis, progress, error, run };
}
