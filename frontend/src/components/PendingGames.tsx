import { useState } from 'react';
import { Link } from 'react-router';
import { api, ApiError } from '../lib/api';
import { CATEGORY_LABEL, formatDateTime, resultMark } from '../lib/format';
import type { Game } from '../lib/types';
import { Button, ErrorNotice } from './ui';

/** Results waiting for confirmation, with confirm and reject in place. */
export function PendingGames({ games, onChange, showReporter = false }: { games: Game[]; onChange: () => void; showReporter?: boolean }) {
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  async function act(game: Game, action: 'confirm' | 'reject') {
    let body: object = {};
    if (action === 'reject') {
      const reason = window.prompt('Hva er feil med resultatet? (valgfritt)') ?? undefined;
      body = { reason };
    }
    setBusy(game.id);
    setError(null);
    try {
      await api.post(`/api/games/${game.id}/${action}`, body);
      onChange();
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <ErrorNotice error={error} />
      <ul className="divide-y divide-mist border-y border-mist">
        {games.map((game) => (
          <li key={game.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3">
            <Link to={`/matches/${game.id}`} className="min-w-0 flex-1 hover:underline">
              <span className="font-semibold">{game.white.displayName}</span>
              <span className="mx-2 font-extrabold tabular">{resultMark(game.result)}</span>
              <span className="font-semibold">{game.black.displayName}</span>
              <span className="block text-sm text-slate">
                {CATEGORY_LABEL[game.category]}, {formatDateTime(game.playedAt)}
                {showReporter && game.registeredBy ? `, registrert av ${game.registeredBy.displayName}` : ''}
              </span>
            </Link>
            <div className="flex gap-2">
              <Button variant="primary" disabled={busy === game.id} onClick={() => act(game, 'confirm')}>Bekreft</Button>
              <Button variant="quiet" disabled={busy === game.id} onClick={() => act(game, 'reject')}>Avvis</Button>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
