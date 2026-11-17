import { useState, type FormEvent } from 'react';
import { Button, Empty, ErrorNotice, PageTitle, SelectField, Tabs, TextField } from '../components/ui';
import { PendingGames } from '../components/PendingGames';
import { PlayerPicker } from '../components/PlayerPicker';
import { api, ApiError } from '../lib/api';
import { formatDateTime } from '../lib/format';
import { useApi } from '../lib/useApi';
import type { Game, Invite, User } from '../lib/types';

type Section = 'pending' | 'invites' | 'players' | 'connection';

export default function AdminPage() {
  const [section, setSection] = useState<Section>('pending');
  return (
    <div className="flex flex-col gap-8">
      <PageTitle>Administrasjon</PageTitle>
      <Tabs label="Område" value={section} onChange={setSection} options={[
        { value: 'pending', label: 'Ubekreftede partier' },
        { value: 'invites', label: 'Invitasjoner' },
        { value: 'players', label: 'Spillere og passord' },
        { value: 'connection', label: 'Tilkobling' },
      ]} />
      {section === 'pending' && <AllPending />}
      {section === 'invites' && <Invites />}
      {section === 'players' && <Players />}
      {section === 'connection' && <Connection />}
    </div>
  );
}

function AllPending() {
  const pending = useApi<Game[]>('/api/admin/games/pending');
  return (
    <section>
      <p className="mb-3 text-slate">Alle resultater som venter på at motstanderen bekrefter. Som administrator kan du bekrefte eller avvise dem.</p>
      <ErrorNotice error={pending.error} />
      {pending.data?.length === 0 && <Empty>Ingen partier venter på bekreftelse.</Empty>}
      {pending.data && pending.data.length > 0 && <PendingGames games={pending.data} onChange={pending.reload} showReporter />}
    </section>
  );
}

const STATUS: Record<Invite['status'], string> = { ACTIVE: 'Aktiv', EXPIRED: 'Utløpt', EXHAUSTED: 'Brukt opp', REVOKED: 'Trukket tilbake' };

