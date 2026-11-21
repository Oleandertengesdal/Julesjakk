import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../lib/api';
import type { Game } from '../lib/types';
import { makeGame } from '../test/fixtures';
import ClockPage from './ClockPage';

let now = 0;

function liveGame(extra: Partial<Game> = {}): Game {
  return makeGame({ status: 'LIVE', result: undefined, termination: undefined, timeControl: '5+3', whiteRatingDelta: undefined,
    blackRatingDelta: undefined, ...extra });
}

async function renderClock(game: Game) {
  vi.spyOn(api, 'get').mockResolvedValue(game);
  render(
    <MemoryRouter initialEntries={[`/matches/${game.id}/klokke`]}>
      <Routes>
        <Route path="/matches/:id/klokke" element={<ClockPage />} />
        <Route path="/matches/:id" element={<p>Partisiden</p>} />
      </Routes>
    </MemoryRouter>,
  );
  await act(async () => { await Promise.resolve(); });
}

function advance(ms: number) {
  now += ms;
  act(() => { vi.advanceTimersByTime(ms); });
}

beforeEach(() => {
  now = 0;
  vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'] });
  vi.spyOn(performance, 'now').mockImplementation(() => now);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('ClockPage', () => {
  it('shows both players with the full time from the time control', async () => {
    await renderClock(liveGame());
    expect(screen.getByRole('button', { name: /Kari.*5:00/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Ola.*5:00/ })).toBeInTheDocument();
    expect(screen.getByText(/Svart trykker for å starte/)).toBeInTheDocument();
  });

  it('starts when Black presses, hands over on every press and reports each change to the server', async () => {
    const put = vi.spyOn(api, 'put').mockResolvedValue({});
    await renderClock(liveGame());
    fireEvent.click(screen.getByRole('button', { name: /Ola/ }));          // Black starts White's clock
    await act(async () => { await Promise.resolve(); });
    expect(put).toHaveBeenLastCalledWith('/api/games/17/clock', { whiteMs: 300_000, blackMs: 300_000, running: 'WHITE', sequence: 1 });
    advance(12_000);
    expect(screen.getByRole('button', { name: /Kari.*4:48/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Kari/ }));          // White moves: -12 s, +3 s
    await act(async () => { await Promise.resolve(); });
    expect(put).toHaveBeenLastCalledWith('/api/games/17/clock', { whiteMs: 291_000, blackMs: 300_000, running: 'BLACK', sequence: 2 });
  });

  it('pauses and resumes', async () => {
    const put = vi.spyOn(api, 'put').mockResolvedValue({});
    await renderClock(liveGame());
    fireEvent.click(screen.getByRole('button', { name: 'Start klokken' }));
    advance(5000);
    fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
    await act(async () => { await Promise.resolve(); });
    expect(put).toHaveBeenLastCalledWith('/api/games/17/clock', expect.objectContaining({ running: null, whiteMs: 295_000 }));
    advance(60_000);
    expect(screen.getByRole('button', { name: /Kari.*4:55/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Fortsett' }));
    advance(1000);
    expect(screen.getByRole('button', { name: /Kari.*4:54/ })).toBeInTheDocument();
  });

  it('keeps the newest state and sends it again when the network was down', async () => {
    const put = vi.spyOn(api, 'put')
      .mockRejectedValueOnce(new ApiError(0, 'CHESS-CLIENT-001', 'Får ikke kontakt', null))
      .mockResolvedValue({});
    await renderClock(liveGame());
    fireEvent.click(screen.getByRole('button', { name: /Ola/ }));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByText(/Ikke lagret ennå/)).toBeInTheDocument();
    advance(3000);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(put).toHaveBeenCalledTimes(2);
    expect(put).toHaveBeenLastCalledWith('/api/games/17/clock', expect.objectContaining({ sequence: 1 }));
    expect(screen.queryByText(/Ikke lagret ennå/)).toBeNull();
  });

  it('flags the player who runs out of time and offers to register the result', async () => {
    vi.spyOn(api, 'put').mockResolvedValue({});
    const post = vi.spyOn(api, 'post').mockResolvedValue(liveGame({ status: 'PENDING', result: 'BLACK_WIN' }));
    await renderClock(liveGame({ timeControl: '1+0' }));
    fireEvent.click(screen.getByRole('button', { name: 'Start klokken' }));
    advance(61_000);
    expect(screen.getByText(/Tiden er ute for Kari/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Registrer: Ola vant på tid' }));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(post).toHaveBeenCalledWith('/api/games/17/finish', { result: 'BLACK_WIN', termination: 'TIMEOUT' });
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText('Partisiden')).toBeInTheDocument();
  });

  it('picks up a clock that was already running, for example after a reload', async () => {
    await renderClock(liveGame({ clock: { initialMs: 300_000, incrementMs: 3000, whiteMs: 200_000, blackMs: 250_000, running: 'BLACK',
      sequence: 9, updatedAt: '2026-12-24T18:00:00.000Z', serverNow: '2026-12-24T18:00:10.000Z' } }));
    expect(screen.getByRole('button', { name: /Ola.*4:00/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Kari.*3:20/ })).toBeInTheDocument();
  });

  it('explains when the game has no clock', async () => {
    await renderClock(liveGame({ timeControl: undefined }));
    expect(screen.getByText(/har ingen tidskontroll/)).toBeInTheDocument();
    await renderClock(makeGame());
    expect(screen.getAllByText(/ikke i gang/).length).toBeGreaterThan(0);
  });

  it('can turn the board around so each player has their own half', async () => {
    await renderClock(liveGame());
    const before = screen.getAllByRole('button', { name: /Kari|Ola/ }).map((b) => b.getAttribute('aria-label'));
    fireEvent.click(screen.getByRole('button', { name: 'Bytt side' }));
    const after = screen.getAllByRole('button', { name: /Kari|Ola/ }).map((b) => b.getAttribute('aria-label'));
    expect(after).toEqual([...before].reverse());
  });
});
