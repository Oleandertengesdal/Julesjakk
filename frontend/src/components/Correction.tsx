import { useState } from 'react';
import { api, ApiError } from '../lib/api';
import { formatDateTime } from '../lib/format';
import type { Game, GameResult } from '../lib/types';
import { Button, ErrorNotice, TextArea } from './ui';

const RESULTS: ReadonlyArray<readonly [GameResult, string, string]> = [
  ['WHITE_WIN', 'Hvit vant', '1–0'], ['DRAW', 'Remis', '½–½'], ['BLACK_WIN', 'Svart vant', '0–1'],
];

/** Tells everyone that an admin changed or annulled the game after it was confirmed, and why. */
export function CorrectionNote({ game }: { game: Game }) {
  if (!game.correction) return null;
  const { by, at, reason } = game.correction;
  const annulled = game.status === 'ANNULLED';
  return (
    <p className={`mx-auto mt-3 max-w-prose rounded-md border-l-4 px-4 py-2 text-sm ${annulled ? 'border-berry bg-white' : 'border-walnut bg-white'}`}>
      <span className="font-semibold">
        {annulled ? `Annullert av ${by.displayName}` : `Resultatet ble rettet av ${by.displayName}`}
      </span>
      {' '}{formatDateTime(at)}: {reason}
      {annulled && <span className="block text-slate">Partiet teller ikke i rating, turneringer eller statistikk.</span>}
    </p>
  );
}

/**
 * Admin tool on a confirmed game: change the result or annul the game. The server replays every
 * rating in the category, so later games and other players are put right as well.
 */
export function CorrectionPanel({ game, onChange }: { game: Game; onChange: (game: Game) => void }) {
  const [result, setResult] = useState<GameResult | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const hasReason = reason.trim().length > 0;

  async function send(path: string, body: Record<string, unknown>) {
    setBusy(true);
    setError(null);
    try {
      onChange(await api.post<Game>(`/api/admin/games/${game.id}/${path}`, body));
      setReason('');
      setResult(null);
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }

  function annul() {
    if (window.confirm('Annullere partiet? Det tas ut av rating, turneringer og statistikk, og ratingen regnes ut på nytt.')) {
      void send('annul', { reason: reason.trim() });
    }
  }

  return (
    <section aria-labelledby="rett-resultat" className="rounded-lg border border-mist bg-white p-5">
      <h2 id="rett-resultat" className="text-xl font-extrabold text-spruce">Rett resultat</h2>
      <p className="mt-1 text-sm text-slate">
        For administratorer. Ratingen i {game.category === 'BLITZ' ? 'lynsjakk' : game.category === 'RAPID' ? 'hurtigsjakk' : 'langsjakk'} regnes
        ut på nytt fra første parti, så senere partier og andre spillere blir også riktige.
      </p>
      <fieldset className="mt-4">
        <legend className="mb-2 text-sm font-semibold">Riktig resultat</legend>
        <div className="grid grid-cols-3 gap-2">
          {RESULTS.map(([value, label, mark]) => {
            const current = value === game.result;
            const chosen = value === result;
            return (
              <label key={value} className={`rounded-md border px-3 py-2 text-center has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-berry ${current ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} ${chosen ? 'border-spruce bg-spruce text-snow' : 'border-mist hover:border-spruce'}`}>
                <input type="radio" name="corrected-result" aria-label={label} value={value} checked={chosen} disabled={current}
                  onChange={() => setResult(value)} className="sr-only" />
                <span className="block text-lg font-extrabold tabular">{mark}</span>
                <span className="text-sm">{current ? 'Nå' : label}</span>
              </label>
            );
          })}
        </div>
      </fieldset>
      <div className="mt-4">
        <TextArea label="Begrunnelse" rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)}
          hint="Vises på partisiden for alle." />
      </div>
      <div className="mt-4 flex flex-wrap gap-3">
        <Button variant="primary" disabled={busy || !result || !hasReason} onClick={() => result && void send('correct', { result, reason: reason.trim() })}>
          Lagre rettelse
        </Button>
        <Button disabled={busy || !hasReason} onClick={annul}>Annuller partiet</Button>
      </div>
      <div className="mt-3"><ErrorNotice error={error} /></div>
    </section>
  );
}
