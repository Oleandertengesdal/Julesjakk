import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import { adminUser, kariUser, makeGame, olaUser } from '../test/fixtures';
import { mockApi, renderPage } from '../test/harness';
import NewGamePage from './NewGamePage';

afterEach(() => vi.restoreAllMocks());

const users = [['/api/users', [adminUser, kariUser, olaUser]]] as [string, unknown][];

async function open(user = kariUser) {
  const view = renderPage(<NewGamePage />, { url: '/games/new', user });
  await screen.findAllByRole('option', { name: 'Ola' });
  return view;
}

describe('NewGamePage', () => {
  it('registers a result with me as White, an idempotency key, and goes to the game', async () => {
    const api = mockApi({ get: users, post: [['/api/games', makeGame({ id: 77, status: 'PENDING' })]] });
    const { user } = await open();
    expect(screen.getByLabelText('Hvit', { selector: 'select' })).toHaveValue('2');
    await user.selectOptions(screen.getByLabelText('Svart', { selector: 'select' }), 'Ola');
    await user.click(screen.getByText('Hvit vant'));
    await user.type(screen.getByLabelText('Tidskontroll'), '5+3');
    expect(screen.getByText('Teller som lyn.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Kommentar'), 'Fin kamp');
    await user.click(screen.getByRole('button', { name: 'Registrer parti' }));
    expect(api.post).toHaveBeenCalledWith('/api/games', expect.objectContaining({ whiteId: 2, blackId: 3, result: 'WHITE_WIN',
      timeControl: '5+3', category: 'BLITZ', comment: 'Fin kamp', rated: true }), { 'Idempotency-Key': expect.stringMatching(/[0-9a-f-]{36}/) });
    expect(await screen.findByText('Side: /matches/77')).toBeInTheDocument();
    expect(screen.queryByText(/Motstanderen må bekrefte/)).toBeNull();
  });

  it('tells players the opponent must confirm, but not admins', async () => {
    mockApi({ get: users });
    const { unmount } = await open();
    expect(screen.getByText(/Motstanderen må bekrefte resultatet/)).toBeInTheDocument();
    unmount();
    await open(adminUser);
    expect(screen.queryByText(/Motstanderen må bekrefte resultatet/)).toBeNull();
  });

  it('refuses a time control it cannot read before sending anything', async () => {
    const api = mockApi({ get: users });
    const { user } = await open();
    await user.type(screen.getByLabelText('Tidskontroll'), 'fem minutter');
    expect(screen.getByText(/for eksempel 5\+3/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Registrer parti' })).toBeDisabled();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('sends a pasted PGN and shows what is wrong with it', async () => {
    const api = mockApi({ get: users, post: [['/api/games', new ApiError(422, 'CHESS-PGN-002', 'Ulovlig trekk 3...Qxf2.', 'web-1')]] });
    const { user } = await open();
    await user.click(screen.getByRole('tab', { name: 'Lim inn PGN' }));
    await user.selectOptions(screen.getByLabelText('Svart', { selector: 'select' }), 'Ola');
    await user.click(screen.getByText('Remis'));
    await user.type(screen.getByLabelText('PGN'), '1. e4 e5');
    await user.click(screen.getByRole('button', { name: 'Registrer parti' }));
    expect(api.post).toHaveBeenCalledWith('/api/games', expect.objectContaining({ pgn: '1. e4 e5', result: 'DRAW' }), expect.anything());
    expect((await screen.findAllByText(/Ulovlig trekk 3...Qxf2/)).length).toBeGreaterThan(0);
  });

  it('starts a live game and opens the chess clock on this phone', async () => {
    const api = mockApi({ get: users, post: [['/api/games/live', makeGame({ id: 88, status: 'LIVE' })]] });
    const { user } = await open();
    await user.click(screen.getByRole('tab', { name: 'Før trekkene underveis' }));
    await user.selectOptions(screen.getByLabelText('Svart', { selector: 'select' }), 'Ola');
    await user.type(screen.getByLabelText('Tidskontroll'), '15+10');
    expect(screen.getByLabelText('Bruk denne telefonen som sjakkur')).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Start partiet' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/live', expect.objectContaining({ timeControl: '15+10', category: 'RAPID' }));
    expect(await screen.findByText('Side: /matches/88/klokke')).toBeInTheDocument();
  });

  it('starts a live game without the clock when asked', async () => {
    mockApi({ get: users, post: [['/api/games/live', makeGame({ id: 88, status: 'LIVE' })]] });
    const { user } = await open();
    await user.click(screen.getByRole('tab', { name: 'Før trekkene underveis' }));
    await user.selectOptions(screen.getByLabelText('Svart', { selector: 'select' }), 'Ola');
    await user.type(screen.getByLabelText('Tidskontroll'), '15+10');
    await user.click(screen.getByLabelText('Bruk denne telefonen som sjakkur'));
    await user.click(screen.getByRole('button', { name: 'Start partiet' }));
    expect(await screen.findByText('Side: /matches/88')).toBeInTheDocument();
  });

  it('can register an unrated game with a termination', async () => {
    const api = mockApi({ get: users, post: [['/api/games', makeGame({ id: 79 })]] });
    const { user } = await open();
    await user.selectOptions(screen.getByLabelText('Svart', { selector: 'select' }), 'Ola');
    await user.click(screen.getByText('Svart vant'));
    await user.selectOptions(screen.getByLabelText('Hvordan endte partiet?'), 'sjakkmatt');
    await user.click(screen.getByLabelText('Ratet parti'));
    await user.click(screen.getByRole('button', { name: 'Registrer parti' }));
    expect(api.post).toHaveBeenCalledWith('/api/games', expect.objectContaining({ rated: false, termination: 'CHECKMATE', result: 'BLACK_WIN' }),
      expect.anything());
  });
});
