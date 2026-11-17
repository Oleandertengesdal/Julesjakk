import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ProblemScreen } from '../components/ErrorScreens';
import { PlayerPicker } from '../components/PlayerPicker';
import { Button, ErrorNotice, Loading, PlayerLink, SelectField } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { CATEGORY_LABEL, decimal, DRAW_RESOLUTION_LABEL, formatDateTime, FORMAT_LABEL, resultMark, STATUS_LABEL, TOURNAMENT_STATUS_LABEL } from '../lib/format';
import { useTopic } from '../lib/live';
import { useApi } from '../lib/useApi';
import type { BracketMatch, GameResult, Pairing, Round, Standing, Tournament, User } from '../lib/types';

export default function TournamentPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const { data: t, setData, error, loading } = useApi<Tournament>(`/api/tournaments/${id}`);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  useTopic<{ type: string; tournament: Tournament }>(`/topic/tournaments/${id}`, (m) => m.type === 'tournament' && setData(m.tournament));

  if (error) return <ProblemScreen error={error} />;
  if (loading && !t) return <Loading label="Henter turneringen" />;
  if (!t || !user) return null;

  const isAdmin = user.role === 'ADMIN';
  const joined = t.participants.some((p) => p.user.id === user.id);
  const knockout = t.format === 'SINGLE_ELIMINATION' || t.format === 'DOUBLE_ELIMINATION';

  async function act(path: string, body: object = {}) {
    setActionError(null);
    try {
      setData(await api.post<Tournament>(`/api/tournaments/${t!.id}/${path}`, body));
    } catch (e) {
      setActionError(e as ApiError);
    }
  }

  return (
    <div className="flex flex-col gap-10">
      <header>
        <p className="text-sm font-semibold text-slate">
          <span className={t.status === 'ACTIVE' ? 'text-berry' : ''}>{TOURNAMENT_STATUS_LABEL[t.status]}</span>
          {', '}{FORMAT_LABEL[t.format]}, {CATEGORY_LABEL[t.category].toLowerCase()}{t.timeControl ? ` ${t.timeControl}` : ''}{t.rated ? '' : ', uratet'}
        </p>
        <h1 className="mt-1 text-4xl font-extrabold text-spruce">{t.name}</h1>
        {t.champion && <p className="mt-3 text-2xl font-extrabold text-walnut">♔ {t.champion.displayName} vant</p>}
        <dl className="mt-3 flex flex-wrap gap-x-8 gap-y-1 text-sm">
          {t.totalRounds && <div><dt className="inline text-slate">Runde </dt><dd className="inline font-semibold tabular">{Math.min(t.currentRound, t.totalRounds)} av {t.totalRounds}</dd></div>}
          {t.drawResolution && <div><dt className="inline text-slate">Remis i utslag: </dt><dd className="inline font-semibold">{DRAW_RESOLUTION_LABEL[t.drawResolution]}</dd></div>}
          {t.format === 'ARENA' && t.startsAt && <div><dt className="inline text-slate">Åpen </dt><dd className="inline font-semibold">{formatDateTime(t.startsAt)} til {formatDateTime(t.endsAt)}</dd></div>}
        </dl>
        <div className="mt-5 flex flex-wrap gap-2">
          {t.status === 'DRAFT' && !joined && <Button variant="primary" onClick={() => act('join')}>Meld meg på</Button>}
          {t.status === 'DRAFT' && joined && <Button variant="quiet" onClick={() => act('leave')}>Meld meg av</Button>}
          {isAdmin && t.status === 'DRAFT' && <Button variant="secondary" disabled={t.participants.length < 2} onClick={() => act('start')}>Start turneringen</Button>}
          {isAdmin && t.status === 'ACTIVE' && t.format === 'ARENA' && <Button variant="secondary" onClick={() => act('finish')}>Avslutt arenaen</Button>}
          {isAdmin && (t.status === 'DRAFT' || t.status === 'ACTIVE') && (
            <Button variant="danger" onClick={() => window.confirm('Avlyse turneringen? Partier som ikke er spilt blir avbrutt.') && act('cancel')}>Avlys</Button>
          )}
        </div>
        <div className="mt-3"><ErrorNotice error={actionError} /></div>
      </header>

      {t.status === 'DRAFT' ? (
        <SignUp tournament={t} isAdmin={isAdmin} onChange={setData} />
      ) : (
        <div className="grid gap-10 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <Standings tournament={t} knockout={knockout} />
          <div className="flex flex-col gap-10">
            {t.format === 'ARENA' && t.status === 'ACTIVE' && <ArenaGame tournament={t} />}
            {knockout && t.bracket && <Bracket matches={t.bracket} />}
            <Rounds rounds={t.rounds} knockout={knockout} arena={t.format === 'ARENA'} />
          </div>
        </div>
      )}
    </div>
  );
}

