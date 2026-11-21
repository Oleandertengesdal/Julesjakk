import { act, fireEvent, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import { START_FEN } from '../lib/chess';
import type { Game } from '../lib/types';
import { drop } from '../test/boardMock';
import { evaluate } from '../test/engineMock';
import { adminRef, adminUser, kari, kariUser, makeAnalysis, makeGame, makeMatchup, makeUser, ola, olaUser, per, playMoves, SCHOLARS_MATE } from '../test/fixtures';
import { mockApi, renderPage, type Routes } from '../test/harness';
import { emit } from '../test/liveMock';
import MatchPage from './MatchPage';

vi.mock('../lib/live', () => import('../test/liveMock'));
vi.mock('../analysis/engine', () => import('../test/engineMock'));

beforeEach(() => evaluate.mockClear());
afterEach(() => vi.restoreAllMocks());

const finished = (extra: Partial<Game> = {}) => makeGame({ moves: SCHOLARS_MATE, plyCount: 7, termination: 'CHECKMATE',
  currentFen: SCHOLARS_MATE[6].fen, comment: 'Lærepenger', ...extra });

function routes(game: Game, more: Routes = []): Routes {
  return [...more, ['/api/games/17', game], ['/api/games/17/preview', makeMatchup()], ['/api/games/17/analysis', new ApiError(404, 'CHESS-GEN-002', 'Ingen', null)]];
}

async function open(game: Game, { user = kariUser, get = [] as Routes, post = [] as Routes, put = [] as Routes } = {}) {
  const api = mockApi({ get: routes(game, get), post, put });
  const view = renderPage(<MatchPage />, { url: '/matches/17', route: '/matches/:id', user });
  await screen.findByRole('heading', { level: 1 });
  return { ...view, api };
}

const board = (id: string) => screen.getByTestId(`chessboard-${id}`);

describe('a finished game', () => {
  it('shows players, result, rating changes, how it ended and the comment', async () => {
    await open(finished());
    const heading = screen.getByRole('heading', { level: 1 });
    expect(heading).toHaveTextContent('Kari');
    expect(heading).toHaveTextContent('1–0');
    expect(screen.getByText('+20')).toBeInTheDocument();
    expect(screen.getByText('Avgjort ved sjakkmatt')).toBeInTheDocument();
    expect(screen.getByText('«Lærepenger»')).toBeInTheDocument();
    expect(await screen.findByText('4–1–1')).toBeInTheDocument();
  });

  it('can be stepped through with buttons, the move list and the keyboard', async () => {
    const { user } = await open(finished());
    expect(screen.getByText('Trekk 4 av 4')).toBeInTheDocument();
    expect(board('recap-17')).toHaveAttribute('data-fen', SCHOLARS_MATE[6].fen);
    await user.click(screen.getByRole('button', { name: 'Til start' }));
    expect(board('recap-17')).toHaveAttribute('data-fen', START_FEN);
    await user.click(screen.getByRole('button', { name: 'Neste trekk' }));
    expect(screen.getByText('Trekk 1 av 4')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Nf6' }));
    expect(board('recap-17')).toHaveAttribute('data-fen', SCHOLARS_MATE[5].fen);
    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    expect(board('recap-17')).toHaveAttribute('data-fen', SCHOLARS_MATE[4].fen);
    fireEvent.keyDown(window, { key: 'End' });
    fireEvent.keyDown(window, { key: 'Home' });
    fireEvent.keyDown(window, { key: 'ArrowRight' });
    expect(board('recap-17')).toHaveAttribute('data-fen', SCHOLARS_MATE[0].fen);
    await user.click(screen.getByRole('button', { name: 'Forrige trekk' }));
    await user.click(screen.getByRole('button', { name: 'Til slutt' }));
    expect(screen.getByText('Trekk 4 av 4')).toBeInTheDocument();
  });

  it('shows a saved analysis: accuracy, graded moves, turning points and the eval bar', async () => {
    const { user } = await open(finished(), { get: [['/api/games/17/analysis', makeAnalysis()]] });
    expect(await screen.findByText('96.4 %')).toBeInTheDocument();
    expect(screen.getByText('61.2 %')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nf6, Bukk' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Vendepunkter' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /3… Nf6/ }));
    expect(screen.getByText(/er bukk/)).toBeInTheDocument();
    expect(screen.getByText(/Stockfish foretrakk g7g6/)).toBeInTheDocument();
    expect(screen.getByRole('meter', { name: 'Stilling' })).toBeInTheDocument();
  });

  it('runs Stockfish on request and saves the analysis for everyone', async () => {
    const { user, api } = await open(finished(), { put: [['/api/games/17/analysis', {}]] });
    await user.click(await screen.findByRole('button', { name: 'Analyser med Stockfish' }));
    expect(evaluate).toHaveBeenCalledTimes(8);
    expect(api.put).toHaveBeenCalledWith('/api/games/17/analysis', expect.objectContaining({ engine: 'Stockfish 19 (lite)', depth: 14 }));
    expect(await screen.findByRole('table', { name: 'Nøyaktighet' })).toBeInTheDocument();
  });

  it('reports an analysis that could not be saved', async () => {
    const { user } = await open(finished(), { put: [['/api/games/17/analysis', new ApiError(422, 'CHESS-ANALYSIS-001', 'Analysen passet ikke.', null)]] });
    await user.click(await screen.findByRole('button', { name: 'Analyser med Stockfish' }));
    expect(await screen.findByText(/Analysen passet ikke/)).toBeInTheDocument();
  });

  it('shows an admin the correction tools, and everyone the correction note', async () => {
    await open(finished({ correction: { reason: 'Feil vinner', by: adminRef, at: '2026-11-15T10:00:00Z' } }), { user: adminUser });
    expect(screen.getByRole('heading', { name: 'Rett resultat' })).toBeInTheDocument();
    expect(screen.getByText(/Resultatet ble rettet av Admin/)).toBeInTheDocument();
  });

  it('refreshes the matchup card after a correction, so it shows the recomputed ratings', async () => {
    const corrected = finished({ result: 'BLACK_WIN', correction: { reason: 'Feil vinner', by: adminRef, at: '2026-11-15T10:00:00Z' } });
    const { user, api } = await open(finished(), { user: adminUser, post: [['/api/admin/games/17/correct', corrected]] });
    await screen.findByText('4–1–1');
    const previews = () => api.get.mock.calls.filter(([p]) => p === '/api/games/17/preview').length;
    expect(previews()).toBe(1);
    await user.click(screen.getByLabelText('Svart vant'));
    await user.type(screen.getByLabelText('Begrunnelse'), 'Feil vinner');
    await user.click(screen.getByRole('button', { name: 'Lagre rettelse' }));
    await screen.findByText(/Resultatet ble rettet av Admin/);
    expect(previews()).toBe(2);
  });

  it('keeps the correction tools from players', async () => {
    await open(finished());
    expect(screen.queryByRole('heading', { name: 'Rett resultat' })).toBeNull();
  });

  it('shows an annulled game as annulled', async () => {
    await open(finished({ status: 'ANNULLED', whiteRatingDelta: undefined, blackRatingDelta: undefined,
      correction: { reason: 'Dobbeltregistrert', by: adminRef, at: '2026-11-15T10:00:00Z' } }));
    expect(screen.getByText('Annullert')).toBeInTheDocument();
    expect(screen.getByText(/teller ikke/)).toBeInTheDocument();
  });
});

