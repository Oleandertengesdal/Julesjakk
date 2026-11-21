import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ErrorNotice, Loading } from '../components/ui';
import { api, ApiError } from '../lib/api';
import {
  checkFlag, formatClock, fromServer, newClock, pause, press, resume, snapshot, start, timeLeft, type ClockState, type Side, type Snapshot,
} from '../lib/clock';
import type { Game, PlayerRef } from '../lib/types';
import { useApi } from '../lib/useApi';

const RETRY_MS = 2000;

function controlOf(timeControl: string | undefined): [number, number] | null {
  const m = timeControl?.trim().match(/^(\d{1,3})(?:\+(\d{1,3}))?$/);
  if (!m || Number(m[1]) === 0) return null;
  return [Number(m[1]) * 60_000, Number(m[2] ?? 0) * 1000];
}

/**
 * A full-screen chess clock for the phone beside the board. The phone keeps time itself; every
 * press, pause and flag is sent to the server so the TV and the game page show the clocks too.
 */
export default function ClockPage() {
  const { id } = useParams();
  const { data: game, error, loading } = useApi<Game>(`/api/games/${id}`);

  if (error) return <Centered><ErrorNotice error={error} /></Centered>;
  if (loading || !game) return <Centered><Loading label="Henter partiet" /></Centered>;
  if (game.status !== 'LIVE') {
    return <Centered><p>Partiet er ikke i gang, så klokken kan ikke brukes.</p><BackLink id={game.id} /></Centered>;
  }
  const control = controlOf(game.timeControl);
  if (!control) {
    return <Centered><p>Partiet har ingen tidskontroll. Start et nytt parti med for eksempel 5+3 for å bruke sjakkur.</p><BackLink id={game.id} /></Centered>;
  }
  return <Clock game={game} control={control} />;
}

function Clock({ game, control }: { game: Game; control: [number, number] }) {
  const navigate = useNavigate();
  const [state, setState] = useState<ClockState>(() =>
    game.clock ? fromServer(game.clock, performance.now()) : newClock(control[0], control[1]));
  const [flipped, setFlipped] = useState(false);
  const [, setTick] = useState(0);
  const [finishing, setFinishing] = useState(false);
  const [finishError, setFinishError] = useState<ApiError | null>(null);
  const sync = useClockSync(game.id);
  const now = performance.now();

  const change = useCallback((next: (s: ClockState, t: number) => ClockState) => {
    setState((current) => {
      const t = performance.now();
      const updated = next(current, t);
      if (updated !== current) {
        sync.send(snapshot(updated, t));
        if (updated.flagged && !current.flagged) navigator.vibrate?.([200, 100, 200]);
      }
      return updated;
    });
  }, [sync]);

  // Repaint ten times a second and flag a player whose time runs out.
  useEffect(() => {
    const timer = setInterval(() => {
      setTick((n) => n + 1);
      change(checkFlag);
    }, 100);
    return () => clearInterval(timer);
  }, [change]);

  useWakeLock();

  function tap(side: Side) {
    navigator.vibrate?.(25);
    change((s, t) => press(s, side, t));
  }

  async function registerTimeout(loser: Side) {
    setFinishing(true);
    setFinishError(null);
    try {
      await api.post(`/api/games/${game.id}/finish`, { result: loser === 'WHITE' ? 'BLACK_WIN' : 'WHITE_WIN', termination: 'TIMEOUT' });
      navigate(`/matches/${game.id}`);
    } catch (e) {
      setFinishError(e as ApiError);
      setFinishing(false);
    }
  }

  const halves: Side[] = flipped ? ['WHITE', 'BLACK'] : ['BLACK', 'WHITE'];
  const playerOf = (side: Side): PlayerRef => (side === 'WHITE' ? game.white : game.black);

  return (
    <main className="grid h-dvh select-none grid-rows-[1fr_auto_1fr] bg-ink text-snow">
      {halves.map((side, index) => {
        const left = timeLeft(state, side, now);
        const running = state.running === side;
        const flagged = state.flagged === side;
        const name = playerOf(side).displayName;
        const half = (
          <button key={side} type="button" onClick={() => tap(side)} disabled={!!state.flagged}
            aria-label={`${name}, ${formatClock(left)}${running ? ', klokken går' : ''}`}
            className={`flex flex-col items-center justify-center gap-2 transition-colors ${index === 0 ? 'rotate-180' : ''} ${
              flagged ? 'bg-berry text-snow' : running ? 'bg-birch text-ink' : 'bg-spruce text-snow/80'}`}>
            <span className="text-lg font-semibold">{name} <span className="font-normal">({side === 'WHITE' ? 'hvit' : 'svart'})</span></span>
            <span className={`tabular font-extrabold leading-none ${left < 20_000 && running ? 'text-berry' : ''}`}
              style={{ fontSize: 'clamp(4rem, 22vw, 11rem)' }}>{formatClock(left)}</span>
            {flagged && <span className="text-xl font-extrabold">Tiden er ute</span>}
          </button>
        );
        return index === 0
          ? [half, <Controls key="controls" state={state} onStart={() => change(start)} onPause={() => change(pause)}
              onResume={() => change(resume)} onFlip={() => setFlipped((f) => !f)} gameId={game.id} unsynced={sync.unsynced} />]
          : half;
      })}
      {state.flagged && (
        <div role="alertdialog" aria-labelledby="flag-title" className="fixed inset-x-4 top-1/2 -translate-y-1/2 rounded-lg bg-snow p-5 text-ink shadow-xl">
          <h2 id="flag-title" className="text-xl font-extrabold">Tiden er ute for {playerOf(state.flagged).displayName}</h2>
          <p className="mt-1 text-slate">Registrer resultatet, eller lukk klokken hvis dere avtaler noe annet.</p>
          <div className="mt-4 flex flex-wrap gap-3">
            <button type="button" disabled={finishing} onClick={() => state.flagged && void registerTimeout(state.flagged)}
              className="rounded-md bg-berry px-4 py-2.5 font-semibold text-snow">
              Registrer: {playerOf(state.flagged === 'WHITE' ? 'BLACK' : 'WHITE').displayName} vant på tid
            </button>
            <Link to={`/matches/${game.id}`} className="rounded-md border border-mist px-4 py-2.5 font-semibold">Lukk klokken</Link>
          </div>
          <div className="mt-3"><ErrorNotice error={finishError} /></div>
        </div>
      )}
    </main>
  );
}

