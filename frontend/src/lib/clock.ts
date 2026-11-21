/**
 * The chess clock run by a phone at the board. Pure functions over an immutable state; `now` is a
 * monotonic millisecond reading (performance.now()) supplied by the caller, which keeps the logic
 * testable and immune to the device's wall clock being changed mid-game.
 *
 * Rules: Black starts White's clock (or the start button does). Pressing ends your own turn: the
 * time you used is taken off, the increment is added (Fischer), and the opponent's clock starts.
 * A clock that reaches zero flags that player and stops.
 */
export type Side = 'WHITE' | 'BLACK';

export interface ClockState {
  initialMs: number;
  incrementMs: number;
  /** Time left at `since` for the running side; exact for a side that is not running. */
  whiteMs: number;
  blackMs: number;
  running: Side | null;
  since: number;
  /** Whose clock was running when the clock was paused. */
  paused: Side | null;
  flagged: Side | null;
  started: boolean;
  /** Increases with every change, so the server can keep the newest state. */
  sequence: number;
}

/** The clock as the server stores and broadcasts it (GameView.clock). */
export interface ServerClock {
  initialMs: number;
  incrementMs: number;
  whiteMs: number;
  blackMs: number;
  running: Side | null;
  sequence: number;
  updatedAt: string;
  serverNow: string;
}

export interface Snapshot { whiteMs: number; blackMs: number; running: Side | null; sequence: number }

const other = (side: Side): Side => (side === 'WHITE' ? 'BLACK' : 'WHITE');
const key = (side: Side): 'whiteMs' | 'blackMs' => (side === 'WHITE' ? 'whiteMs' : 'blackMs');

export function newClock(initialMs: number, incrementMs: number): ClockState {
  return { initialMs, incrementMs, whiteMs: initialMs, blackMs: initialMs, running: null, since: 0, paused: null, flagged: null,
    started: false, sequence: 0 };
}

export function timeLeft(state: ClockState, side: Side, now: number): number {
  const stored = state[key(side)];
  return state.running === side ? Math.max(0, stored - (now - state.since)) : stored;
}

/** Commits the running side's used time, so the state is exact at `now`. */
function settle(state: ClockState, now: number): ClockState {
  if (!state.running) return state;
  return { ...state, [key(state.running)]: timeLeft(state, state.running, now), since: now };
}

export function start(state: ClockState, now: number): ClockState {
  if (state.started || state.flagged) return state;
  return { ...state, running: 'WHITE', since: now, started: true, sequence: state.sequence + 1 };
}

/** The player on `side` presses their button, ending their turn. */
export function press(state: ClockState, side: Side, now: number): ClockState {
  if (state.flagged) return state;
  if (!state.started) return side === 'BLACK' ? start(state, now) : state;
  if (state.running !== side) return state;
  const settled = settle(state, now);
  if (settled[key(side)] <= 0) return flag(settled, side);
  return { ...settled, [key(side)]: settled[key(side)] + state.incrementMs, running: other(side), since: now,
    sequence: state.sequence + 1 };
}

export function pause(state: ClockState, now: number): ClockState {
  if (!state.running) return state;
  return { ...settle(state, now), running: null, paused: state.running, sequence: state.sequence + 1 };
}

export function resume(state: ClockState, now: number): ClockState {
  if (state.running || !state.paused || state.flagged) return state;
  return { ...state, running: state.paused, paused: null, since: now, sequence: state.sequence + 1 };
}

/** Flags the running player if their time is up; returns the same state otherwise. */
export function checkFlag(state: ClockState, now: number): ClockState {
  if (!state.running || timeLeft(state, state.running, now) > 0) return state;
  return flag(settle(state, now), state.running);
}

function flag(state: ClockState, side: Side): ClockState {
  return { ...state, [key(side)]: 0, running: null, paused: null, flagged: side, sequence: state.sequence + 1 };
}

export function snapshot(state: ClockState, now: number): Snapshot {
  return { whiteMs: Math.round(timeLeft(state, 'WHITE', now)), blackMs: Math.round(timeLeft(state, 'BLACK', now)),
    running: state.running, sequence: state.sequence };
}

/** Picks up a clock the server has (the phone was reloaded), counting the time since the last press. */
export function fromServer(clock: ServerClock, now: number): ClockState {
  const elapsed = Math.max(0, Date.parse(clock.serverNow) - Date.parse(clock.updatedAt));
  const state: ClockState = { ...newClock(clock.initialMs, clock.incrementMs), whiteMs: clock.whiteMs, blackMs: clock.blackMs,
    running: clock.running, since: now, started: true, sequence: clock.sequence };
  if (clock.running) state[key(clock.running)] = Math.max(0, clock[key(clock.running)] - elapsed);
  if (clock.whiteMs === 0) state.flagged = 'WHITE';
  if (clock.blackMs === 0) state.flagged = 'BLACK';
  return state;
}

/**
 * What a viewer (TV, game page) shows. Uses only server times plus how long ago the view arrived,
 * so a TV whose own clock is wrong still shows the right time.
 */
export function remainingFromView(clock: ServerClock, receivedAt: number, now: number): { white: number; black: number } {
  const elapsed = clock.running ? Date.parse(clock.serverNow) - Date.parse(clock.updatedAt) + (now - receivedAt) : 0;
  return {
    white: Math.max(0, clock.whiteMs - (clock.running === 'WHITE' ? elapsed : 0)),
    black: Math.max(0, clock.blackMs - (clock.running === 'BLACK' ? elapsed : 0)),
  };
}

/** 1:02:05, 5:00, 0:20, and tenths of a second below twenty seconds: 19.9 */
export function formatClock(ms: number): string {
  const safe = Math.max(0, ms);
  if (safe < 20_000) return (Math.floor(safe / 100) / 10).toFixed(1);
  const total = Math.floor(safe / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = String(total % 60).padStart(2, '0');
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`;
}
