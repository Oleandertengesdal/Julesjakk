import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { Button, Empty, ErrorNotice, Loading, PageTitle, SelectField, TextField } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { CATEGORY_LABEL, categoryForTimeControl, DRAW_RESOLUTION_LABEL, formatDate, FORMAT_LABEL, TOURNAMENT_STATUS_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';
import type { Category, Tournament, TournamentFormat, TournamentSummary } from '../lib/types';

const FORMAT_HELP: Record<TournamentFormat, string> = {
  ROUND_ROBIN: 'Alle møter alle én gang. Best for få spillere.',
  DOUBLE_ROUND_ROBIN: 'Alle møter alle to ganger, én gang med hver farge.',
  SWISS: 'Spillere med lik poengsum møtes. Passer når dere er mange og har dårlig tid.',
  SINGLE_ELIMINATION: 'Taperen er ute. Remis avgjøres etter regelen du velger.',
  DOUBLE_ELIMINATION: 'Du er ute etter to tap. Taperne spiller videre i en egen gruppe.',
  ARENA: 'Spill så mange partier dere vil innenfor tidsrommet. Tre seire på rad gir dobbelt poeng.',
};

function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function CreateTournament() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [format, setFormat] = useState<TournamentFormat>('ROUND_ROBIN');
  const [timeControl, setTimeControl] = useState('10+5');
  const [category, setCategory] = useState<Category>('RAPID');
  const [drawResolution, setDrawResolution] = useState('BLITZ_PLAYOFF');
  const [rounds, setRounds] = useState('');
  const [startsAt, setStartsAt] = useState(toLocalInput(new Date()));
  const [endsAt, setEndsAt] = useState(toLocalInput(new Date(Date.now() + 2 * 3600_000)));
  const [error, setError] = useState<ApiError | null>(null);
  const derived = timeControl.trim() ? categoryForTimeControl(timeControl) : null;
  const knockout = format === 'SINGLE_ELIMINATION' || format === 'DOUBLE_ELIMINATION';

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const created = await api.post<Tournament>('/api/tournaments', {
        name,
        format,
        category: derived ?? category,
        timeControl: timeControl.trim() || undefined,
        drawResolution: knockout ? drawResolution : undefined,
        rounds: format === 'SWISS' && rounds ? Number(rounds) : undefined,
        startsAt: format === 'ARENA' ? new Date(startsAt).toISOString() : undefined,
        endsAt: format === 'ARENA' ? new Date(endsAt).toISOString() : undefined,
      });
      navigate(`/tournaments/${created.id}`);
    } catch (e) {
      setError(e as ApiError);
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4 rounded-lg border border-mist bg-white p-5" noValidate>
      <h2 className="text-xl font-extrabold text-spruce">Ny turnering</h2>
      <TextField label="Navn" value={name} onChange={(e) => setName(e.target.value)} placeholder="Julecupen 2026" required error={error?.fieldErrors.name} />
      <SelectField label="Format" value={format} onChange={(e) => setFormat(e.target.value as TournamentFormat)} hint={FORMAT_HELP[format]}>
        {(Object.keys(FORMAT_LABEL) as TournamentFormat[]).map((f) => <option key={f} value={f}>{FORMAT_LABEL[f]}</option>)}
      </SelectField>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Tidskontroll" value={timeControl} onChange={(e) => setTimeControl(e.target.value)} hint={derived ? `Teller som ${CATEGORY_LABEL[derived].toLowerCase()}.` : 'Tomt betyr uten klokke.'} />
        {!derived && (
          <SelectField label="Kategori" value={category} onChange={(e) => setCategory(e.target.value as Category)}>
            {(['BLITZ', 'RAPID', 'CLASSICAL'] as Category[]).map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
          </SelectField>
        )}
      </div>
      {knockout && (
        <SelectField label="Hvis et parti ender remis" value={drawResolution} onChange={(e) => setDrawResolution(e.target.value)}>
          {Object.entries(DRAW_RESOLUTION_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </SelectField>
      )}
      {format === 'SWISS' && <TextField label="Antall runder" type="number" min={1} max={30} value={rounds} onChange={(e) => setRounds(e.target.value)} hint="Tomt gir anbefalt antall for antall spillere." />}
      {format === 'ARENA' && (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Starter" type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} />
          <TextField label="Slutter" type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
        </div>
      )}
      <ErrorNotice error={error} />
      <div><Button type="submit" variant="primary" disabled={!name.trim()}>Opprett turnering</Button></div>
    </form>
  );
}

export default function TournamentsPage() {
  const { user } = useAuth();
  const list = useApi<TournamentSummary[]>('/api/tournaments');
  const [creating, setCreating] = useState(false);

  return (
    <div className="flex flex-col gap-8">
      <PageTitle aside={user?.role === 'ADMIN' && !creating && <Button variant="primary" onClick={() => setCreating(true)}>Ny turnering</Button>}>
        Turneringer
      </PageTitle>
      {creating && <div className="max-w-2xl"><CreateTournament /></div>}
      <ErrorNotice error={list.error} />
      {list.loading && !list.data && <Loading />}
      {list.data?.length === 0 && <Empty>Ingen turneringer ennå.{user?.role === 'ADMIN' ? ' Opprett den første.' : ' En administrator kan opprette en.'}</Empty>}
      <ul className="divide-y divide-mist border-y border-mist">
        {list.data?.map((t) => (
          <li key={t.id}>
            <Link to={`/tournaments/${t.id}`} className="flex flex-wrap items-baseline justify-between gap-2 px-2 py-4 hover:bg-white">
              <span>
                <span className="text-lg font-extrabold text-spruce">{t.name}</span>
                <span className="block text-sm text-slate">{FORMAT_LABEL[t.format]}, {CATEGORY_LABEL[t.category].toLowerCase()}{t.timeControl ? ` ${t.timeControl}` : ''}, {t.participantCount} spillere</span>
              </span>
              <span className={`text-sm font-semibold ${t.status === 'ACTIVE' ? 'text-berry' : 'text-slate'}`}>
                {TOURNAMENT_STATUS_LABEL[t.status]}{t.startsAt ? `, ${formatDate(t.startsAt)}` : ''}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