function Controls({ state, onStart, onPause, onResume, onFlip, gameId, unsynced }: {
  state: ClockState; onStart: () => void; onPause: () => void; onResume: () => void; onFlip: () => void; gameId: number; unsynced: boolean;
}) {
  const button = 'rounded-md border border-snow/30 px-4 py-2 text-sm font-semibold';
  return (
    <div className="flex flex-wrap items-center justify-center gap-3 bg-ink px-3 py-3">
      {!state.started && <button type="button" className={`${button} bg-berry`} onClick={onStart}>Start klokken</button>}
      {state.running && <button type="button" className={button} onClick={onPause}>Pause</button>}
      {state.started && !state.running && !state.flagged && <button type="button" className={`${button} bg-berry`} onClick={onResume}>Fortsett</button>}
      <button type="button" className={button} onClick={onFlip}>Bytt side</button>
      <Link to={`/matches/${gameId}`} className={button}>Lukk</Link>
      <p className="w-full text-center text-xs text-snow/70" role="status">
        {!state.started ? 'Svart trykker for å starte hvits klokke.' : unsynced ? 'Ikke lagret ennå – prøver igjen.' : 'Vises på TV-en.'}
      </p>
    </div>
  );
}

/**
 * Sends the newest clock state to the server. Only the latest snapshot matters: a failed send is
 * retried with whatever is newest then, and a "stale" answer (another device was newer) is dropped.
 */
function useClockSync(gameId: number) {
  const latest = useRef<Snapshot | null>(null);
  const inFlight = useRef(false);
  const retry = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [unsynced, setUnsynced] = useState(false);

  const flush = useCallback(async () => {
    if (inFlight.current || !latest.current) return;
    const sending = latest.current;
    inFlight.current = true;
    try {
      await api.put(`/api/games/${gameId}/clock`, sending);
      if (latest.current === sending) latest.current = null;
      setUnsynced(false);
    } catch (e) {
      const stale = e instanceof ApiError && e.code === 'CHESS-GAME-005';
      const over = e instanceof ApiError && (e.code === 'CHESS-GAME-003' || e.code === 'CHESS-GAME-007');
      if (stale || over) {
        if (latest.current === sending) latest.current = null;
      } else {
        setUnsynced(true);
        retry.current = setTimeout(() => void flush(), RETRY_MS);
      }
    } finally {
      inFlight.current = false;
    }
    if (latest.current && latest.current !== sending && !retry.current) void flush();
  }, [gameId]);

  const send = useCallback((s: Snapshot) => {
    latest.current = s;
    if (retry.current) {
      clearTimeout(retry.current);
      retry.current = null;
    }
    void flush();
  }, [flush]);

  useEffect(() => () => { if (retry.current) clearTimeout(retry.current); }, []);
  return { send, unsynced };
}

/** Keeps the phone's screen on while the clock is open, where the browser allows it. */
function useWakeLock() {
  useEffect(() => {
    let lock: { release: () => Promise<void> } | null = null;
    const nav = navigator as Navigator & { wakeLock?: { request: (type: 'screen') => Promise<{ release: () => Promise<void> }> } };
    nav.wakeLock?.request('screen').then((l) => { lock = l; }).catch(() => { /* not allowed: fine */ });
    return () => { void lock?.release(); };
  }, []);
}

function Centered({ children }: { children: React.ReactNode }) {
  return <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-4 p-6 text-center">{children}</main>;
}

function BackLink({ id }: { id: number }) {
  return <Link to={`/matches/${id}`} className="font-semibold text-spruce underline">Til partiet</Link>;
}
