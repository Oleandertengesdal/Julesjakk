import { act, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import type { Tournament } from '../lib/types';
import { adminUser, kari, kariUser, makeStanding, makeTournament, makeTournamentSummary, ola, olaUser, per } from '../test/fixtures';
import { mockApi, renderPage } from '../test/harness';
import { emit } from '../test/liveMock';
import TournamentPage from './TournamentPage';
import TournamentsPage from './TournamentsPage';

vi.mock('../lib/live', () => import('../test/liveMock'));
afterEach(() => vi.restoreAllMocks());

describe('TournamentsPage', () => {
  it('lists tournaments with format, players and status', async () => {
    mockApi({ get: [['/api/tournaments', [makeTournamentSummary(), makeTournamentSummary({ id: 6, name: 'Arena', format: 'ARENA',
      status: 'DRAFT', startsAt: '2026-12-27T15:00:00Z' })]]] });
    renderPage(<TournamentsPage />, { url: '/tournaments' });
    expect(await screen.findByText('Julecupen')).toBeInTheDocument();
    expect(screen.getByText(/Alle mot alle, hurtig 15\+10, 4 spillere/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ny turnering' })).toBeNull();
  });

  it('says so when there are none', async () => {
    mockApi({ get: [['/api/tournaments', []]] });
    renderPage(<TournamentsPage />, { url: '/tournaments' });
    expect(await screen.findByText(/En administrator kan opprette en/)).toBeInTheDocument();
  });

  it('lets an admin create a knockout with a draw rule', async () => {
    const api = mockApi({ get: [['/api/tournaments', []]], post: [['/api/tournaments', makeTournament({ id: 9 })]] });
    const { user } = renderPage(<TournamentsPage />, { url: '/tournaments', user: adminUser });
    await user.click(await screen.findByRole('button', { name: 'Ny turnering' }));
    await user.type(screen.getByLabelText('Navn'), 'Nyttårsutslaget');
    await user.selectOptions(screen.getByLabelText('Format'), 'Utslag');
    await user.clear(screen.getByLabelText('Tidskontroll'));
    await user.type(screen.getByLabelText('Tidskontroll'), '5+3');
    await user.selectOptions(screen.getByLabelText('Hvis et parti ender remis'), 'Armageddon');
    await user.click(screen.getByRole('button', { name: 'Opprett turnering' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments', expect.objectContaining({ name: 'Nyttårsutslaget',
      format: 'SINGLE_ELIMINATION', category: 'BLITZ', drawResolution: 'ARMAGEDDON' }));
    expect(await screen.findByText('Side: /tournaments/9')).toBeInTheDocument();
  });

  it('sends Swiss rounds and arena times, and shows refusals', async () => {
    const api = mockApi({ get: [['/api/tournaments', []]], post: [['/api/tournaments', new ApiError(422, 'CHESS-TOURN-008', 'Feil oppsett.', null)]] });
    const { user } = renderPage(<TournamentsPage />, { url: '/tournaments', user: adminUser });
    await user.click(await screen.findByRole('button', { name: 'Ny turnering' }));
    await user.type(screen.getByLabelText('Navn'), 'Sveitsisk');
    await user.selectOptions(screen.getByLabelText('Format'), 'Monrad (sveitsisk)');
    await user.type(screen.getByLabelText('Antall runder'), '5');
    await user.click(screen.getByRole('button', { name: 'Opprett turnering' }));
    expect(api.post).toHaveBeenLastCalledWith('/api/tournaments', expect.objectContaining({ format: 'SWISS', rounds: 5 }));
    expect(await screen.findByText(/Feil oppsett/)).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Format'), 'Arena');
    await user.click(screen.getByRole('button', { name: 'Opprett turnering' }));
    expect(api.post).toHaveBeenLastCalledWith('/api/tournaments', expect.objectContaining({ format: 'ARENA',
      startsAt: expect.stringMatching(/Z$/), endsAt: expect.stringMatching(/Z$/) }));
  });
});

async function openTournament(t: Tournament, user = kariUser, post: [string, unknown][] = [], extraGet: [string, unknown][] = []) {
  const api = mockApi({ get: [['/api/tournaments/5', t], ['/api/users', [adminUser, kariUser, olaUser]], ...extraGet], post,
    del: [[/participants/, makeTournament({ status: 'DRAFT', participants: [] })]] });
  const view = renderPage(<TournamentPage />, { url: '/tournaments/5', route: '/tournaments/:id', user });
  await screen.findByRole('heading', { level: 1, name: t.name });
  return { ...view, api };
}

describe('TournamentPage', () => {
  it('shows a round robin: standings with tie-breaks and the pairings', async () => {
    await openTournament(makeTournament({ standings: [makeStanding(1, kari, 1.5), makeStanding(2, ola, 0.5)] }));
    const table = screen.getByRole('table');
    expect(within(table).getAllByText('1½').length).toBeGreaterThan(0);
    expect(within(table).getByText('Buchh.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Runder' })).toBeInTheDocument();
    expect(screen.getByText('Ikke spilt')).toBeInTheDocument();
    expect(screen.getByText('Runde')).toBeInTheDocument();
  });

  it('follows live updates', async () => {
    await openTournament(makeTournament());
    act(() => emit('/topic/tournaments/5', { type: 'tournament', tournament: makeTournament({ status: 'FINISHED', champion: kari }) }));
    expect(screen.getByText('♔ Kari vant')).toBeInTheDocument();
  });

  it('lets players sign up and withdraw while it is open', async () => {
    const draft = makeTournament({ status: 'DRAFT', participants: [{ id: 2, user: ola, seed: 1, seedRating: 1250 }] });
    const { user, api } = await openTournament(draft, kariUser, [['/api/tournaments/5/join', makeTournament({ status: 'DRAFT' })],
      ['/api/tournaments/5/leave', draft]]);
    await user.click(screen.getByRole('button', { name: 'Meld meg på' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments/5/join', {});
    await user.click(await screen.findByRole('button', { name: 'Meld meg av' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments/5/leave', {});
    expect(screen.queryByRole('button', { name: 'Start turneringen' })).toBeNull();
  });

  it('lets an admin add and remove players, start and cancel', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const draft = makeTournament({ status: 'DRAFT', participants: [{ id: 2, user: ola, seed: 1, seedRating: 1250 }] });
    const { user, api } = await openTournament(draft, adminUser, [
      ['/api/tournaments/5/participants', makeTournament({ status: 'DRAFT' })],
      ['/api/tournaments/5/start', makeTournament()],
      ['/api/tournaments/5/cancel', makeTournament({ status: 'CANCELLED' })],
    ]);
    expect(screen.getByRole('button', { name: 'Start turneringen' })).toBeDisabled();
    await user.selectOptions(await screen.findByLabelText('Legg til spiller'), 'Kari');
    await user.click(screen.getByRole('button', { name: 'Legg til' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments/5/participants', { userId: 2 });
    await user.click(await screen.findByRole('button', { name: 'Start turneringen' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments/5/start', {});
    await user.click(await screen.findByRole('button', { name: 'Avlys' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments/5/cancel', {});
    expect(await screen.findByText('Avlyst')).toBeInTheDocument();
  });

  it('shows a knockout: draw rule, bracket and who went through', async () => {
    const ko = makeTournament({ format: 'SINGLE_ELIMINATION', drawResolution: 'BLITZ_PLAYOFF', totalRounds: null,
      bracket: [{ id: 1, side: 'WINNERS', round: 1, playerA: kari, playerB: ola, winner: kari },
        { id: 2, side: 'WINNERS', round: 2, playerA: kari, playerB: per, winner: null }],
      standings: [makeStanding(1, kari, 1), makeStanding(2, ola, 0, { eliminated: true })] });
    await openTournament(ko);
    expect(screen.getByText('To lynpartier, deretter armageddon')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Sluttspill' })).toBeInTheDocument();
    expect(screen.getAllByLabelText('videre').length).toBe(1);
    expect(screen.getByRole('heading', { name: 'Kamper' })).toBeInTheDocument();
  });

  it('runs an arena: register games while it is open, and an admin can close it', async () => {
    const arena = makeTournament({ format: 'ARENA', totalRounds: null, startsAt: '2026-12-27T15:00:00Z', endsAt: '2026-12-27T17:00:00Z',
      rounds: [], standings: [makeStanding(1, kari, 4, { streak: 3, onFire: true })] });
    const { user, api } = await openTournament(arena, adminUser, [['/api/tournaments/5/arena-games', {}], ['/api/tournaments/5/finish', { ...arena, status: 'FINISHED' }]]);
    expect(screen.getByRole('heading', { name: 'Registrer arenaparti' })).toBeInTheDocument();
    expect(screen.getByText('Rekke')).toBeInTheDocument();
    const form = screen.getByRole('region', { name: 'Registrer arenaparti' });
    await user.selectOptions(within(form).getByLabelText('Svart'), 'Ola');
    await user.selectOptions(within(form).getByLabelText('Resultat'), '1–0');
    await user.click(within(form).getByRole('button', { name: 'Registrer' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments/5/arena-games', { whiteId: 1, blackId: 3, result: 'WHITE_WIN' });
    await user.click(screen.getByRole('button', { name: 'Avslutt arenaen' }));
    expect(api.post).toHaveBeenCalledWith('/api/tournaments/5/finish', {});
  });

  it('shows why an action was refused, and a missing tournament', async () => {
    const { user } = await openTournament(makeTournament({ status: 'DRAFT', participants: [] }), kariUser,
      [['/api/tournaments/5/join', new ApiError(409, 'CHESS-TOURN-002', 'Påmeldingen er stengt.', null)]]);
    await user.click(screen.getByRole('button', { name: 'Meld meg på' }));
    expect(await screen.findByText(/Påmeldingen er stengt/)).toBeInTheDocument();
  });

  it('shows a problem screen for a tournament that does not exist', async () => {
    mockApi({ get: [['/api/tournaments/5', new ApiError(404, 'CHESS-TOURN-001', 'Fant ikke turneringen.', null)]] });
    renderPage(<TournamentPage />, { url: '/tournaments/5', route: '/tournaments/:id' });
    expect(await screen.findByText('Finner det ikke')).toBeInTheDocument();
  });
});
