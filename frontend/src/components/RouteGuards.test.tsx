import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { adminUser, kariUser, makeUser, kari } from '../test/fixtures';
import { authAs, renderPage } from '../test/harness';
import { RequireAdmin, RequireAuth } from './RouteGuards';

describe('RequireAuth', () => {
  it('sends visitors without a session to login, remembering where they were going', () => {
    renderPage(<RequireAuth><p>Hemmelig</p></RequireAuth>, { url: '/tournaments/5?tab=x', route: '/tournaments/:id', user: null });
    expect(screen.getByText('Side: /login?next=%2Ftournaments%2F5%3Ftab%3Dx')).toBeInTheDocument();
  });

  it('waits while the session is being restored', () => {
    renderPage(<RequireAuth><p>Hemmelig</p></RequireAuth>, { url: '/', auth: authAs(null, { loading: true }) });
    expect(screen.getByText(/Henter økten din/)).toBeInTheDocument();
  });

  it('sends someone with a temporary password to the change form first', () => {
    renderPage(<RequireAuth><p>Hemmelig</p></RequireAuth>, { url: '/', user: makeUser(kari, { mustChangePassword: true }) });
    expect(screen.getByText('Side: /change-password')).toBeInTheDocument();
  });

  it('shows the page to a signed-in user', () => {
    renderPage(<RequireAuth><p>Hemmelig</p></RequireAuth>, { url: '/', user: kariUser });
    expect(screen.getByText('Hemmelig')).toBeInTheDocument();
  });
});

describe('RequireAdmin', () => {
  it('shows admin pages to admins only', () => {
    const { unmount } = renderPage(<RequireAdmin><p>Adminside</p></RequireAdmin>, { url: '/admin', user: adminUser });
    expect(screen.getByText('Adminside')).toBeInTheDocument();
    unmount();
    renderPage(<RequireAdmin><p>Adminside</p></RequireAdmin>, { url: '/admin', user: kariUser });
    expect(screen.queryByText('Adminside')).toBeNull();
    expect(screen.getByText('Ingen tilgang')).toBeInTheDocument();
  });
});
