import { describe, expect, it } from 'vitest';
import { checkFlag, formatClock, fromServer, newClock, pause, press, remainingFromView, resume, snapshot, start, timeLeft } from './clock';

const FIVE_MIN = 300_000;

describe('a chess clock', () => {
  it('starts with both players on the full time and nobody running', () => {
    const c = newClock(FIVE_MIN, 3000);
    expect(timeLeft(c, 'WHITE', 1000)).toBe(FIVE_MIN);
    expect(timeLeft(c, 'BLACK', 99_000)).toBe(FIVE_MIN);
    expect(c.running).toBeNull();
    expect(c.started).toBe(false);
  });

  it('is started by Black pressing, which sets White running without an increment', () => {
    const c = press(newClock(FIVE_MIN, 3000), 'BLACK', 1000);
    expect(c.running).toBe('WHITE');
    expect(c.started).toBe(true);
    expect(timeLeft(c, 'BLACK', 1000)).toBe(FIVE_MIN);
    expect(timeLeft(c, 'WHITE', 6000)).toBe(FIVE_MIN - 5000);
  });

  it('can also be started with the start button', () => {
    expect(start(newClock(FIVE_MIN, 0), 0).running).toBe('WHITE');
  });

  it('ignores White pressing before the game has started', () => {
    const c = newClock(FIVE_MIN, 3000);
    expect(press(c, 'WHITE', 1000)).toBe(c);
  });

  it('takes the thinking time from the player who presses, adds the increment and hands over', () => {
    let c = start(newClock(FIVE_MIN, 3000), 0);
    c = press(c, 'WHITE', 12_000);
    expect(c.running).toBe('BLACK');
    expect(timeLeft(c, 'WHITE', 50_000)).toBe(FIVE_MIN - 12_000 + 3000);
    c = press(c, 'BLACK', 20_000);
    expect(timeLeft(c, 'BLACK', 20_000)).toBe(FIVE_MIN - 8000 + 3000);
    expect(c.running).toBe('WHITE');
  });

  it('ignores a press on the clock that is not running', () => {
    const c = start(newClock(FIVE_MIN, 0), 0);
    expect(press(c, 'BLACK', 5000)).toBe(c);
  });

  it('pauses and resumes the player who was thinking', () => {
    let c = start(newClock(FIVE_MIN, 0), 0);
    c = pause(c, 10_000);
    expect(c.running).toBeNull();
    expect(timeLeft(c, 'WHITE', 60_000)).toBe(FIVE_MIN - 10_000);
    expect(press(c, 'WHITE', 61_000)).toBe(c);
    c = resume(c, 70_000);
    expect(c.running).toBe('WHITE');
    expect(timeLeft(c, 'WHITE', 75_000)).toBe(FIVE_MIN - 15_000);
  });

  it('flags the player whose time runs out and then stops', () => {
    let c = start(newClock(10_000, 0), 0);
    expect(checkFlag(c, 9_999)).toBe(c);
    c = checkFlag(c, 10_500);
    expect(c.flagged).toBe('WHITE');
    expect(c.running).toBeNull();
    expect(timeLeft(c, 'WHITE', 20_000)).toBe(0);
    expect(press(c, 'WHITE', 21_000)).toBe(c);
    expect(resume(c, 22_000)).toBe(c);
  });

  it('counts every change, so the server can tell the newest state', () => {
    let c = newClock(FIVE_MIN, 0);
    expect(c.sequence).toBe(0);
    c = press(c, 'BLACK', 0);
    c = press(c, 'WHITE', 1000);
    c = pause(c, 2000);
    c = resume(c, 3000);
    expect(c.sequence).toBe(4);
  });

  it('turns its state into a snapshot with the running time counted up to now', () => {
    const c = press(start(newClock(FIVE_MIN, 2000), 0), 'WHITE', 4000);
    expect(snapshot(c, 9000)).toEqual({ whiteMs: FIVE_MIN - 4000 + 2000, blackMs: FIVE_MIN - 5000, running: 'BLACK', sequence: 2 });
  });

  it('picks up where the server says it was, minus the time since', () => {
    const c = fromServer({ initialMs: FIVE_MIN, incrementMs: 0, whiteMs: 200_000, blackMs: 250_000, running: 'BLACK', sequence: 9,
      updatedAt: '2026-12-24T18:00:00.000Z', serverNow: '2026-12-24T18:00:04.000Z' }, 50_000);
    expect(timeLeft(c, 'BLACK', 50_000)).toBe(246_000);
    expect(timeLeft(c, 'BLACK', 51_000)).toBe(245_000);
    expect(timeLeft(c, 'WHITE', 51_000)).toBe(200_000);
    expect(c.sequence).toBe(9);
    expect(c.started).toBe(true);
  });

  it('resumes a paused server clock as paused, with the side that was thinking', () => {
    const c = fromServer({ initialMs: FIVE_MIN, incrementMs: 0, whiteMs: 100_000, blackMs: FIVE_MIN, running: null, sequence: 3,
      updatedAt: '2026-12-24T18:00:00.000Z', serverNow: '2026-12-24T18:00:00.000Z' }, 0);
    expect(c.running).toBeNull();
    expect(c.started).toBe(true);
  });
});

describe('a clock seen from the TV', () => {
  const view = { initialMs: FIVE_MIN, incrementMs: 0, whiteMs: 120_000, blackMs: 90_000, running: 'WHITE' as const, sequence: 4,
    updatedAt: '2026-12-24T18:00:00.000Z', serverNow: '2026-12-24T18:00:02.000Z' };

  it('counts down the running side from when the server got the press, whatever the TV clock says', () => {
    expect(remainingFromView(view, 10_000, 10_000)).toEqual({ white: 118_000, black: 90_000 });
    expect(remainingFromView(view, 10_000, 13_000)).toEqual({ white: 115_000, black: 90_000 });
  });

  it('never shows less than zero', () => {
    expect(remainingFromView(view, 0, 500_000).white).toBe(0);
  });

  it('shows a stopped clock as it stands', () => {
    expect(remainingFromView({ ...view, running: null }, 0, 99_000)).toEqual({ white: 120_000, black: 90_000 });
  });
});

describe('formatClock', () => {
  it.each([
    [3_600_000, '1:00:00'], [3_725_000, '1:02:05'], [300_000, '5:00'], [65_000, '1:05'], [20_000, '0:20'],
    [19_950, '19.9'], [9_040, '9.0'], [0, '0.0'], [-500, '0.0'],
  ])('shows %i ms as %s', (ms, text) => {
    expect(formatClock(ms)).toBe(text);
  });
});
