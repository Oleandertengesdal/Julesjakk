import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import { kariUser, makeUser } from '../test/fixtures';
import { authAs, mockApi, renderPage } from '../test/harness';
import { ChangePasswordPage, RegisterPage, ResetPasswordPage } from './AuthPages';

afterEach(() => vi.restoreAllMocks());

describe('RegisterPage', () => {
  it('takes the code from the invite link, registers and signs in', async () => {
    const api = mockApi({ post: [['/api/auth/register', kariUser]] });
    const login = vi.fn(async () => kariUser);
    const { user } = renderPage(<RegisterPage />, { url: '/register?kode=JUL-2026', route: '/register', user: null, auth: authAs(null, { login }) });
    expect(screen.getByLabelText('Invitasjonskode')).toHaveValue('JUL-2026');
    await user.type(screen.getByLabelText('Navn som vises'), 'Kari');
    await user.type(screen.getByLabelText('Brukernavn'), 'kari');
    await user.type(screen.getByLabelText('Passord'), 'langt-passord');
    await user.click(screen.getByRole('button', { name: 'Opprett konto' }));
    expect(api.post).toHaveBeenCalledWith('/api/auth/register', { inviteCode: 'JUL-2026', username: 'kari', displayName: 'Kari', password: 'langt-passord' });
    expect(login).toHaveBeenCalledWith('kari', 'langt-passord');
    expect(await screen.findByText('Side: /')).toBeInTheDocument();
  });

  it('shows field errors next to the field', async () => {
    mockApi({ post: [['/api/auth/register', new ApiError(422, 'CHESS-GEN-001', 'Sjekk feltene.', null, { username: 'Brukernavnet er tatt.' })]] });
    const { user } = renderPage(<RegisterPage />, { url: '/register', route: '/register', user: null });
    await user.click(screen.getByRole('button', { name: 'Opprett konto' }));
    expect(await screen.findByText('Brukernavnet er tatt.')).toBeInTheDocument();
  });
});

describe('ResetPasswordPage', () => {
  it('sets a new password with the token from the link', async () => {
    const api = mockApi({ post: [['/api/auth/reset-password', null]] });
    const { user } = renderPage(<ResetPasswordPage />, { url: '/reset-password?token=abc', route: '/reset-password', user: null });
    await user.type(screen.getByLabelText('Nytt passord'), 'nytt-passord');
    await user.click(screen.getByRole('button', { name: 'Lagre nytt passord' }));
    expect(api.post).toHaveBeenCalledWith('/api/auth/reset-password', { token: 'abc', newPassword: 'nytt-passord' });
    expect(await screen.findByText(/Passordet er endret/)).toBeInTheDocument();
  });

  it('explains a link without a token', () => {
    mockApi({});
    renderPage(<ResetPasswordPage />, { url: '/reset-password', route: '/reset-password', user: null });
    expect(screen.getByText(/Lenken mangler en kode/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lagre nytt passord' })).toBeDisabled();
  });

  it('shows an expired link', async () => {
    mockApi({ post: [['/api/auth/reset-password', new ApiError(400, 'CHESS-AUTH-006', 'Lenken er utløpt.', null)]] });
    const { user } = renderPage(<ResetPasswordPage />, { url: '/reset-password?token=old', route: '/reset-password', user: null });
    await user.click(screen.getByRole('button', { name: 'Lagre nytt passord' }));
    expect(await screen.findByText(/Lenken er utløpt/)).toBeInTheDocument();
  });
});

describe('ChangePasswordPage', () => {
  it('explains a temporary password, changes it and signs out everywhere', async () => {
    const api = mockApi({ post: [['/api/auth/change-password', null]] });
    const forced = makeUser({ id: 2, username: 'kari', displayName: 'Kari' }, { mustChangePassword: true });
    const auth = authAs(forced);
    const { user } = renderPage(<ChangePasswordPage />, { url: '/change-password', route: '/change-password', auth });
    expect(screen.getByText(/midlertidig passord/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Nåværende passord'), 'Tmp-1');
    await user.type(screen.getByLabelText('Nytt passord'), 'mitt-eget');
    await user.click(screen.getByRole('button', { name: 'Lagre og logg inn på nytt' }));
    expect(api.post).toHaveBeenCalledWith('/api/auth/change-password', { currentPassword: 'Tmp-1', newPassword: 'mitt-eget' });
    expect(auth.logout).toHaveBeenCalled();
    expect(await screen.findByText('Side: /login')).toBeInTheDocument();
  });
});
