import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthContext } from '../lib/auth';
import { adminUser, kariUser, makeGame } from '../test/fixtures';
import { authAs, mockApi } from '../test/harness';
import { Layout } from './Layout';

afterEach(() => vi.restoreAllMocks());

function renderLayout(auth = authAs(kariUser)) {
  render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<Layout />}><Route path="/" element={<p>Forsiden</p>} /></Route>
          <Route path="/login" element={<p>Innlogging</p>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('Layout', () => {
  it('has the main menu, the page, and how many results wait for me', async () => {
    mockApi({ get: [['/api/games/pending', [makeGame({ status: 'PENDING' }), makeGame({ id: 18, status: 'PENDING' })]]] });
    renderLayout();
    const menu = screen.getByRole('navigation', { name: 'Hovedmeny' });
    expect(within(menu).getByRole('link', { name: /Registrer parti/ })).toBeInTheDocument();
    expect(within(menu).queryByRole('link', { name: 'Admin' })).toBeNull();
    expect(await screen.findByLabelText('2 venter')).toBeInTheDocument();
    expect(screen.getByText('Forsiden')).toBeInTheDocument();
  });

  it('shows the admin page to admins', () => {
    mockApi({ get: [['/api/games/pending', []]] });
    renderLayout(authAs(adminUser));
    expect(within(screen.getByRole('navigation', { name: 'Hovedmeny' })).getByRole('link', { name: 'Admin' })).toBeInTheDocument();
  });

  it('logs out and goes to the login page', async () => {
    mockApi({ get: [['/api/games/pending', []]] });
    const auth = authAs(kariUser);
    renderLayout(auth);
    await userEvent.click(screen.getByRole('button', { name: 'Logg ut' }));
    expect(auth.logout).toHaveBeenCalled();
    expect(await screen.findByText('Innlogging')).toBeInTheDocument();
  });
});