function SignUp({ tournament, isAdmin, onChange }: { tournament: Tournament; isAdmin: boolean; onChange: (t: Tournament) => void }) {
  const users = useApi<User[]>(isAdmin ? '/api/users' : null);
  const [pick, setPick] = useState<number | ''>('');
  const [error, setError] = useState<ApiError | null>(null);
  const available = (users.data ?? []).filter((u) => !tournament.participants.some((p) => p.user.id === u.id));

  return (
    <section aria-labelledby="pameldte">
      <h2 id="pameldte" className="text-xl font-extrabold text-spruce">Påmeldte ({tournament.participants.length})</h2>
      <p className="text-slate">Seedingen settes etter rating i {CATEGORY_LABEL[tournament.category].toLowerCase()} når turneringen starter.</p>
      <ul className="mt-3 divide-y divide-mist border-y border-mist">
        {tournament.participants.map((p) => (
          <li key={p.id} className="flex items-center justify-between py-2">
            <PlayerLink player={p.user} />
            {isAdmin && (
              <button type="button" className="text-sm text-berry underline" onClick={async () => {
                try { onChange(await api.delete<Tournament>(`/api/tournaments/${tournament.id}/participants/${p.user.id}`)); } catch (e) { setError(e as ApiError); }
              }}>Fjern</button>
            )}
          </li>
        ))}
      </ul>
      {isAdmin && (
        <div className="mt-4 flex max-w-md items-end gap-2">
          <div className="flex-1"><PlayerPicker label="Legg til spiller" users={available} value={pick} onChange={setPick} /></div>
          <Button disabled={pick === ''} onClick={async () => {
            setError(null);
            try { onChange(await api.post<Tournament>(`/api/tournaments/${tournament.id}/participants`, { userId: pick })); setPick(''); } catch (e) { setError(e as ApiError); }
          }}>Legg til</Button>
        </div>
      )}
      <div className="mt-3"><ErrorNotice error={error} /></div>
    </section>
  );
}

function score(value: number): string {
  if (Number.isInteger(value)) return String(value);
  return value < 1 ? '½' : `${Math.floor(value)}½`;
}

