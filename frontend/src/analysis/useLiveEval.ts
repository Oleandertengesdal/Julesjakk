import { useEffect, useRef, useState } from 'react';
import { createBrowserEngine, type EngineDriver, type EngineResult } from './engine';

/** Evaluates one position at a time in the background, for the live eval bar and best-move arrow. */
export function useLiveEval(fen: string | null, enabled: boolean, depth = 12): EngineResult | null {
  const [result, setResult] = useState<EngineResult | null>(null);
  const engine = useRef<{ driver: EngineDriver; terminate: () => void } | null>(null);

  useEffect(() => {
    if (!enabled) return;
    engine.current = createBrowserEngine();
    return () => {
      engine.current?.terminate();
      engine.current = null;
    };
  }, [enabled]);

  useEffect(() => {
    if (!enabled || !fen || !engine.current) return;
    let current = true;
    engine.current.driver.evaluate(fen, depth).then((r) => current && setResult(r)).catch(() => {});
    return () => {
      current = false;
    };
  }, [fen, enabled, depth]);

  return enabled ? result : null;
}
