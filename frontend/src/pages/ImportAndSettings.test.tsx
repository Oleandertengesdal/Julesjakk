import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import { kari, kariUser, makeGame, makeUser } from '../test/fixtures';
import { authAs, mockApi, renderPage } from '../test/harness';
import ImportPage from './ImportPage';
import SettingsPage from './SettingsPage';

afterEach(() => vi.restoreAllMocks());

describe('ImportPage', () => {
  it('asks to link an online account first', () => {
    renderPage(<ImportPage />, { url: '/games/import', user: kariUser });
    expect(screen.getByText(/Koble brukernavnet ditt/)).toBeInTheDocument();
  });

  it('imports from a link and opens the game', async () => {
    const api = mockApi({ post: [['/api/games/import', makeGame({ id: 91, source: 'LICHESS' })]] });
    const { user } = renderPage(<ImportPage />, { url: '/games/import', user: makeUser(kari, { lichessUsername: 'kari_k' }) });
    expect(screen.queryByText(/Koble brukernavnet ditt/)).toBeNull();
    await user.type(screen.getByLabelText('Lenke'), 'https://lichess.org/AbCd1234');
    await user.click(screen.getByRole('button', { name: 'Importer' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/import', { url: 'https://lichess.org/AbCd1234' });
    expect(await screen.findByText('Side: /matches/91')).toBeInTheDocument();
  });

  it('imports pasted PGN and explains a refusal', async () => {
    const api = mockApi({ post: [['/api/games/import', new ApiError(422, 'CHESS-IMPORT-003', 'Begge spillerne må være i familien.', 'w')]] });
    const { user } = renderPage(<ImportPage />, { url: '/games/import', user: kariUser });
    await user.click(screen.getByRole('tab', { name: 'Lim inn PGN' }));
    await user.type(screen.getByLabelText('PGN fra Lichess eller Chess.com'), '1. e4');
    await user.click(screen.getByRole('button', { name: 'Importer' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/import', { pgn: '1. e4' });
    expect(await screen.findByText(/Begge spillerne må være i familien/)).toBeInTheDocument();
  });
});

describe('SettingsPage', () => {
  it('saves linked accounts and reloads the profile', async () => {
    const api = mockApi({ put: [['/api/users/me/accounts', kariUser]] });
    const auth = authAs(makeUser(kari, { lichessUsername: 'gammel' }));
    const { user } = renderPage(<SettingsPage />, { url: '/settings', auth });
    const lichess = screen.getByLabelText('Brukernavn på Lichess');
    expect(lichess).toHaveValue('gammel');
    await user.clear(lichess);
    await user.type(lichess, ' kari_k ');
    await user.type(screen.getByLabelText('Brukernavn på Chess.com'), 'KariK');
    await user.click(screen.getByRole('button', { name: 'Lagre' }));
    expect(api.put).toHaveBeenCalledWith('/api/users/me/accounts', { lichessUsername: 'kari_k', chessComUsername: 'KariK' });
    expect(auth.reload).toHaveBeenCalled();
    expect(await screen.findByText('Lagret.')).toBeInTheDocument();
  });

  it('shows field errors from the server', async () => {
    mockApi({ put: [['/api/users/me/accounts', new ApiError(422, 'CHESS-GEN-001', 'Sjekk feltene.', 'w', { lichessUsername: 'For langt.' })]] });
    const { user } = renderPage(<SettingsPage />, { url: '/settings', user: kariUser });
    await user.click(screen.getByRole('button', { name: 'Lagre' }));
    expect(await screen.findByText('For langt.')).toBeInTheDocument();
  });

  it('logs out', async () => {
    const auth = authAs(kariUser);
    const { user } = renderPage(<SettingsPage />, { url: '/settings', auth });
    await user.click(screen.getByRole('button', { name: 'Logg ut' }));
    expect(auth.logout).toHaveBeenCalled();
    expect(screen.getByRole('link', { name: 'Bytt passord' })).toHaveAttribute('href', '/change-password');
  });
});
