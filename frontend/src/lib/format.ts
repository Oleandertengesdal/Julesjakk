import type { Category, GameResult, GameStatus, Termination, TournamentFormat, TournamentStatus } from './types';

export const CATEGORY_LABEL: Record<Category, string> = { BLITZ: 'Lyn', RAPID: 'Hurtig', CLASSICAL: 'Langsjakk' };

export const STATUS_LABEL: Record<GameStatus, string> = {
  SCHEDULED: 'Ikke spilt', LIVE: 'Pågår', PENDING: 'Venter på bekreftelse', REJECTED: 'Avvist', FINISHED: 'Ferdig', ABORTED: 'Avbrutt', ANNULLED: 'Annullert',
};

export const TERMINATION_LABEL: Record<Termination, string> = {
  CHECKMATE: 'sjakkmatt', RESIGNATION: 'oppgitt', TIMEOUT: 'tiden gikk ut', STALEMATE: 'patt',
  THREEFOLD_REPETITION: 'trekkgjentakelse', FIFTY_MOVE_RULE: '50-trekksregelen', INSUFFICIENT_MATERIAL: 'for lite materiell',
  AGREEMENT: 'enighet', FORFEIT: 'walkover', UNKNOWN: 'annet',
};

export const FORMAT_LABEL: Record<TournamentFormat, string> = {
  ROUND_ROBIN: 'Alle mot alle', DOUBLE_ROUND_ROBIN: 'Alle mot alle, dobbel', SWISS: 'Monrad (sveitsisk)',
  SINGLE_ELIMINATION: 'Utslag', DOUBLE_ELIMINATION: 'Dobbel utslag', ARENA: 'Arena',
};

export const TOURNAMENT_STATUS_LABEL: Record<TournamentStatus, string> = {
  DRAFT: 'Påmelding åpen', ACTIVE: 'Pågår', FINISHED: 'Ferdig', CANCELLED: 'Avlyst',
};

export const DRAW_RESOLUTION_LABEL: Record<string, string> = {
  REPLAY: 'Nytt parti med byttede farger',
  BLITZ_PLAYOFF: 'To lynpartier, deretter armageddon',
  ARMAGEDDON: 'Armageddon',
};

export const CLASSIFICATION_LABEL: Record<string, string> = {
  BRILLIANT: 'Briljant', GREAT: 'Sterkt', BEST: 'Beste trekk', EXCELLENT: 'Utmerket', GOOD: 'Godt', BOOK: 'Teori',
  INACCURACY: 'Unøyaktighet', MISTAKE: 'Feil', BLUNDER: 'Bukk',
};

/** "1–0", "½–½", "0–1" with proper dashes. */
export function resultMark(result: GameResult | undefined | null): string {
  if (result === 'WHITE_WIN') return '1–0';
  if (result === 'BLACK_WIN') return '0–1';
  if (result === 'DRAW') return '½–½';
  return '–';
}

/** Signed rating change with a real minus sign: "+12", "−8", "±0". */
export function signed(delta: number | null | undefined): string {
  if (delta === null || delta === undefined) return '';
  if (delta === 0) return '±0';
  return delta > 0 ? `+${delta}` : `−${Math.abs(delta)}`;
}

const dateFormat = new Intl.DateTimeFormat('nb-NO', { day: 'numeric', month: 'short', year: 'numeric' });
const shortDateFormat = new Intl.DateTimeFormat('nb-NO', { day: 'numeric', month: 'short' });
const dateTimeFormat = new Intl.DateTimeFormat('nb-NO', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

/** "17. nov." this year, "17. nov. 2025" otherwise. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  return date.getFullYear() === new Date().getFullYear() ? shortDateFormat.format(date) : dateFormat.format(date);
}

/** Norwegian decimal comma, at most two decimals: 0,25 */
export function decimal(value: number): string {
  return value.toLocaleString('nb-NO', { maximumFractionDigits: 2 });
}

export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTimeFormat.format(new Date(iso)) : '';
}

/**
 * Same rule as the server: minutes + increment seconds, under 10 is blitz, under 60 rapid, else classical.
 * Returns null for text that is not a time control.
 */
export function categoryForTimeControl(timeControl: string): Category | null {
  const compact = timeControl.replace(/\s/g, '');
  if (compact === '') return 'CLASSICAL';
  const match = /^([0-9]{1,3})(?:\+([0-9]{1,3}))?$/.exec(compact);
  if (!match) return null;
  const minutes = Number(match[1]);
  const increment = match[2] ? Number(match[2]) : 0;
  if (minutes < 1 || minutes > 600 || increment > 180) return null;
  const estimate = minutes + increment;
  return estimate < 10 ? 'BLITZ' : estimate < 60 ? 'RAPID' : 'CLASSICAL';
}

/** Rounds percentages so that they always add up to exactly 100 (largest remainder method). */
export function percentages(values: number[]): number[] {
  const scaled = values.map((v) => v * 100);
  const floored = scaled.map(Math.floor);
  let missing = 100 - floored.reduce((a, b) => a + b, 0);
  const order = scaled.map((v, i) => [v - Math.floor(v), i] as const).sort((a, b) => b[0] - a[0]);
  for (const [, index] of order) {
    if (missing <= 0) break;
    floored[index]++;
    missing--;
  }
  return floored;
}
