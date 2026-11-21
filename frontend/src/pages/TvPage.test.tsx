import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { kari, makeGame, makeStanding, makeTv, ola } from '../test/fixtures';
import { evaluate } from '../test/engineMock';
import { mockApi, renderPage } from '../test/harness';
import { emit } from '../test/liveMock';
import TvPage from './TvPage';

vi.mock('../lib/live', () => import('../test/liveMock'));
vi.mock('../analysis/engine', () => import('../test/engineMock'));

beforeEach(() => { localStorage.clear(); evaluate.mockClear(); });
afterEach(() => vi.restoreAllMocks());

describe('TvPage', () => {
  it('shows the live game big: players, ratings, move number, last move and the tournament ticker', async () => {
    mockApi({ get: [['/api/tv', makeTv({ tournament: { id: 5, name: 'Julecupen', format: 'ROUND_ROBIN',
      standings: [makeStanding(1, kari, 2.5), makeStanding(2, ola, 0.5)] } })]] });
    renderPage(<TvPage />, { url: '/tv' });
    expect(await screen.findByText('Trekk 2')).toBeInTheDocument();
    expect(screen.getAllByText('Kari').length).toBe(2);
    expect(screen.getByText('1320')).toBeInTheDocument();
    expect(screen.getByText('Nf3')).toBeInTheDocument();
    expect(screen.getByText('Julecupen')).toBeInTheDocument();
    expect(screen.getByText('2½')).toBeInTheDocument();
  });

  it('switches to the next game as soon as the server says so', async () => {
    mockApi({ get: [['/api/tv', makeTv()]] });
    renderPage(<TvPage />, { url: '/tv' });
    await screen.findByText('Trekk 2');
    act(() => emit('/topic/tv', makeTv({ game: makeGame({ status: 'FINISHED', result: 'BLACK_WIN', termination: 'RESIGNATION' }), reason: 'RECENT' })));
    expect(screen.getByText(/^Siste parti, Lyn 5\+3/)).toBeInTheDocument();
    expect(screen.getByText('0–1')).toBeInTheDocument();
    expect(screen.getByText('Avgjort ved oppgitt')).toBeInTheDocument();
  });

  it('shows the clocks the phone at the board is running', async () => {
    const tv = makeTv();
    tv.game!.clock = { initialMs: 300_000, incrementMs: 3000, whiteMs: 184_000, blackMs: 9_500, running: null, sequence: 8,
      updatedAt: '2026-12-24T18:00:00Z', serverNow: '2026-12-24T18:00:00Z' };
    mockApi({ get: [['/api/tv', tv]] });
    renderPage(<TvPage />, { url: '/tv' });
    expect(await screen.findByRole('timer', { name: 'Hvits klokke' })).toHaveTextContent('3:04');
    expect(screen.getByRole('timer', { name: 'Svarts klokke' })).toHaveTextContent('9.5');
  });

  it('turns the evaluation bar on and off with E and remembers it', async () => {
    mockApi({ get: [['/api/tv', makeTv()]] });
    renderPage(<TvPage />, { url: '/tv' });
    await screen.findByText('Trekk 2');
    expect(screen.queryByRole('meter')).toBeNull();
    fireEvent.keyDown(window, { key: 'e' });
    expect(await screen.findByRole('meter', { name: 'Stilling' })).toBeInTheDocument();
    expect(localStorage.getItem('sjakkportal.tv.eval')).toBe('true');
    fireEvent.keyDown(window, { key: 'E' });
    expect(screen.queryByRole('meter')).toBeNull();
  });

  it('goes full screen with F', async () => {
    mockApi({ get: [['/api/tv', makeTv()]] });
    const request = vi.fn(async () => {});
    document.documentElement.requestFullscreen = request;
    renderPage(<TvPage />, { url: '/tv' });
    await screen.findByText('Trekk 2');
    fireEvent.keyDown(window, { key: 'f' });
    expect(request).toHaveBeenCalled();
  });

  it('waits nicely when nothing has been played', async () => {
    mockApi({ get: [['/api/tv', makeTv({ game: null, reason: null })]] });
    renderPage(<TvPage />, { url: '/tv' });
    expect(await screen.findByText('Ingen partier ennå')).toBeInTheDocument();
  });
});
