import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { Button, ErrorNotice, PageTitle, Tabs, TextArea, TextField } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import type { Game } from '../lib/types';

export default function ImportPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'url' | 'pgn'>('url');
  const [url, setUrl] = useState('');
  const [pgn, setPgn] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const linked = user?.lichessUsername || user?.chessComUsername;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const game = await api.post<Game>('/api/games/import', mode === 'url' ? { url } : { pgn });
      navigate(`/matches/${game.id}`);
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="max-w-2xl">
      <PageTitle>Importer parti</PageTitle>
      <p className="text-slate">
        Partier mellom to i familien kan hentes fra Lichess eller Chess.com for analyse. De påvirker ikke ratingen.
      </p>
      {!linked && (
        <p className="mt-4 rounded-md border-l-4 border-birch bg-white px-4 py-3">
          Koble brukernavnet ditt på Lichess eller Chess.com under <Link to="/settings" className="font-semibold underline">innstillinger</Link> først,
          så vi vet hvem du er i partiet.
        </p>
      )}
      <form onSubmit={submit} className="mt-6 flex flex-col gap-5" noValidate>
        <Tabs label="Kilde" value={mode} onChange={setMode} options={[{ value: 'url', label: 'Lenke til partiet' }, { value: 'pgn', label: 'Lim inn PGN' }]} />
        {mode === 'url' ? (
          <TextField label="Lenke" type="url" inputMode="url" placeholder="https://lichess.org/AbCd1234" value={url} onChange={(e) => setUrl(e.target.value)}
            hint="Lenker fra lichess.org og chess.com/game/live/… fungerer." />
        ) : (
          <TextArea label="PGN fra Lichess eller Chess.com" rows={10} value={pgn} onChange={(e) => setPgn(e.target.value)}
            hint="Ta med hodelinjene ([Site …], [White …]) så partiet kan kjennes igjen." />
        )}
        <ErrorNotice error={error} />
        <div><Button type="submit" variant="primary" disabled={busy || (mode === 'url' ? !url : !pgn)}>{busy ? 'Henter…' : 'Importer'}</Button></div>
      </form>
    </div>
  );
}
