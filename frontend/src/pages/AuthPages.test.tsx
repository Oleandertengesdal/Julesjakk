import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';

const login = vi.fn();
vi.mock('../lib/auth', () => ({
  useAuth: () => ({ user: null, loading: false, login, logout: vi.fn(), reload: vi.fn() }),
}));

const { LoginPage } = await import('./AuthPages');

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<p>Forsiden</p>} />
        <Route path="/matches/:id" element={<p>Partisiden</p>} />
        <Route path="/change-password" element={<p>Bytt passord</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    login.mockReset();
  });

  it('shows the server’s message and code after a wrong password', async () => {
    login.mockRejectedValue(new ApiError(401, 'CHESS-AUTH-001', 'Feil brukernavn eller passord.', 't-1'));
    renderAt('/login');
    await userEvent.type(screen.getByLabelText('Brukernavn'), 'kari');
    await userEvent.type(screen.getByLabelText('Passord'), 'feil');
    await userEvent.click(screen.getByRole('button', { name: 'Logg inn' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Feil brukernavn eller passord.');
    expect(screen.getByText(/CHESS-AUTH-001/)).toBeInTheDocument();
  });

  it('tells a rate-limited user how long to wait', async () => {
    login.mockRejectedValue(new ApiError(429, 'CHESS-RATE-001', 'For mange forsøk.', null, {}, 30));
    renderAt('/login');
    await userEvent.type(screen.getByLabelText('Brukernavn'), 'kari');
    await userEvent.type(screen.getByLabelText('Passord'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Logg inn' }));
    expect(await screen.findByText(/prøv igjen om 30 sekunder/i)).toBeInTheDocument();
  });

  it('returns to the page the user came from, but only within the site', async () => {
    login.mockResolvedValue({ mustChangePassword: false });
    renderAt('/login?next=%2Fmatches%2F7');
    await userEvent.type(screen.getByLabelText('Brukernavn'), 'kari');
    await userEvent.type(screen.getByLabelText('Passord'), 'riktig-passord');
    await userEvent.click(screen.getByRole('button', { name: 'Logg inn' }));
    expect(await screen.findByText('Partisiden')).toBeInTheDocument();
  });

  it('ignores a redirect to another site', async () => {
    login.mockResolvedValue({ mustChangePassword: false });
    renderAt('/login?next=%2F%2Fevil.example%2Fphish');
    await userEvent.type(screen.getByLabelText('Brukernavn'), 'kari');
    await userEvent.type(screen.getByLabelText('Passord'), 'riktig-passord');
    await userEvent.click(screen.getByRole('button', { name: 'Logg inn' }));
    expect(await screen.findByText('Forsiden')).toBeInTheDocument();
  });

  it('sends a user with a temporary password straight to choosing a new one', async () => {
    login.mockResolvedValue({ mustChangePassword: true });
    renderAt('/login?next=%2Fmatches%2F7');
    await userEvent.type(screen.getByLabelText('Brukernavn'), 'kari');
    await userEvent.type(screen.getByLabelText('Passord'), 'midlertidig');
    await userEvent.click(screen.getByRole('button', { name: 'Logg inn' }));
    expect(await screen.findByText('Bytt passord')).toBeInTheDocument();
  });
});
