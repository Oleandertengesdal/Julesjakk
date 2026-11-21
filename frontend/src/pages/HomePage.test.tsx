import { act, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { kari, leaderboard, makeGame, makeListItem, makeTournamentSummary, makeTv, ola } from '../test/fixtures';
import { mockApi, renderPage } from '../test/harness';
import { emit } from '../test/liveMock';
import { HomePage } from './HomePage';

vi.mock('../lib/live', () => import('../test/liveMock'));
afterEach(() => vi.restoreAllMocks());

const base = [
  ['/api/tv', makeTv()],
  ['/api/games/pending', []],
  ['/api/games?limit=8', [makeListItem({ id: 31, result: 'WHITE_WIN', whiteRatingDelta: 12, blackRatingDelta: -12 })]],
  ['/api/tournaments', [makeTournamentSummary(), makeTournamentSummary({ id: 6, name: 'Gammel cup', status: 'FINISHED' })]],
  [/^\/api\/leaderboard/, leaderboard],
] as [string | RegExp, unknown][];

describe('HomePage', () => {
  it('shows the live game, recent games, the leaderboard and open tournaments', async () => {
    mockApi({ get: base });
    renderPage(<HomePage />, { url: '/' });
    expect(await screen.findByText('Spilles nå')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Kari');
    expect(screen.getByText(/Lyn, trekk 2/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Følg partiet' })).toHaveAttribute('href', '/matches/17');
    const rangering = screen.getByRole('region', { name: 'Rangering' });
    expect(within(rangering).getByText('1320')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Julecupen' })).toBeInTheDocument();
    expect(screen.queryByText('Gammel cup')).toBeNull();
    expect(screen.getByText('+12')).toBeInTheDocument();
  });

  it('follows the TV game as it changes', async () => {
    mockApi({ get: base });
    renderPage(<HomePage />, { url: '/' });
    await screen.findByText('Spilles nå');
    act(() => emit('/topic/tv', makeTv({ game: makeGame({ status: 'FINISHED', result: 'BLACK_WIN' }), reason: 'RECENT' })));
    expect(screen.getByText('Siste parti')).toBeInTheDocument();
    expect(screen.getByText(/endte 0–1/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Se gjennom partiet' })).toBeInTheDocument();
  });

  it('invites the first game when there is nothing yet', async () => {
    mockApi({ get: [['/api/tv', makeTv({ game: null, reason: null })], ['/api/games/pending', []], ['/api/games?limit=8', []],
      ['/api/tournaments', []], [/^\/api\/leaderboard/, []]] });
    renderPage(<HomePage />, { url: '/' });
    expect(await screen.findByText('Brettet står klart')).toBeInTheDocument();
    expect(await screen.findByText(/Ingen partier ennå/)).toBeInTheDocument();
    expect(await screen.findByText(/Ingen ratede partier i hurtig/)).toBeInTheDocument();
  });

  it('switches the leaderboard between categories', async () => {
    const api = mockApi({ get: base });
    const { user } = renderPage(<HomePage />, { url: '/' });
    await screen.findByText('Spilles nå');
    await user.click(screen.getByRole('tab', { name: 'Lyn' }));
    expect(api.get).toHaveBeenCalledWith('/api/leaderboard?category=BLITZ');
  });

  it('puts results waiting for me first, and confirming reloads the lists', async () => {
    const pending = makeGame({ id: 50, status: 'PENDING', registeredBy: ola, white: ola, black: kari, confirmedBy: undefined });
    const api = mockApi({ get: [['/api/games/pending', [pending]], ...base.filter(([p]) => p !== '/api/games/pending')],
      post: [['/api/games/50/confirm', pending]] });
    const { user } = renderPage(<HomePage />, { url: '/' });
    const section = await screen.findByRole('region', { name: 'Venter på deg' });
    await user.click(within(section).getByRole('button', { name: 'Bekreft' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/50/confirm', {});
    expect(api.get.mock.calls.filter(([p]) => p === '/api/games/pending').length).toBeGreaterThan(1);
    expect(kari.id).toBe(2);
  });
});
