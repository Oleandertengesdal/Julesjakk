import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AccessList } from './AccessList';
import type { User } from '../lib/types';

const ratings = { BLITZ: { rating: 1200, ratedGames: 0 }, RAPID: { rating: 1200, ratedGames: 0 }, CLASSICAL: { rating: 1200, ratedGames: 0 } };
function user(id: number, displayName: string, extra: Partial<User> = {}): User {
  return { id, username: displayName.toLowerCase(), displayName, role: 'PLAYER', managed: false, enabled: true, mustChangePassword: false,
    chessComUsername: null, lichessUsername: null, ratings, ...extra };
}

describe('AccessList', () => {
  const users = [user(1, 'Admin', { role: 'ADMIN' }), user(2, 'Kari'), user(3, 'Ola', { enabled: false }), user(4, 'Lillebror', { managed: true })];

  it('lists accounts with logins, with their state, and leaves out yourself and players without a login', () => {
    render(<AccessList users={users} currentUserId={1} onToggle={vi.fn()} />);
    const rows = screen.getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('Kari')).toBeInTheDocument();
    expect(within(rows[0]).getByRole('button', { name: 'Deaktiver Kari' })).toBeInTheDocument();
    expect(within(rows[1]).getByText('Deaktivert')).toBeInTheDocument();
    expect(within(rows[1]).getByRole('button', { name: 'Aktiver Ola' })).toBeInTheDocument();
  });

  it('asks before shutting someone out, then reports the choice', async () => {
    const onToggle = vi.fn().mockResolvedValue(undefined);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    render(<AccessList users={users} currentUserId={1} onToggle={onToggle} />);
    await userEvent.click(screen.getByRole('button', { name: 'Deaktiver Kari' }));
    expect(onToggle).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Deaktiver Kari' }));
    expect(onToggle).toHaveBeenCalledWith(2, false);
    await userEvent.click(screen.getByRole('button', { name: 'Aktiver Ola' }));
    expect(onToggle).toHaveBeenCalledWith(3, true);
    expect(confirm).toHaveBeenCalledTimes(2);
  });
});
