import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import { kariUser, leaderboard, makeListItem, makeMatchup, makeStats, makeUser, ola, olaUser, per } from '../test/fixtures';
import { mockApi, renderPage } from '../test/harness';
import PlayerPage from './PlayerPage';
import PlayersPage from './PlayersPage';

afterEach(() => vi.restoreAllMocks());

describe('PlayersPage', () => {
  it('ranks players per category and lists those without rated games', async () => {
    const api = mockApi({ get: [[/^\/api\/leaderboard/, leaderboard], ['/api/users', [kariUser, olaUser, makeUser(per),
      makeUser({ id: 9, username: 'borte', displayName: 'Borte' }, { enabled: false })]]] });
    const { user } = renderPage(<PlayersPage />, { url: '/players' });
    const table = await screen.findByRole('table', { name: 'Rangering i hurtig' });
    expect(within(table).getByRole('link', { name: 'Kari' })).toHaveAttribute('href', '/players/2');
    expect(within(table).getByText('1340')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Per' })).toBeInTheDocument();
    expect(screen.queryByText('Borte')).toBeNull();
    await user.click(screen.getByRole('tab', { name: 'Langsjakk' }));
    expect(api.get).toHaveBeenCalledWith('/api/leaderboard?category=CLASSICAL');
  });

  it('says when nobody has played yet', async () => {
    mockApi({ get: [[/^\/api\/leaderboard/, []], ['/api/users', []]] });
    renderPage(<PlayersPage />, { url: '/players' });
    expect(await screen.findByText(/Ingen har spilt ratede partier i hurtig/)).toBeInTheDocument();
  });
});

describe('PlayerPage', () => {
  const get = (extra: [string | RegExp, unknown][] = []) => [...extra,
    ['/api/users/3/stats', makeStats({ user: ola })],
    [/rating-history/, [{ gameId: null, at: '2026-09-01T10:00:00Z', rating: 1200 }, { gameId: 4, at: '2026-09-02T10:00:00Z', rating: 1215 }]],
    [/\/games\?limit=15/, [makeListItem()]],
    [/^\/api\/matchups/, makeMatchup()],
    ['/api/users', [kariUser, olaUser, makeUser(per)]],
  ] as [string | RegExp, unknown][];

  it('shows ratings, results, streaks, rivals, badges and recent games', async () => {
    mockApi({ get: get() });
    renderPage(<PlayerPage />, { url: '/players/3', route: '/players/:id' });
    expect(await screen.findByRole('heading', { level: 1, name: 'Ola' })).toBeInTheDocument();
    expect(screen.getByText('1320')).toBeInTheDocument();
    expect(screen.getByText('12 partier: 7 seire, 2 remis, 3 tap')).toBeInTheDocument();
    expect(screen.getByText('3 seire')).toBeInTheDocument();
    expect(screen.getByText('84.2 %')).toBeInTheDocument();
    expect(screen.getByText('Første seier')).toBeInTheDocument();
    expect(await screen.findByLabelText('Graf over rating i hurtig')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Du mot Ola' })).toBeInTheDocument();
  });

  it('lets me compare myself with someone on my own page', async () => {
    const api = mockApi({ get: get([['/api/users/2/stats', makeStats()]]) });
    const { user } = renderPage(<PlayerPage />, { url: '/players/2', route: '/players/:id' });
    await screen.findByRole('heading', { name: 'Sammenlign med' });
    await user.selectOptions(await screen.findByLabelText('Motstander'), 'Per');
    expect(api.get).toHaveBeenCalledWith('/api/matchups?a=2&b=4&category=RAPID');
  });

  it('handles a player without games or analyses', async () => {
    mockApi({ get: get([['/api/users/3/stats', makeStats({ user: ola, games: 0, wins: 0, draws: 0, losses: 0, currentStreak: null,
      averageAccuracy: null, analysedGames: 0, nemesis: null, favouriteOpponent: null, badges: [] })], [/rating-history/, []], [/\/games\?limit=15/, []]]) });
    const { user } = renderPage(<PlayerPage />, { url: '/players/3', route: '/players/:id' });
    expect(await screen.findByText('Ingen ferdige partier ennå.')).toBeInTheDocument();
    expect(screen.getByText('Ingen analyserte partier')).toBeInTheDocument();
    expect(await screen.findByText(/Ingen ratede partier i hurtig/)).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Lyn' }));
    expect(screen.getByText(/Ingen ratede partier i lyn/)).toBeInTheDocument();
  });

  it('shows a problem screen for an unknown player', async () => {
    mockApi({ get: [['/api/users/3/stats', new ApiError(404, 'CHESS-USER-001', 'Fant ikke spilleren.', null)]] });
    renderPage(<PlayerPage />, { url: '/players/3', route: '/players/:id' });
    expect(await screen.findByText('Finner det ikke')).toBeInTheDocument();
  });
});
