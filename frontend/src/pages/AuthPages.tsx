import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Button, ErrorNotice, TextField } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';

/** A knight forking king and rook: the illustration on the signed-out pages. */
const LOGIN_PIECES: Record<number, { glyph: string; white: boolean }> = {
  1: { glyph: '♚', white: false },
  3: { glyph: '♜', white: false },
  10: { glyph: '♘', white: true },
  14: { glyph: '♔', white: true },
};

/** Shared frame for the signed-out pages: spruce panel with the wordmark, form on snow. */
function AuthFrame({ title, intro, children }: { title: string; intro?: string; children: ReactNode }) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <aside className="relative hidden overflow-hidden bg-spruce p-12 text-snow lg:flex lg:flex-col lg:justify-between">
        <p className="flex items-center gap-2 text-xl font-extrabold"><span className="text-3xl text-birch" aria-hidden="true">♞</span>Sjakkportal</p>
        <div aria-hidden="true" className="grid w-72 grid-cols-4 overflow-hidden rounded-sm opacity-90 shadow-[0_0_0_6px_#4a3122]">
          {Array.from({ length: 16 }, (_, i) => (
            <div key={i} className={`grid aspect-square place-items-center text-5xl leading-none ${(Math.floor(i / 4) + i) % 2 === 0 ? 'bg-birch' : 'bg-[#8A6244]'}`}>
              {LOGIN_PIECES[i] && <span className={LOGIN_PIECES[i].white ? 'text-snow [text-shadow:0_0_2px_#2b1d14,0_0_1px_#2b1d14]' : 'text-ink'}>{LOGIN_PIECES[i].glyph}</span>}
            </div>
          ))}
        </div>
        <p className="max-w-sm text-birch">Partiene, ratingene og turneringene til familien, samlet på ett brett.</p>
      </aside>
      <main className="flex items-center px-6 py-12 sm:px-12">
        <div className="w-full max-w-sm">
          <p className="mb-8 flex items-center gap-2 text-lg font-extrabold text-spruce lg:hidden"><span aria-hidden="true">♞</span>Sjakkportal</p>
          <h1 className="text-3xl font-extrabold text-spruce">{title}</h1>
          {intro && <p className="mt-2 text-slate">{intro}</p>}
          <div className="mt-8">{children}</div>
        </div>
      </main>
    </div>
  );
}

function useSubmit<T>(action: () => Promise<T>, onDone: (result: T) => void) {
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onDone(await action());
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }
  return { error, busy, submit };
}

/** Only same-site paths are accepted as a redirect target after login. */
function safeNext(next: string | null): string {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
}

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const { error, busy, submit } = useSubmit(() => login(username, password), (user) =>
    navigate(user.mustChangePassword ? '/change-password' : safeNext(params.get('next')), { replace: true }));

  return (
    <AuthFrame title="Logg inn">
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <TextField label="Brukernavn" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
        <TextField label="Passord" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        <ErrorNotice error={error} />
        <Button type="submit" variant="primary" disabled={busy || !username || !password}>{busy ? 'Logger inn…' : 'Logg inn'}</Button>
      </form>
      <p className="mt-6 text-sm text-slate">
        Har du fått en invitasjonskode? <Link to="/register" className="font-semibold text-spruce underline">Opprett konto</Link>
      </p>
      <p className="mt-2 text-sm text-slate">Glemt passordet? Be en administrator om en lenke for å lage nytt.</p>
    </AuthFrame>
  );
}

export function RegisterPage() {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [params] = useSearchParams();
  const [form, setForm] = useState({ inviteCode: params.get('kode') ?? '', username: '', displayName: '', password: '' });
  const set = (field: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [field]: e.target.value });
  const { error, busy, submit } = useSubmit(async () => {
    await api.post('/api/auth/register', form);
    return login(form.username, form.password);
  }, () => navigate('/', { replace: true }));

  return (
    <AuthFrame title="Opprett konto" intro="Du trenger en invitasjonskode fra en administrator i familien.">
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <TextField label="Invitasjonskode" value={form.inviteCode} onChange={set('inviteCode')} autoCapitalize="characters" required error={error?.fieldErrors.inviteCode} />
        <TextField label="Navn som vises" value={form.displayName} onChange={set('displayName')} placeholder="For eksempel Bestefar" required error={error?.fieldErrors.displayName} />
        <TextField label="Brukernavn" value={form.username} onChange={set('username')} autoComplete="username" hint="3–32 tegn: bokstaver, tall, punktum eller bindestrek." required error={error?.fieldErrors.username} />
        <TextField label="Passord" type="password" value={form.password} onChange={set('password')} autoComplete="new-password" hint="Minst 8 tegn." required error={error?.fieldErrors.password} />
        <ErrorNotice error={error} />
        <Button type="submit" variant="primary" disabled={busy}>{busy ? 'Oppretter…' : 'Opprett konto'}</Button>
      </form>
      <p className="mt-6 text-sm text-slate">Har du allerede konto? <Link to="/login" className="font-semibold text-spruce underline">Logg inn</Link></p>
    </AuthFrame>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const [password, setPassword] = useState('');
  const [done, setDone] = useState(false);
  const token = params.get('token') ?? '';
  const { error, busy, submit } = useSubmit(() => api.post('/api/auth/reset-password', { token, newPassword: password }), () => setDone(true));

  return (
    <AuthFrame title="Lag nytt passord">
      {done ? (
        <div className="flex flex-col gap-4">
          <p>Passordet er endret. Du kan logge inn med det nye passordet nå.</p>
          <Link to="/login" className="font-semibold text-spruce underline">Logg inn</Link>
        </div>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
          {!token && <p className="text-berry">Lenken mangler en kode. Be administratoren om en ny lenke.</p>}
          <TextField label="Nytt passord" type="password" autoComplete="new-password" hint="Minst 8 tegn." value={password} onChange={(e) => setPassword(e.target.value)} />
          <ErrorNotice error={error} />
          <Button type="submit" variant="primary" disabled={busy || !token}>Lagre nytt passord</Button>
        </form>
      )}
    </AuthFrame>
  );
}

export function ChangePasswordPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const { error, busy, submit } = useSubmit(() => api.post('/api/auth/change-password', { currentPassword: current, newPassword: next }), async () => {
    // Changing the password ends every session, including this one.
    await logout();
    navigate('/login', { replace: true });
  });

  return (
    <AuthFrame title="Velg nytt passord" intro={user?.mustChangePassword ? 'Du logget inn med et midlertidig passord. Velg ditt eget før du fortsetter.' : undefined}>
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <TextField label="Nåværende passord" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        <TextField label="Nytt passord" type="password" autoComplete="new-password" hint="Minst 8 tegn, ikke likt brukernavnet." value={next} onChange={(e) => setNext(e.target.value)} />
        <ErrorNotice error={error} />
        <Button type="submit" variant="primary" disabled={busy || !current || !next}>Lagre og logg inn på nytt</Button>
      </form>
    </AuthFrame>
  );
}