function Invites() {
  const invites = useApi<Invite[]>('/api/admin/invites');
  const [code, setCode] = useState('');
  const [maxUses, setMaxUses] = useState('1');
  const [expiry, setExpiry] = useState('168');
  const [note, setNote] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  async function create(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await api.post('/api/admin/invites', {
        code: code.trim() || undefined,
        maxUses: maxUses === 'unlimited' ? undefined : Number(maxUses),
        expiresInHours: expiry === 'never' ? undefined : Number(expiry),
        note: note || undefined,
      });
      setCode('');
      setNote('');
      void invites.reload();
    } catch (e) {
      setError(e as ApiError);
    }
  }

  async function copyLink(inviteCode: string) {
    await navigator.clipboard.writeText(`${window.location.origin}/register?kode=${encodeURIComponent(inviteCode)}`);
    setCopied(inviteCode);
  }

  return (
    <div className="grid gap-10 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <form onSubmit={create} className="flex flex-col gap-4" noValidate>
        <h2 className="text-xl font-extrabold text-spruce">Ny invitasjon</h2>
        <TextField label="Kode" value={code} onChange={(e) => setCode(e.target.value)} placeholder="Tomt gir en tilfeldig kode" hint="Egen kode: bokstaver, tall og bindestrek, minst 6 tegn." />
        <SelectField label="Antall bruk" value={maxUses} onChange={(e) => setMaxUses(e.target.value)}>
          <option value="1">1 person</option><option value="5">5 personer</option><option value="20">20 personer</option><option value="unlimited">Ubegrenset (familietreff)</option>
        </SelectField>
        <SelectField label="Gyldig i" value={expiry} onChange={(e) => setExpiry(e.target.value)}>
          <option value="24">24 timer</option><option value="168">7 dager</option><option value="720">30 dager</option><option value="never">Uten utløp</option>
        </SelectField>
        <TextField label="Notat" value={note} onChange={(e) => setNote(e.target.value)} placeholder="For eksempel Julaften hos besteforeldrene" />
        <ErrorNotice error={error} />
        <div><Button type="submit" variant="primary">Lag invitasjon</Button></div>
      </form>
      <section>
        <h2 className="mb-3 text-xl font-extrabold text-spruce">Invitasjoner</h2>
        {invites.data?.length === 0 && <Empty>Ingen invitasjoner ennå.</Empty>}
        <ul className="divide-y divide-mist border-y border-mist">
          {invites.data?.map((invite) => (
            <li key={invite.id} className="py-3">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="text-lg font-extrabold tabular tracking-wide">{invite.code}</span>
                <span className={`text-sm font-semibold ${invite.status === 'ACTIVE' ? 'text-moss' : 'text-slate'}`}>{STATUS[invite.status]}</span>
              </div>
              <p className="text-sm text-slate">
                {invite.maxUses === null ? `Brukt ${invite.useCount} ganger, ubegrenset` : `${invite.remainingUses} av ${invite.maxUses} igjen`}
                {invite.expiresAt ? `, utløper ${formatDateTime(invite.expiresAt)}` : ', uten utløp'}
                {invite.note ? `. ${invite.note}` : ''}
              </p>
              {invite.redemptions.length > 0 && <p className="text-sm">Brukt av {invite.redemptions.map((r) => r.displayName).join(', ')}</p>}
              {invite.status === 'ACTIVE' && (
                <div className="mt-2 flex gap-3 text-sm">
                  <button type="button" className="font-semibold text-spruce underline" onClick={() => copyLink(invite.code)}>{copied === invite.code ? 'Lenke kopiert' : 'Kopier lenke'}</button>
                  <button type="button" className="font-semibold text-berry underline" onClick={async () => {
                    if (!window.confirm(`Trekke tilbake ${invite.code}? Den slutter å virke med en gang.`)) return;
                    await api.delete(`/api/admin/invites/${invite.id}`);
                    void invites.reload();
                  }}>Trekk tilbake</button>
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function Players() {
  const users = useApi<User[]>('/api/users');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [target, setTarget] = useState<number | ''>('');
  const [mode, setMode] = useState<'TEMPORARY_PASSWORD' | 'RESET_LINK'>('RESET_LINK');
  const [issued, setIssued] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [created, setCreated] = useState<string | null>(null);

  async function createManaged(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const player = await api.post<User>('/api/admin/players', { username, displayName });
      setCreated(`${player.displayName} er lagt til. Du registrerer og bekrefter partiene for denne spilleren.`);
      setUsername('');
      setDisplayName('');
      void users.reload();
    } catch (e) {
      setError(e as ApiError);
    }
  }

  async function reset() {
    setError(null);
    setIssued(null);
    try {
      const result = await api.post<{ temporaryPassword?: string; resetToken?: string; expiresAt?: string }>(`/api/admin/users/${target}/password-reset`, { mode });
      setIssued(result.temporaryPassword
        ? `Midlertidig passord: ${result.temporaryPassword}. Spilleren må velge nytt passord ved neste innlogging.`
        : `${window.location.origin}/reset-password?token=${result.resetToken}`);
    } catch (e) {
      setError(e as ApiError);
    }
  }

  return (
    <div className="grid gap-10 lg:grid-cols-2">
      <section className="flex flex-col gap-4">
        <h2 className="text-xl font-extrabold text-spruce">Tilbakestill passord</h2>
        <PlayerPicker label="Spiller" users={(users.data ?? []).filter((u) => !u.managed)} value={target} onChange={setTarget} />
        <SelectField label="Hvordan" value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
          <option value="RESET_LINK">Engangslenke (gyldig i 24 timer)</option>
          <option value="TEMPORARY_PASSWORD">Midlertidig passord</option>
        </SelectField>
        <div><Button variant="primary" disabled={target === ''} onClick={reset}>Lag</Button></div>
        {issued && (
          <div role="status" className="rounded-md border-l-4 border-moss bg-white px-4 py-3">
            <p className="break-all font-semibold">{issued}</p>
            <p className="mt-1 text-sm text-slate">Vises bare nå. Alle innlogginger for spilleren er avsluttet.</p>
          </div>
        )}
      </section>
      <form onSubmit={createManaged} className="flex flex-col gap-4" noValidate>
        <h2 className="text-xl font-extrabold text-spruce">Spiller uten innlogging</h2>
        <p className="text-slate">For barn eller andre som ikke skal ha egen konto. Partiene deres registreres og bekreftes av en administrator.</p>
        <TextField label="Navn som vises" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        <TextField label="Brukernavn" value={username} onChange={(e) => setUsername(e.target.value)} hint="3–32 tegn." error={error?.fieldErrors.username} />
        <div><Button type="submit" variant="primary" disabled={!username || !displayName}>Legg til spiller</Button></div>
        {created && <p role="status" className="text-sm text-moss">{created}</p>}
      </form>
      <div className="lg:col-span-2"><ErrorNotice error={error} /></div>
    </div>
  );
}

interface RequestDiagnostics {
  remoteAddress: string;
  clientAddressUsedForRateLimits: string;
  secure: boolean;
  forwardingHeaders: Record<string, string>;
}

/** How the server sees this browser, for setting SJAKKPORTAL_CLIENT_IP_HEADER (docs/DEPLOYMENT.md). */
function Connection() {
  const diagnostics = useApi<RequestDiagnostics>('/api/admin/diagnostics/request');
  const data = diagnostics.data;
  return (
    <section className="flex max-w-2xl flex-col gap-4">
      <p className="text-slate">
        Slik ser serveren forespørselen din. Brukes én gang etter publisering for å stille inn hvilken adresse
        grensene for innloggingsforsøk teller per. Se «Klientadresse» i docs/DEPLOYMENT.md.
      </p>
      <ErrorNotice error={diagnostics.error} />
      {data && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 rounded-lg border border-mist bg-white p-4 text-sm">
          <dt className="text-slate">Tilkoblingsadresse</dt>
          <dd className="break-all font-semibold">{data.remoteAddress}</dd>
          <dt className="text-slate">Brukes for grenser</dt>
          <dd className="break-all font-semibold">{data.clientAddressUsedForRateLimits}</dd>
          {Object.entries(data.forwardingHeaders).map(([name, value]) => (
            <div key={name} className="contents">
              <dt className="text-slate">{name}</dt>
              <dd className="break-all">{value}</dd>
            </div>
          ))}
        </dl>
      )}
      <div><Button onClick={diagnostics.reload}>Hent på nytt</Button></div>
    </section>
  );
}
