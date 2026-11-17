import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Button, ErrorNotice, PageTitle, TextField } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import type { User } from '../lib/types';

export default function SettingsPage() {
  const { user, reload, logout } = useAuth();
  const [lichess, setLichess] = useState(user?.lichessUsername ?? '');
  const [chessCom, setChessCom] = useState(user?.chessComUsername ?? '');
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSaved(false);
    try {
      await api.put<User>('/api/users/me/accounts', { lichessUsername: lichess.trim(), chessComUsername: chessCom.trim() });
      await reload();
      setSaved(true);
    } catch (e) {
      setError(e as ApiError);
    }
  }

  return (
    <div className="flex max-w-xl flex-col gap-10">
      <PageTitle>Innstillinger</PageTitle>
      <form onSubmit={save} className="flex flex-col gap-4" noValidate>
        <h2 className="text-xl font-extrabold text-spruce">Kontoer på nett</h2>
        <p className="text-slate">Brukes til å kjenne deg igjen når partier importeres fra Lichess eller Chess.com.</p>
        <TextField label="Brukernavn på Lichess" value={lichess} onChange={(e) => setLichess(e.target.value)} error={error?.fieldErrors.lichessUsername} />
        <TextField label="Brukernavn på Chess.com" value={chessCom} onChange={(e) => setChessCom(e.target.value)} error={error?.fieldErrors.chessComUsername} />
        <ErrorNotice error={error} />
        {saved && <p role="status" className="text-sm text-moss">Lagret.</p>}
        <div><Button type="submit" variant="primary">Lagre</Button></div>
      </form>
      <section className="flex flex-col gap-3">
        <h2 className="text-xl font-extrabold text-spruce">Passord og innlogging</h2>
        <div className="flex flex-wrap gap-3">
          <Link to="/change-password" className="rounded-md border border-mist bg-white px-4 py-2 font-semibold hover:border-spruce">Bytt passord</Link>
          <Button variant="quiet" onClick={() => void logout()}>Logg ut</Button>
        </div>
      </section>
    </div>
  );
}
