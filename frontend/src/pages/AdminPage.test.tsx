import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../lib/api';
import type { Invite } from '../lib/types';
import { adminUser, kari, kariUser, makeGame, makeUser, olaUser } from '../test/fixtures';
import { mockApi, renderPage, type Routes } from '../test/harness';
import AdminPage from './AdminPage';

afterEach(() => vi.restoreAllMocks());

const invite = (extra: Partial<Invite> = {}): Invite => ({ id: 1, code: 'JUL-2026', note: 'Familien', maxUses: 5, useCount: 1, remainingUses: 4,
  expiresAt: '2026-12-31T23:00:00Z', revokedAt: null, createdAt: '2026-11-01T10:00:00Z', status: 'ACTIVE',
  redemptions: [{ userId: 2, username: 'kari', displayName: 'Kari', redeemedAt: '2026-11-02T10:00:00Z' }], ...extra });

function open(get: Routes = [], post: Routes = [], del: Routes = []) {
  const api = mockApi({ get: [...get, ['/api/admin/games/pending', []], ['/api/admin/invites', [invite()]], ['/api/users', [adminUser, kariUser, olaUser]],
    ['/api/admin/diagnostics/request', { remoteAddress: '10.0.0.5', clientAddressUsedForRateLimits: '10.0.0.5', secure: false,
      forwardingHeaders: { 'X-Forwarded-For': '203.0.113.7' } }]], post, del });
  return { ...renderPage(<AdminPage />, { url: '/admin', user: adminUser }), api };
}

describe('AdminPage', () => {
  it('lists every unconfirmed game with who reported it', async () => {
    open([['/api/admin/games/pending', [makeGame({ status: 'PENDING', registeredBy: kari })]]]);
    expect(await screen.findByText(/registrert av Kari/)).toBeInTheDocument();
  });

  it('says when nothing is waiting', async () => {
    open();
    expect(await screen.findByText('Ingen partier venter på bekreftelse.')).toBeInTheDocument();
  });

  it('creates invites, copies the link and revokes', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { user, api } = open([], [['/api/admin/invites', invite({ id: 2, code: 'NY-KODE' })]], [['/api/admin/invites/1', null]]);
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    await user.click(screen.getByRole('tab', { name: 'Invitasjoner' }));
    expect(await screen.findByText('JUL-2026')).toBeInTheDocument();
    expect(screen.getByText(/4 av 5 igjen/)).toBeInTheDocument();
    expect(screen.getByText('Brukt av Kari')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Antall bruk'), 'Ubegrenset (familietreff)');
    await user.selectOptions(screen.getByLabelText('Gyldig i'), 'Uten utløp');
    await user.type(screen.getByLabelText('Notat'), 'Julaften');
    await user.click(screen.getByRole('button', { name: 'Lag invitasjon' }));
    expect(api.post).toHaveBeenCalledWith('/api/admin/invites', { code: undefined, maxUses: undefined, expiresInHours: undefined, note: 'Julaften' });
    await user.click(screen.getByRole('button', { name: 'Kopier lenke' }));
    expect(writeText).toHaveBeenCalledWith(expect.stringMatching(/\/register\?kode=JUL-2026$/));
    expect(screen.getByRole('button', { name: 'Lenke kopiert' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Trekk tilbake' }));
    expect(api.delete).toHaveBeenCalledWith('/api/admin/invites/1');
  });

  it('shows why an invite could not be made', async () => {
    const { user } = open([], [['/api/admin/invites', new ApiError(409, 'CHESS-INVITE-005', 'Koden finnes allerede.', null)]]);
    await user.click(screen.getByRole('tab', { name: 'Invitasjoner' }));
    await user.type(await screen.findByLabelText('Kode'), 'JUL-2026');
    await user.click(screen.getByRole('button', { name: 'Lag invitasjon' }));
    expect(await screen.findByText(/Koden finnes allerede/)).toBeInTheDocument();
  });

  it('issues a reset link, and says sessions end when it is used', async () => {
    const { user, api } = open([], [['/api/admin/users/2/password-reset', { resetToken: 'abc123', expiresAt: '2026-11-21T10:00:00Z' }]]);
    await user.click(screen.getByRole('tab', { name: 'Spillere og passord' }));
    await user.selectOptions(await screen.findByLabelText('Spiller'), 'Kari');
    await user.click(screen.getByRole('button', { name: 'Lag' }));
    expect(api.post).toHaveBeenCalledWith('/api/admin/users/2/password-reset', { mode: 'RESET_LINK' });
    expect(await screen.findByText(/\/reset-password\?token=abc123/)).toBeInTheDocument();
    expect(screen.getByText(/Når lenken brukes, avsluttes alle innlogginger/)).toBeInTheDocument();
  });

  it('issues a temporary password, which ends sessions at once', async () => {
    const { user } = open([], [['/api/admin/users/2/password-reset', { temporaryPassword: 'Tmp-Passord-99' }]]);
    await user.click(screen.getByRole('tab', { name: 'Spillere og passord' }));
    await user.selectOptions(await screen.findByLabelText('Spiller'), 'Kari');
    await user.selectOptions(screen.getByLabelText('Hvordan'), 'Midlertidig passord');
    await user.click(screen.getByRole('button', { name: 'Lag' }));
    expect(await screen.findByText(/Tmp-Passord-99/)).toBeInTheDocument();
    expect(screen.getByText(/Alle innlogginger for spilleren er avsluttet/)).toBeInTheDocument();
  });

  it('adds a player without a login', async () => {
    const { user, api } = open([], [['/api/admin/players', makeUser({ id: 9, username: 'lille', displayName: 'Lillebror' }, { managed: true })]]);
    await user.click(screen.getByRole('tab', { name: 'Spillere og passord' }));
    await user.type(await screen.findByLabelText('Navn som vises'), 'Lillebror');
    await user.type(screen.getByLabelText('Brukernavn'), 'lille');
    await user.click(screen.getByRole('button', { name: 'Legg til spiller' }));
    expect(api.post).toHaveBeenCalledWith('/api/admin/players', { username: 'lille', displayName: 'Lillebror' });
    expect(await screen.findByText(/Lillebror er lagt til/)).toBeInTheDocument();
  });

  it('disables an account from the access list', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { user, api } = open([], [['/api/admin/users/3/disable', { ...olaUser, enabled: false }]]);
    await user.click(screen.getByRole('tab', { name: 'Spillere og passord' }));
    await user.click(await screen.findByRole('button', { name: 'Deaktiver Ola' }));
    expect(api.post).toHaveBeenCalledWith('/api/admin/users/3/disable');
  });

  it('shows how the server sees this browser', async () => {
    const { user } = open();
    await user.click(screen.getByRole('tab', { name: 'Tilkobling' }));
    const list = await screen.findByText('Brukes for grenser');
    expect(within(list.closest('dl')!).getByText('203.0.113.7')).toBeInTheDocument();
  });
});
