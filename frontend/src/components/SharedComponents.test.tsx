import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import { kari, makeGame, makeListItem, makeMatchup, ola } from '../test/fixtures';
import { mockApi } from '../test/harness';
import { GameList } from './GameList';
import { MatchupCard } from './MatchupCard';
import { PendingGames } from './PendingGames';
import { Delta, Empty, ErrorNotice, Form, LiveDot, PlayerLink } from './ui';

afterEach(() => vi.restoreAllMocks());
const inRouter = (ui: React.ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe('GameList', () => {
  it('shows result, rating changes and status, and marks my games', () => {
    inRouter(<GameList highlightUser={kari.id} games={[
      makeListItem({ id: 1, result: 'WHITE_WIN', whiteRatingDelta: 14, blackRatingDelta: -14 }),
      makeListItem({ id: 2, status: 'LIVE', result: undefined }),
      makeListItem({ id: 3, status: 'PENDING', result: 'DRAW' }),
    ]} />);
    expect(screen.getByText('+14')).toBeInTheDocument();
    expect(screen.getByText('−14')).toBeInTheDocument();
    expect(screen.getByText('Venter på bekreftelse')).toBeInTheDocument();
    expect(screen.getAllByRole('link')[0]).toHaveAttribute('href', '/matches/1');
  });
});

describe('PendingGames', () => {
  it('confirms a result', async () => {
    const api = mockApi({ post: [['/api/games/17/confirm', {}]] });
    const onChange = vi.fn();
    inRouter(<PendingGames games={[makeGame({ status: 'PENDING' })]} onChange={onChange} showReporter />);
    expect(screen.getByText(/registrert av Kari/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Bekreft' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/confirm', {});
    expect(onChange).toHaveBeenCalled();
  });

  it('rejects with the reason the player gives', async () => {
    const api = mockApi({ post: [['/api/games/17/reject', {}]] });
    vi.spyOn(window, 'prompt').mockReturnValue('Jeg vant');
    inRouter(<PendingGames games={[makeGame({ status: 'PENDING' })]} onChange={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Avvis' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/reject', { reason: 'Jeg vant' });
  });

  it('shows why the server refused', async () => {
    mockApi({ post: [['/api/games/17/confirm', new ApiError(403, 'CHESS-GAME-008', 'Motstanderen må bekrefte.', 'web-1')]] });
    inRouter(<PendingGames games={[makeGame({ status: 'PENDING' })]} onChange={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Bekreft' }));
    expect(await screen.findByText(/Motstanderen må bekrefte/)).toBeInTheDocument();
  });
});

describe('MatchupCard', () => {
  it('shows ratings, the record, the odds and what is at stake', () => {
    inRouter(<MatchupCard matchup={makeMatchup()} />);
    expect(screen.getByText('1320')).toBeInTheDocument();
    expect(screen.getByText('4–1–1')).toBeInTheDocument();
    expect(screen.getByText(/52/)).toBeInTheDocument();
    expect(screen.getAllByText('+8').length).toBeGreaterThan(0);
  });
});

describe('small building blocks', () => {
  it('render what they say', () => {
    inRouter(<>
      <Form results={['W', 'D', 'L']} />
      <Form results={[]} />
      <Delta value={0} />
      <Delta value={null} />
      <LiveDot />
      <PlayerLink player={ola} />
      <PlayerLink player={null} />
      <Empty>Tomt her</Empty>
      <ErrorNotice error={new ApiError(500, 'CHESS-GEN-999', 'Noe gikk galt.', 'trace-1')} />
      <ErrorNotice error={null} />
    </>);
    expect(screen.getByLabelText('Siste partier: seier, remis, tap')).toBeInTheDocument();
    expect(screen.getByText('Ingen partier')).toBeInTheDocument();
    expect(screen.getByText('Direkte')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ola' })).toHaveAttribute('href', '/players/3');
    expect(screen.getByText('Tomt her')).toBeInTheDocument();
    expect(screen.getByText(/CHESS-GEN-999/)).toBeInTheDocument();
  });
});
