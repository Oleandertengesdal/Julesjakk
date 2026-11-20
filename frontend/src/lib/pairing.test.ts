import { describe, expect, it } from 'vitest';
import { pairingLabel } from './pairing';
import type { Pairing, TournamentGame } from './types';

const player = (id: number, name: string) => ({ id, username: name.toLowerCase(), displayName: name });
function game(id: number, status: TournamentGame['status'], stage: string, result?: TournamentGame['result']): TournamentGame {
  return { id, status, stage, result, category: 'BLITZ', rated: true, white: player(1, 'Mormor'), black: player(2, 'Jonas'), playedAt: '' };
}
function pairing(games: TournamentGame[]): Pairing {
  return { id: 1, board: 1, white: player(1, 'Mormor'), black: player(2, 'Jonas'), bye: false, result: null, advancing: null,
    currentGame: games[games.length - 1], games };
}

describe('pairingLabel', () => {
  it('shows the result of a single decided game', () => {
    expect(pairingLabel(pairing([game(1, 'FINISHED', 'MAIN', 'WHITE_WIN')]))).toBe('1–0');
  });

  it('says a game has not been played yet', () => {
    expect(pairingLabel(pairing([game(1, 'SCHEDULED', 'MAIN')]))).toBe('Ikke spilt');
  });

  it('names the tie-break that is waiting after a draw', () => {
    expect(pairingLabel(pairing([game(1, 'FINISHED', 'MAIN', 'DRAW'), game(2, 'SCHEDULED', 'PLAYOFF')]))).toBe('Lynomspill');
    expect(pairingLabel(pairing([game(1, 'FINISHED', 'MAIN', 'DRAW'), game(2, 'SCHEDULED', 'ARMAGEDDON')]))).toBe('Armageddon');
    expect(pairingLabel(pairing([game(1, 'FINISHED', 'MAIN', 'DRAW'), game(2, 'SCHEDULED', 'MAIN')]))).toBe('Omspill');
    expect(pairingLabel(pairing([game(1, 'FINISHED', 'MAIN', 'DRAW'), game(2, 'LIVE', 'PLAYOFF')]))).toBe('Lynomspill pågår');
  });

  it('counts the games once a tie-break has decided the match', () => {
    expect(pairingLabel(pairing([game(1, 'FINISHED', 'MAIN', 'DRAW'), game(2, 'FINISHED', 'PLAYOFF', 'BLACK_WIN')]))).toBe('0–1 (2 partier)');
  });
});