describe('a result waiting for confirmation', () => {
  const pending = () => makeGame({ status: 'PENDING', registeredBy: kari, confirmedBy: undefined, whiteRatingDelta: undefined,
    blackRatingDelta: undefined, rejectionReason: undefined });

  it('lets the opponent confirm it', async () => {
    const { user, api } = await open(pending(), { user: olaUser, post: [['/api/games/17/confirm', makeGame()]] });
    expect(screen.getByText(/Kari registrerte resultatet/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Bekreft resultatet' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/confirm', {});
    expect(await screen.findByText('Ferdig')).toBeInTheDocument();
  });

  it('lets the reporter withdraw it but not confirm it', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue('Feil resultat');
    const { user, api } = await open(pending(), { post: [['/api/games/17/reject', makeGame({ status: 'REJECTED', rejectionReason: 'Feil resultat' })]] });
    expect(screen.queryByRole('button', { name: 'Bekreft resultatet' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Trekk tilbake' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/reject', { reason: 'Feil resultat' });
    expect(await screen.findByText('Avvist: Feil resultat')).toBeInTheDocument();
  });

  it('shows outsiders the state without buttons', async () => {
    await open(pending(), { user: makeUser(per) });
    expect(screen.queryByRole('button', { name: 'Avvis' })).toBeNull();
  });

  it('shows a refusal', async () => {
    const { user } = await open(pending(), { user: olaUser, post: [['/api/games/17/confirm', new ApiError(409, 'CHESS-GAME-003', 'Allerede bekreftet.', null)]] });
    await user.click(screen.getByRole('button', { name: 'Bekreft resultatet' }));
    expect(await screen.findByText(/Allerede bekreftet/)).toBeInTheDocument();
  });
});

describe('a live game', () => {
  const live = (extra: Partial<Game> = {}) => makeGame({ status: 'LIVE', result: undefined, termination: undefined, timeControl: '15+10',
    whiteRatingDelta: undefined, blackRatingDelta: undefined, moves: [], plyCount: 0, currentFen: START_FEN, ...extra });

  it('takes moves on the board and sends them with the expected ply', async () => {
    const after = live({ moves: playMoves('e4'), plyCount: 1, currentFen: playMoves('e4')[0].fen });
    const { api } = await open(live(), { post: [['/api/games/17/moves', {}]], get: [] });
    api.get.mockImplementation(async (path: string) => (path === '/api/games/17' ? after : makeMatchup()) as never);
    let accepted = false;
    act(() => { accepted = drop('live-17', 'e2', 'e4'); });
    expect(accepted).toBe(true);
    expect(api.post).toHaveBeenCalledWith('/api/games/17/moves', { move: 'e2e4', expectedPly: 0 });
    expect(await screen.findByRole('button', { name: 'e4' })).toBeInTheDocument();
  });

  it('refuses illegal moves and moves of the wrong colour on the board itself', async () => {
    const { api } = await open(live(), { post: [['/api/games/17/moves', {}]] });
    act(() => { expect(drop('live-17', 'e2', 'e5')).toBe(false); });
    act(() => { expect(drop('live-17', 'e7', 'e5')).toBe(false); });
    act(() => { expect(drop('live-17', 'e3', 'e4')).toBe(false); });
    expect(api.post).not.toHaveBeenCalled();
  });

  it('asks which piece a pawn becomes', async () => {
    const fen = '8/4P3/8/8/8/8/k7/4K3 w - - 0 1';
    const { user, api } = await open(live({ currentFen: fen }), { post: [['/api/games/17/moves', {}]] });
    act(() => { drop('live-17', 'e7', 'e8'); });
    const dialog = screen.getByRole('dialog', { name: 'Velg brikke' });
    await user.click(within(dialog).getByRole('button', { name: /Springer/ }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/moves', { move: 'e7e8n', expectedPly: 0 });
  });

  it('reloads after someone else moved first', async () => {
    const { api } = await open(live(), { post: [['/api/games/17/moves', new ApiError(409, 'CHESS-GAME-005', 'Noen andre var først.', null)]] });
    const before = api.get.mock.calls.filter(([p]) => p === '/api/games/17').length;
    act(() => { drop('live-17', 'e2', 'e4'); });
    expect(await screen.findByText(/Noen andre var først/)).toBeInTheDocument();
    expect(api.get.mock.calls.filter(([p]) => p === '/api/games/17').length).toBeGreaterThan(before);
  });

  it('follows moves made on another phone', async () => {
    await open(live());
    act(() => emit('/topic/games/17', { type: 'game', game: live({ moves: playMoves('d4'), plyCount: 1, currentFen: playMoves('d4')[0].fen }) }));
    expect(screen.getByRole('button', { name: 'd4' })).toBeInTheDocument();
  });

  it('finishes and aborts', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { user, api } = await open(live(), { post: [
      ['/api/games/17/finish', makeGame({ status: 'PENDING', result: 'DRAW', termination: 'AGREEMENT' })],
      ['/api/games/17/abort', live({ status: 'ABORTED' })],
    ] });
    await user.selectOptions(screen.getByLabelText('Resultat'), 'Remis');
    await user.selectOptions(screen.getByLabelText('Hvordan'), 'enighet');
    await user.click(screen.getByRole('button', { name: 'Registrer resultat' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/finish', { result: 'DRAW', termination: 'AGREEMENT' });
    expect(await screen.findByText('Venter på bekreftelse')).toBeInTheDocument();
  });

  it('aborts after asking', async () => {
    vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    const { user, api } = await open(live(), { post: [['/api/games/17/abort', live({ status: 'ABORTED' })]] });
    await user.click(screen.getByRole('button', { name: 'Avbryt partiet' }));
    expect(api.post).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Avbryt partiet' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/abort', {});
  });

  it('shows the clocks and a way to open the chess clock', async () => {
    await open(live({ clock: { initialMs: 900_000, incrementMs: 10_000, whiteMs: 600_000, blackMs: 700_000, running: null, sequence: 3,
      updatedAt: '2026-12-24T18:00:00Z', serverNow: '2026-12-24T18:00:00Z' } }));
    expect(screen.getByRole('timer', { name: 'Hvits klokke' })).toHaveTextContent('10:00');
    expect(screen.getByRole('link', { name: 'Åpne sjakkur' })).toHaveAttribute('href', '/matches/17/klokke');
  });

  it('turns the board, shows Stockfish, and lets an admin put the game on TV', async () => {
    const { user, api } = await open(live(), { user: adminUser, post: [['/api/admin/games/17/feature', live({ featured: true })]] });
    await user.click(screen.getByRole('button', { name: 'Snu brettet' }));
    expect(board('live-17')).toHaveAttribute('data-orientation', 'black');
    await user.click(screen.getByRole('button', { name: 'Vis Stockfish' }));
    expect(await screen.findByRole('meter', { name: 'Stilling' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Vis på TV' }));
    expect(api.post).toHaveBeenCalledWith('/api/admin/games/17/feature', { featured: true });
    expect(await screen.findByRole('button', { name: 'Fjern fra TV' })).toBeInTheDocument();
  });

  it('is read-only for outsiders', async () => {
    await open(live(), { user: makeUser(per) });
    expect(board('live-17')).toHaveAttribute('data-draggable', 'no');
    expect(screen.queryByRole('heading', { name: 'Avslutt partiet' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Åpne sjakkur' })).toBeNull();
  });
});

describe('a tournament game not yet played', () => {
  const scheduled = () => makeGame({ status: 'SCHEDULED', result: undefined, termination: undefined, whiteRatingDelta: undefined,
    blackRatingDelta: undefined, tournamentPairingId: 11, stage: 'PLAYOFF' });

  it('can be started or have its result reported by the players', async () => {
    const { user, api } = await open(scheduled(), { post: [['/api/games/17/report', makeGame({ status: 'PENDING' })]] });
    expect(screen.getByText('Omspill (lyn)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start innspilling' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remis' }));
    expect(api.post).toHaveBeenCalledWith('/api/games/17/report', { result: 'DRAW' });
  });

  it('is only shown to others', async () => {
    await open(scheduled(), { user: makeUser(per) });
    expect(screen.getByText('Partiet er ikke spilt ennå.')).toBeInTheDocument();
  });
});

it('shows a problem screen for a game that does not exist', async () => {
  mockApi({ get: [['/api/games/17', new ApiError(404, 'CHESS-GAME-001', 'Fant ikke partiet.', 'web-x')]] });
  renderPage(<MatchPage />, { url: '/matches/17', route: '/matches/:id', user: kariUser });
  expect(await screen.findByText('Finner det ikke')).toBeInTheDocument();
  expect(ola.id).toBe(3);
});
