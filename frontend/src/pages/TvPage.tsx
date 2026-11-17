import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useLiveEval } from '../analysis/useLiveEval';
import { Board } from '../components/Board';
import { EvalBar } from '../components/EvalBar';
import { capturedPieces, START_FEN } from '../lib/chess';
import { CATEGORY_LABEL, resultMark, TERMINATION_LABEL } from '../lib/format';
import { useTopic } from '../lib/live';
import { useApi } from '../lib/useApi';
import type { TvState } from '../lib/types';

/**
 * The living-room screen: full spruce background, the board as large as the screen allows,
 * names and ratings readable from the sofa. Updates arrive over the socket; nothing to click.
 * Keys: E toggles Stockfish, F toggles full screen.
 */
export default function TvPage() {
  const tv = useApi<TvState>('/api/tv');
  useTopic<TvState>('/topic/tv', (state) => tv.setData(state));
  const [showEval, setShowEval] = useState(() => localStorageFlag('sjakkportal.tv.eval'));
  const game = tv.data?.game ?? null;
  const fen = game?.currentFen ?? START_FEN;
  const evaluation = useLiveEval(game ? fen : null, showEval, 14);
  const captured = capturedPieces(fen);
  const last = game?.moves.at(-1);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'e' || event.key === 'E') {
        setShowEval((value) => {
          try { localStorage.setItem('sjakkportal.tv.eval', String(!value)); } catch { /* private mode */ }
          return !value;
        });
      }
      if (event.key === 'f' || event.key === 'F') {
        if (document.fullscreenElement) void document.exitFullscreen();
        else void document.documentElement.requestFullscreen?.();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Keep the screen awake on tablets and TVs while this page is open.
  useEffect(() => {
    let lock: { release(): Promise<void> } | null = null;
    const nav = navigator as Navigator & { wakeLock?: { request(type: 'screen'): Promise<{ release(): Promise<void> }> } };
    nav.wakeLock?.request('screen').then((l) => { lock = l; }).catch(() => {});
    return () => { void lock?.release(); };
  }, []);

  const moveNumber = game ? Math.ceil(game.plyCount / 2) : 0;

  return (
    <div className="flex h-dvh flex-col overflow-hidden bg-spruce text-snow">
      <div className="flex min-h-0 flex-1 gap-[3vh] p-[3vh]">
        <div className="flex h-full min-h-0 items-stretch gap-[1.5vh]">
          {showEval && game && <EvalBar score={evaluation?.score ?? null} className="w-[2.2vh] min-w-4" />}
          <div className="aspect-square h-full">
            <Board fen={fen} lastMoveUci={last?.uci} id="tv-board" label="Partiet som vises" />
          </div>
        </div>

        <aside className="flex min-w-0 flex-1 flex-col justify-between">
          {game ? (
            <>
              <div>
                <p className="text-[2.2vh] font-semibold text-birch">
                  {tv.data?.reason === 'RECENT' ? 'Siste parti' : game.status === 'LIVE' ? '● Direkte' : 'Ferdig'}
                  {', '}{CATEGORY_LABEL[game.category]}{game.timeControl ? ` ${game.timeControl}` : ''}
                </p>
                <PlayerBlock name={game.black.displayName} rating={tv.data?.blackRating} pieces={captured.byBlack} advantage={-captured.balance} toMove={game.status === 'LIVE' && fen.split(' ')[1] === 'b'} />
                <p className="my-[2vh] text-[9vh] font-extrabold leading-none tabular text-birch">
                  {game.status === 'LIVE' ? `Trekk ${moveNumber || 1}` : resultMark(game.result)}
                </p>
                <PlayerBlock name={game.white.displayName} rating={tv.data?.whiteRating} pieces={captured.byWhite} advantage={captured.balance} toMove={game.status === 'LIVE' && fen.split(' ')[1] === 'w'} />
                {game.status !== 'LIVE' && game.termination && <p className="mt-[2vh] text-[2.4vh] text-birch">Avgjort ved {TERMINATION_LABEL[game.termination]}</p>}
              </div>
              {last && <p className="text-[3vh] text-birch">Siste trekk <span className="font-extrabold text-snow">{last.san}</span></p>}
            </>
          ) : (
            <div className="my-auto">
              <p className="text-[6vh] font-extrabold">Ingen partier ennå</p>
              <p className="mt-2 text-[2.5vh] text-birch">Når noen starter et parti, vises det her med en gang.</p>
            </div>
          )}
        </aside>
      </div>

      {tv.data?.tournament && tv.data.tournament.standings.length > 0 && (
        <footer className="flex items-center gap-[3vh] overflow-hidden whitespace-nowrap bg-spruce-2 px-[3vh] py-[1.4vh] text-[2.3vh]">
          <span className="font-extrabold text-birch">{tv.data.tournament.name}</span>
          {tv.data.tournament.standings.map((row) => (
            <span key={row.user.id} className="tabular">
              <span className="text-birch">{row.rank}.</span> {row.user.displayName} <span className="font-extrabold">{formatScore(row.score)}</span>
            </span>
          ))}
        </footer>
      )}
      <Link to="/" className="sr-only focus:not-sr-only focus:absolute focus:right-4 focus:top-4">Tilbake til Sjakkportal</Link>
    </div>
  );
}

function PlayerBlock({ name, rating, pieces, advantage, toMove }: { name: string; rating?: number | null; pieces: string[]; advantage: number; toMove: boolean }) {
  return (
    <div className={`border-l-[0.6vh] pl-[2vh] ${toMove ? 'border-berry' : 'border-transparent'}`}>
      <p className="truncate text-[6vh] font-extrabold leading-tight">{name}</p>
      <p className="text-[3vh] tabular text-birch">
        {rating ?? ''}
        <span className="ml-[2vh] text-[3.2vh] tracking-tight">{pieces.join('')}</span>
        {advantage > 0 && <span className="ml-[1vh] font-semibold">+{advantage}</span>}
      </p>
    </div>
  );
}

function formatScore(score: number): string {
  return Number.isInteger(score) ? String(score) : `${Math.floor(score)}½`.replace(/^0½$/, '½');
}

function localStorageFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true';
  } catch {
    return false;
  }
}
