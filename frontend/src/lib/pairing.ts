import { resultMark, STATUS_LABEL } from './format';
import type { Pairing } from './types';

const TIE_BREAK_LABEL: Record<string, string> = { PLAYOFF: 'Lynomspill', ARMAGEDDON: 'Armageddon', MAIN: 'Omspill' };

/** The middle column of a pairing row: a result, a status, or which tie-break game is due after a draw. */
export function pairingLabel(pairing: Pairing): string {
  const game = pairing.currentGame;
  if (!game) return '';
  const decided = game.status === 'FINISHED' || game.status === 'PENDING';
  if (pairing.games.length > 1 && !decided) {
    const name = TIE_BREAK_LABEL[game.stage] ?? 'Omspill';
    return game.status === 'LIVE' ? `${name} pågår` : name;
  }
  const label = decided ? resultMark(game.result) : STATUS_LABEL[game.status];
  return pairing.games.length > 1 ? `${label} (${pairing.games.length} partier)` : label;
}
