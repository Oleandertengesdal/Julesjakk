import { useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { PlayerPicker } from '../components/PlayerPicker';
import { Button, ErrorNotice, PageTitle, SelectField, Tabs, TextArea, TextField } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { CATEGORY_LABEL, categoryForTimeControl, TERMINATION_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';
import type { Category, Game, GameResult, Termination, User } from '../lib/types';

type Mode = 'result' | 'pgn' | 'live';

const TERMINATIONS: Termination[] = ['CHECKMATE', 'RESIGNATION', 'TIMEOUT', 'AGREEMENT', 'STALEMATE', 'THREEFOLD_REPETITION', 'INSUFFICIENT_MATERIAL', 'FORFEIT', 'UNKNOWN'];

export default function NewGamePage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const users = useApi<User[]>('/api/users');
  const [mode, setMode] = useState<Mode>('result');
  const [white, setWhite] = useState<number | ''>(user?.id ?? '');
  const [black, setBlack] = useState<number | ''>('');
  const [result, setResult] = useState<GameResult | ''>('');
  const [termination, setTermination] = useState<Termination | ''>('');
  const [timeControl, setTimeControl] = useState('');
  const [category, setCategory] = useState<Category>('CLASSICAL');
  const [pgn, setPgn] = useState('');
  const [comment, setComment] = useState('');
  const [rated, setRated] = useState(true);
  const [withClock, setWithClock] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  // One key per form: a double-tap on "Registrer" creates one game, not two.
  const idempotencyKey = useMemo(() => crypto.randomUUID(), []);

  const derived = timeControl.trim() ? categoryForTimeControl(timeControl) : null;
  const effectiveCategory = derived ?? category;
  const timeControlError = timeControl.trim() && !derived ? 'Skriv minutter og eventuelt tillegg i sekunder, for eksempel 5+3.' : undefined;
  const isAdmin = user?.role === 'ADMIN';
  const involvesMe = white === user?.id || black === user?.id;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const common = { whiteId: white, blackId: black, rated, category: effectiveCategory, timeControl: timeControl.trim() || undefined };
      if (mode === 'live') {
        const game = await api.post<Game>('/api/games/live', common);
        navigate(withClock && derived ? `/matches/${game.id}/klokke` : `/matches/${game.id}`);
        return;
      }
      const game = await api.post<Game>('/api/games', {
        ...common,
        result,
        termination: termination || undefined,
        pgn: mode === 'pgn' ? pgn : undefined,
        comment: comment || undefined,
      }, { 'Idempotency-Key': idempotencyKey });
      navigate(`/matches/${game.id}`);
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }

  const ready = white !== '' && black !== '' && !timeControlError && (mode === 'live' || result !== '') && (mode !== 'pgn' || pgn.trim() !== '');

  return (
    <div className="max-w-2xl">
      <PageTitle aside={<Link to="/games/import" className="text-sm font-semibold text-spruce underline">Importer fra Lichess eller Chess.com</Link>}>
        Registrer parti
      </PageTitle>
      <Tabs label="Hvordan vil du registrere?" value={mode} onChange={setMode} options={[
        { value: 'result', label: 'Bare resultatet' },
        { value: 'pgn', label: 'Lim inn PGN' },
        { value: 'live', label: 'Før trekkene underveis' },
      ]} />
      <p className="mt-3 text-slate">
        {mode === 'result' && 'Raskest etter et parti ved brettet.'}
        {mode === 'pgn' && 'Trekkene kontrolleres, og partiet kan analyseres med Stockfish etterpå.'}
        {mode === 'live' && 'Spill med sjakkur på telefonen, og før gjerne inn trekkene underveis. Tilskuere og TV-skjermen følger med.'}
      </p>

      <form onSubmit={submit} className="mt-6 flex flex-col gap-5" noValidate>
        <div className="grid gap-4 sm:grid-cols-2">
          <PlayerPicker label="Hvit" users={users.data ?? []} value={white} onChange={setWhite} exclude={black} />
          <PlayerPicker label="Svart" users={users.data ?? []} value={black} onChange={setBlack} exclude={white} />
        </div>
        {!isAdmin && white !== '' && black !== '' && !involvesMe && (
          <p className="text-sm font-semibold text-berry">Du kan bare registrere partier du har spilt selv.</p>
        )}

        {mode !== 'live' && (
          <fieldset>
            <legend className="mb-2 text-sm font-semibold">Resultat</legend>
            <div className="grid grid-cols-3 gap-2">
              {([['WHITE_WIN', 'Hvit vant', '1–0'], ['DRAW', 'Remis', '½–½'], ['BLACK_WIN', 'Svart vant', '0–1']] as const).map(([value, label, mark]) => (
                <label key={value} className={`cursor-pointer rounded-md border px-3 py-3 text-center ${result === value ? 'border-spruce bg-spruce text-snow' : 'border-mist bg-white hover:border-spruce'}`}>
                  <input type="radio" name="result" value={value} checked={result === value} onChange={() => setResult(value)} className="sr-only" />
                  <span className="block text-xl font-extrabold tabular">{mark}</span>
                  <span className="text-sm">{label}</span>
                </label>
              ))}
            </div>
          </fieldset>
        )}

        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Tidskontroll" placeholder="For eksempel 5+3" value={timeControl} onChange={(e) => setTimeControl(e.target.value)}
            hint={derived ? `Teller som ${CATEGORY_LABEL[derived].toLowerCase()}.` : 'Minutter + tillegg i sekunder. Tomt betyr uten klokke.'} error={timeControlError} />
          <SelectField label="Kategori" value={effectiveCategory} disabled={!!derived} onChange={(e) => setCategory(e.target.value as Category)}
            hint={derived ? 'Bestemt av tidskontrollen.' : undefined}>
            {(['BLITZ', 'RAPID', 'CLASSICAL'] as Category[]).map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
          </SelectField>
        </div>

        {mode === 'pgn' && (
          <TextArea label="PGN" rows={8} value={pgn} onChange={(e) => setPgn(e.target.value)} placeholder={'1. e4 e5 2. Nf3 Nc6 ...'} error={error?.code.startsWith('CHESS-PGN') ? error.detail : undefined} />
        )}

        {mode !== 'live' && (
          <div className="grid gap-4 sm:grid-cols-2">
            <SelectField label="Hvordan endte partiet?" value={termination} onChange={(e) => setTermination(e.target.value as Termination)}>
              <option value="">Ikke oppgitt</option>
              {TERMINATIONS.map((t) => <option key={t} value={t}>{TERMINATION_LABEL[t]}</option>)}
            </SelectField>
            <TextField label="Kommentar" value={comment} maxLength={1000} onChange={(e) => setComment(e.target.value)} />
          </div>
        )}

        <label className="flex items-center gap-2">
          <input type="checkbox" checked={rated} onChange={(e) => setRated(e.target.checked)} className="h-4 w-4 accent-spruce" />
          Ratet parti
        </label>

        {mode === 'live' && derived && (
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={withClock} onChange={(e) => setWithClock(e.target.checked)} className="h-4 w-4 accent-spruce" />
            Bruk denne telefonen som sjakkur
          </label>
        )}

        {!isAdmin && mode !== 'live' && <p className="text-sm text-slate">Motstanderen må bekrefte resultatet før det teller.</p>}
        <ErrorNotice error={error} />
        <div>
          <Button type="submit" variant="primary" disabled={!ready || busy}>
            {mode === 'live' ? 'Start partiet' : busy ? 'Registrerer…' : 'Registrer parti'}
          </Button>
        </div>
      </form>
    </div>
  );
}
