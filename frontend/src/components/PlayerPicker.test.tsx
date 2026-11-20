import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PlayerPicker } from './PlayerPicker';
import type { User } from '../lib/types';

const ratings = { BLITZ: { rating: 1200, ratedGames: 0 }, RAPID: { rating: 1200, ratedGames: 0 }, CLASSICAL: { rating: 1200, ratedGames: 0 } };
const user = (id: number, displayName: string, enabled = true): User => ({ id, username: displayName.toLowerCase(), displayName, role: 'PLAYER',
  managed: false, enabled, mustChangePassword: false, chessComUsername: null, lichessUsername: null, ratings });

describe('PlayerPicker', () => {
  it('never offers disabled accounts (admins receive them in the user list)', () => {
    render(<PlayerPicker label="Hvit" users={[user(1, 'Kari'), user(2, 'Ola', false)]} value="" onChange={() => {}} />);
    expect(screen.getByRole('option', { name: 'Kari' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Ola' })).toBeNull();
  });
});