function Standings({ tournament, knockout }: { tournament: Tournament; knockout: boolean }) {
  const arena = tournament.format === 'ARENA';
  return (
    <section aria-labelledby="tabell">
      <h2 id="tabell" className="mb-3 text-xl font-extrabold text-spruce">Tabell</h2>
      <div className="overflow-x-auto">
        <table className="w-full text-sm tabular">
          <thead>
            <tr className="border-b-2 border-spruce text-left">
              <th className="py-2 pr-2 font-semibold">#</th>
              <th className="py-2 pr-2 font-semibold">Spiller</th>
              <th className="py-2 pr-2 text-right font-semibold">{knockout ? 'Seire' : 'Poeng'}</th>
              <th className="py-2 pr-2 text-right font-semibold" title="Seier, remis, tap">S-R-T</th>
              {!knockout && !arena && <>
                <th className="py-2 pr-2 text-right font-semibold" title="Poeng mot spillere med samme poengsum">Innb.</th>
                <th className="py-2 pr-2 text-right font-semibold" title="Buchholz: summen av motstandernes poeng">Buchh.</th>
                <th className="py-2 text-right font-semibold" title="Sonneborn-Berger: poeng vektet etter motstandernes styrke">S-B</th>
              </>}
              {arena && <th className="py-2 text-right font-semibold">Rekke</th>}
            </tr>
          </thead>
          <tbody className="divide-y divide-mist">
            {tournament.standings.map((row: Standing) => (
              <tr key={row.user.id} className={row.eliminated ? 'text-slate' : ''}>
                <td className="py-2 pr-2 font-extrabold text-walnut">{row.rank}</td>
                <td className="py-2 pr-2"><PlayerLink player={row.user} />{row.eliminated && <span className="ml-2 text-xs">ute</span>}</td>
                <td className="py-2 pr-2 text-right text-base font-extrabold">{score(row.score)}</td>
                <td className="py-2 pr-2 text-right">{row.wins}-{row.draws}-{row.losses}</td>
                {!knockout && !arena && <>
                  <td className="py-2 pr-2 text-right">{score(row.headToHead)}</td>
                  <td className="py-2 pr-2 text-right">{score(row.buchholz)}</td>
                  <td className="py-2 text-right">{decimal(row.sonnebornBerger)}</td>
                </>}
                {arena && <td className="py-2 text-right">{row.onFire ? <span className="font-extrabold text-berry" title="Dobbelt poeng">{row.streak} på rad</span> : row.streak || ''}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!knockout && !arena && <p className="mt-2 text-xs text-slate">Ved likt: innbyrdes oppgjør, så Buchholz, så Sonneborn-Berger.</p>}
    </section>
  );
}

function PairingRow({ pairing }: { pairing: Pairing }) {
  const game = pairing.currentGame;
  if (pairing.bye) {
    return <li className="py-2 text-slate"><PlayerLink player={pairing.white} /> står over denne runden</li>;
  }
  const label = game ? (game.status === 'FINISHED' || game.status === 'PENDING' ? resultMark(game.result) : STATUS_LABEL[game.status]) : '';
  const tieBreaks = pairing.games.length > 1 ? ` (${pairing.games.length} partier)` : '';
  return (
    <li>
      <Link to={game ? `/matches/${game.id}` : '#'} className="grid grid-cols-[1fr_auto_1fr] items-center gap-3 py-2 hover:bg-white">
        <span className={`truncate text-right ${pairing.advancing?.id === pairing.white?.id ? 'font-extrabold' : ''}`}>{pairing.white?.displayName}</span>
        <span className={`min-w-20 text-center text-sm font-semibold ${game?.status === 'LIVE' ? 'text-berry' : game?.status === 'SCHEDULED' ? 'text-slate' : ''}`}>{label}{tieBreaks}</span>
        <span className={`truncate ${pairing.advancing?.id === pairing.black?.id ? 'font-extrabold' : ''}`}>{pairing.black?.displayName}</span>
      </Link>
    </li>
  );
}

const BRACKET_LABEL: Record<string, string> = { MAIN: 'Runde', WINNERS: 'Vinnersiden, runde', LOSERS: 'Tapersiden, runde', GRAND_FINAL: 'Finale' };

function Rounds({ rounds, knockout, arena }: { rounds: Round[]; knockout: boolean; arena: boolean }) {
  const ordered = [...rounds].reverse();
  return (
    <section aria-labelledby="runder">
      <h2 id="runder" className="mb-3 text-xl font-extrabold text-spruce">{arena ? 'Partier' : knockout ? 'Kamper' : 'Runder'}</h2>
      <ol className="flex flex-col gap-6">
        {ordered.map((round) => (
          <li key={round.id}>
            <h3 className="mb-1 font-semibold text-walnut">
              {arena ? '' : round.bracket === 'GRAND_FINAL' ? (round.number > 1 ? 'Finale, omkamp' : 'Finale') : `${BRACKET_LABEL[round.bracket]} ${round.number}`}
              {round.status === 'ACTIVE' && !arena && <span className="ml-2 text-sm text-berry">pågår</span>}
            </h3>
            <ul className="divide-y divide-mist border-y border-mist">
              {[...round.pairings].sort((a, b) => a.board - b.board).map((p) => <PairingRow key={p.id} pairing={p} />)}
            </ul>
          </li>
        ))}
      </ol>
    </section>
  );
}

function Bracket({ matches }: { matches: BracketMatch[] }) {
  const sides = ['WINNERS', 'LOSERS', 'GRAND_FINAL'].map((side) => ({ side, matches: matches.filter((m) => m.side === side) })).filter((s) => s.matches.length > 0);
  return (
    <section aria-labelledby="sluttspill">
      <h2 id="sluttspill" className="mb-3 text-xl font-extrabold text-spruce">Sluttspill</h2>
      {sides.map(({ side, matches: list }) => {
        const rounds = [...new Set(list.map((m) => m.round))].sort((a, b) => a - b);
        return (
          <div key={side} className="mb-6">
            {sides.length > 1 && <h3 className="mb-2 font-semibold text-walnut">{side === 'WINNERS' ? 'Vinnersiden' : side === 'LOSERS' ? 'Tapersiden' : 'Finale'}</h3>}
            <div className="flex gap-4 overflow-x-auto pb-2">
              {rounds.map((r) => (
                <ol key={r} className="flex min-w-44 flex-col justify-around gap-3">
                  {list.filter((m) => m.round === r).map((m) => (
                    <li key={m.id} className="rounded-md border border-mist bg-white text-sm">
                      {[m.playerA, m.playerB].map((p, i) => (
                        <p key={i} className={`flex justify-between px-3 py-1.5 ${i === 0 ? 'border-b border-mist' : ''} ${m.winner && p && m.winner.id === p.id ? 'font-extrabold' : m.winner ? 'text-slate' : ''}`}>
                          <span className="truncate">{p?.displayName ?? (m.winner ? 'Fri' : '…')}</span>
                          {m.winner && p && m.winner.id === p.id && <span aria-label="videre">✓</span>}
                        </p>
                      ))}
                    </li>
                  ))}
                </ol>
              ))}
            </div>
          </div>
        );
      })}
    </section>
  );
}

function ArenaGame({ tournament }: { tournament: Tournament }) {
  const { user } = useAuth();
  const players = tournament.participants.map((p) => ({ ...p.user, role: 'PLAYER' as const, managed: false, mustChangePassword: false, chessComUsername: null, lichessUsername: null, ratings: {} as User['ratings'] }));
  const [white, setWhite] = useState<number | ''>(user?.id ?? '');
  const [black, setBlack] = useState<number | ''>('');
  const [result, setResult] = useState<GameResult | ''>('');
  const [error, setError] = useState<ApiError | null>(null);
  const [done, setDone] = useState<string | null>(null);

  return (
    <section aria-labelledby="arena-parti" className="rounded-lg border border-mist bg-white p-5">
      <h2 id="arena-parti" className="text-xl font-extrabold text-spruce">Registrer arenaparti</h2>
      <div className="mt-3 grid gap-3 sm:grid-cols-3">
        <PlayerPicker label="Hvit" users={players} value={white} onChange={setWhite} exclude={black} />
        <PlayerPicker label="Svart" users={players} value={black} onChange={setBlack} exclude={white} />
        <SelectField label="Resultat" value={result} onChange={(e) => setResult(e.target.value as GameResult)}>
          <option value="">Velg</option><option value="WHITE_WIN">1–0</option><option value="DRAW">½–½</option><option value="BLACK_WIN">0–1</option>
        </SelectField>
      </div>
      <Button className="mt-3" variant="primary" disabled={white === '' || black === '' || !result} onClick={async () => {
        setError(null);
        setDone(null);
        try {
          await api.post(`/api/tournaments/${tournament.id}/arena-games`, { whiteId: white, blackId: black, result });
          setDone(user?.role === 'ADMIN' ? 'Partiet er registrert.' : 'Partiet er registrert. Det teller når motstanderen har bekreftet.');
          setResult('');
        } catch (e) {
          setError(e as ApiError);
        }
      }}>Registrer</Button>
      {done && <p role="status" className="mt-2 text-sm text-moss">{done}</p>}
      <div className="mt-2"><ErrorNotice error={error} /></div>
    </section>
  );
}
