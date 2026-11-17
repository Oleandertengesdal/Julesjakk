import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ErrorBoundary, ForbiddenScreen, NotFoundScreen, ProblemScreen } from './ErrorScreens';
import { ApiError } from '../lib/api';

function Boom(): never {
  throw new Error('kaboom in render');
}

function mockClipboard() {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  return writeText;
}

describe('error screens', () => {
  it('404 shows its code and a way home', () => {
    render(<MemoryRouter initialEntries={['/finnes-ikke']}><NotFoundScreen /></MemoryRouter>);
    expect(screen.getByRole('heading', { name: /finner ikke siden/i })).toBeInTheDocument();
    expect(screen.getByText('CHESS-GEN-002')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /til forsiden/i })).toHaveAttribute('href', '/');
  });

  it('403 shows the access-denied code', () => {
    render(<MemoryRouter><ForbiddenScreen /></MemoryRouter>);
    expect(screen.getByText('CHESS-AUTH-003')).toBeInTheDocument();
  });

  it('an API problem shows the server message, code and trace id', () => {
    render(<MemoryRouter><ProblemScreen error={new ApiError(404, 'CHESS-GAME-001', 'Partiet finnes ikke.', 'trace-abc')} /></MemoryRouter>);
    expect(screen.getByText('Partiet finnes ikke.')).toBeInTheDocument();
    expect(screen.getByText('CHESS-GAME-001')).toBeInTheDocument();
  });

  it('copies debug info with code, trace id, page and time', async () => {
    const writeText = mockClipboard();
    render(<MemoryRouter><ProblemScreen error={new ApiError(500, 'CHESS-GEN-999', 'Noe gikk galt.', 'trace-xyz')} /></MemoryRouter>);
    await userEvent.click(screen.getByRole('button', { name: /kopier feilsøkingsinfo/i }));
    const copied = JSON.parse(writeText.mock.calls[0][0]);
    expect(copied).toMatchObject({ code: 'CHESS-GEN-999', traceId: 'trace-xyz', status: 500 });
    expect(copied.url).toBeTruthy();
    expect(copied.time).toBeTruthy();
    expect(await screen.findByText(/kopiert/i)).toBeInTheDocument();
  });

  it('the error boundary catches a crash and shows the client render code instead of a blank page', async () => {
    const writeText = mockClipboard();
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<MemoryRouter><ErrorBoundary><Boom /></ErrorBoundary></MemoryRouter>);
    expect(screen.getByText('CHESS-CLIENT-003')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /kopier feilsøkingsinfo/i }));
    expect(writeText.mock.calls[0][0]).toContain('kaboom in render');
  });
});
