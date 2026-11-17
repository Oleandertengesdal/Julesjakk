import { Chess, validateFen } from 'chess.js';
import { parseBestMove, parseInfo, toWhitePovScore, type Score } from './uci';

/** A line-based connection to a UCI engine: a browser Worker, or the Node build in tests. */
export interface EngineTransport {
  send(command: string): void;
  onLine(handler: (line: string) => void): void;
}

export interface EngineResult {
  /** White's point of view. */
  score: Score;
  best: string | null;
  pv: string[];
  depth: number;
}

interface Job {
  fen: string;
  depth: number;
  resolve: (result: EngineResult) => void;
  reject: (error: Error) => void;
}

/**
 * Drives one engine instance. Requests are queued and run one at a time, because a UCI engine
 * answers whatever position it was last given: interleaving two searches would mix up the answers.
 * Positions are validated before they reach the engine, which does not survive malformed input.
 */
export class EngineDriver {
  private readonly queue: Job[] = [];
  private current: Job | null = null;
  private lastInfo: { score: Score; pv: string[]; depth: number } | null = null;
  private readonly ready: Promise<void>;
  private markReady: () => void = () => {};

  constructor(private readonly transport: EngineTransport) {
    this.ready = new Promise((resolve) => {
      this.markReady = resolve;
    });
    transport.onLine((line) => this.handle(String(line)));
    transport.send('uci');
    transport.send('isready');
  }

  evaluate(fen: string, depth: number): Promise<EngineResult> {
    return new Promise((resolve, reject) => {
      this.queue.push({ fen, depth, resolve, reject });
      void this.ready.then(() => this.next());
    });
  }

  private next(): void {
    if (this.current) return;
    const job = this.queue.shift();
    if (!job) return;

    const check = validateFen(job.fen ?? '');
    if (!check.ok || /[\r\n;]/.test(job.fen)) {
      job.reject(new Error(`Ugyldig stilling: ${check.error ?? 'ulovlige tegn'}`));
      this.next();
      return;
    }
    // Finished positions are answered here: engines differ in how (and whether) they reply to them.
    const chess = new Chess(job.fen);
    if (chess.isCheckmate() || chess.isStalemate()) {
      const sideToMove = chess.turn();
      const score: Score = chess.isStalemate() ? { cp: 0 } : { mate: sideToMove === 'w' ? -0 : 0 };
      job.resolve({ score, best: null, pv: [], depth: 0 });
      this.next();
      return;
    }
    this.current = job;
    this.lastInfo = null;
    this.transport.send(`position fen ${job.fen}`);
    this.transport.send(`go depth ${Math.max(1, Math.min(30, Math.floor(job.depth)))}`);
  }

  private handle(line: string): void {
    if (line === 'readyok') {
      this.markReady();
      return;
    }
    if (!this.current) return;
    const info = parseInfo(line);
    if (info) {
      this.lastInfo = info;
      return;
    }
    const best = parseBestMove(line);
    if (best === undefined) return;
    const job = this.current;
    this.current = null;
    const sideToMove = job.fen.split(' ')[1] === 'b' ? 'b' : 'w';
    const info2 = this.lastInfo ?? { score: { cp: 0 }, pv: [], depth: 0 };
    job.resolve({ score: toWhitePovScore(info2.score, sideToMove), best, pv: info2.pv, depth: info2.depth });
    this.next();
  }
}

/** Starts the engine as a Web Worker. The single-threaded build needs no cross-origin isolation. */
export function createBrowserEngine(): { driver: EngineDriver; terminate: () => void } {
  const file = globalThis.crossOriginIsolated ? 'stockfish-19-lite.js' : 'stockfish-19-lite-single.js';
  const worker = new Worker(`/stockfish/${file}`);
  let handler: (line: string) => void = () => {};
  worker.onmessage = (event: MessageEvent) => handler(String(event.data));
  const driver = new EngineDriver({ send: (command) => worker.postMessage(command), onLine: (h) => { handler = h; } });
  return { driver, terminate: () => worker.terminate() };
}
