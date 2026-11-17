import { useState } from 'react';
import { useParams } from 'react-router';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ProblemScreen } from '../components/ErrorScreens';
import { GameList } from '../components/GameList';
import { MatchupCard } from '../components/MatchupCard';
import { PlayerPicker } from '../components/PlayerPicker';
import { Empty, Form, Loading, PlayerLink, Tabs } from '../components/ui';
import { useAuth } from '../lib/auth';
import { CATEGORY_LABEL, decimal, formatDate } from '../lib/format';
import { useApi } from '../lib/useApi';
import type { Category, GameListItem, Matchup, PlayerStats, RatingPoint, User } from '../lib/types';

const CATEGORIES: Category[] = ['BLITZ', 'RAPID', 'CLASSICAL'];

export default function PlayerPage() {
  const { id } = useParams();
  const { user: me } = useAuth();
  const [category, setCategory] = useState<Category>('RAPID');
  const stats = useApi<PlayerStats>(`/api/users/${id}/stats`);
  const history = useApi<RatingPoint[]>(`/api/users/${id}/rating-history?category=${category}`);
  const games = useApi<GameListItem[]>(`/api/users/${id}/games?limit=15`);

  if (stats.error) return <ProblemScreen error={stats.error} />;
  if (!stats.data) return <Loading label="Henter spilleren" />;
  const s = stats.data;
  const pct = (part: number, whole: number) => (whole === 0 ? 0 : Math.round((part / whole) * 100));

  return (
    <div className="flex flex-col gap-12">
      <header>
        <h1 className="text-4xl font-extrabold text-spruce sm:text-5xl">{s.user.displayName}</h1>
        <p className="text-slate">@{s.user.username}</p>
        <dl className="mt-6 grid max-w-2xl grid-cols-3 gap-4">
          {CATEGORIES.map((c) => (
            <div key={c} className={`border-l-4 pl-3 ${c === category ? 'border-berry' : 'border-mist'}`}>
              <dt className="text-sm text-slate">{CATEGORY_LABEL[c]}</dt>
              <dd className="text-3xl font-extrabold tabular sm:text-4xl">{s.ratings[c].rating}</dd>
              <dd className="text-xs text-slate">Topp {s.ratings[c].peak}, {s.ratings[c].ratedGames} partier</dd>
            </div>
          ))}
        </dl>
      </header>

      <section aria-labelledby="ratingutvikling">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 id="ratingutvikling" className="text-xl font-extrabold text-spruce">Ratingutvikling</h2>
          <Tabs label="Kategori" value={category} onChange={setCategory} options={CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))} />
        </div>
        {history.data && history.data.length > 1 ? (
          <div className="h-64" aria-label={`Graf over rating i ${CATEGORY_LABEL[category].toLowerCase()}`}>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={history.data.map((p, i) => ({ ...p, index: i }))} margin={{ top: 8, right: 8, bottom: 0, left: -12 }}>
                <CartesianGrid stroke="#dde3df" vertical={false} />
                <XAxis dataKey="index" tick={false} axisLine={{ stroke: '#dde3df' }} />
                <YAxis domain={['dataMin - 20', 'dataMax + 20']} tick={{ fontSize: 12, fill: '#56635d' }} allowDecimals={false} />
                <Tooltip formatter={(value) => [value, 'Rating']} labelFormatter={(index) => formatDate(history.data![Number(index)]?.at)} />
                <Line type="monotone" dataKey="rating" stroke="#17332B" strokeWidth={2.5} dot={{ r: 3, fill: '#B3243A', stroke: 'none' }} isAnimationActive={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <Empty>Ingen ratede partier i {CATEGORY_LABEL[category].toLowerCase()} ennå.</Empty>
        )}
      </section>

      <section aria-labelledby="statistikk" className="grid gap-10 md:grid-cols-2">
        <div>
          <h2 id="statistikk" className="mb-3 text-xl font-extrabold text-spruce">Resultater</h2>
          {s.games === 0 ? <Empty>Ingen ferdige partier ennå.</Empty> : (
            <>
              <div className="flex h-4 overflow-hidden rounded-full" aria-hidden="true">
                <div className="bg-moss" style={{ width: `${pct(s.wins, s.games)}%` }} />
                <div className="bg-mist" style={{ width: `${pct(s.draws, s.games)}%` }} />
                <div className="bg-berry" style={{ width: `${pct(s.losses, s.games)}%` }} />
              </div>
              <p className="mt-2">{s.games} partier: {s.wins} seire, {s.draws} remis, {s.losses} tap</p>
              <table className="mt-4 w-full text-sm tabular">
                <thead><tr className="text-left text-slate"><th className="font-semibold">Farge</th><th className="font-semibold">Partier</th><th className="font-semibold">S-R-T</th><th className="font-semibold">Seier %</th></tr></thead>
                <tbody className="divide-y divide-mist">
                  {([['Hvit', s.asWhite], ['Svart', s.asBlack]] as const).map(([label, r]) => (
                    <tr key={label}><th scope="row" className="py-1.5 text-left font-semibold">{label}</th><td>{r.games}</td><td>{r.wins}-{r.draws}-{r.losses}</td><td>{pct(r.wins, r.games)} %</td></tr>
                  ))}
                </tbody>
              </table>
              <div className="mt-4 flex items-center gap-3"><span className="text-sm text-slate">Form</span><Form results={s.recentForm} /></div>
            </>
          )}
        </div>
        <dl className="grid grid-cols-2 content-start gap-x-6 gap-y-5">
          <Stat label="Lengste seiersrekke" value={String(s.longestWinStreak)} />
          <Stat label="Nåværende rekke" value={s.currentStreak ? `${s.currentStreak.length} ${s.currentStreak.type === 'WIN' ? 'seire' : s.currentStreak.type === 'DRAW' ? 'remis' : 'tap'}` : '–'} />
          <Stat label="Snittnøyaktighet" value={s.averageAccuracy !== null ? `${s.averageAccuracy} %` : '–'} note={s.analysedGames ? `${s.analysedGames} analyserte partier` : 'Ingen analyserte partier'} />
          <Stat label="Bukker per parti" value={s.analysedGames ? String(s.blundersPerGame) : '–'} />
          <div><dt className="text-sm text-slate">Erkefiende</dt><dd>{s.nemesis ? <><PlayerLink player={s.nemesis.user} /> <span className="text-sm text-slate">{decimal(s.nemesis.score)} av {s.nemesis.games}</span></> : '–'}</dd></div>
          <div><dt className="text-sm text-slate">Oftest motstander</dt><dd>{s.favouriteOpponent ? <><PlayerLink player={s.favouriteOpponent.user} /> <span className="text-sm text-slate tabular">{s.favouriteOpponent.games} partier</span></> : '–'}</dd></div>
        </dl>
      </section>

      {s.badges.length > 0 && (
        <section aria-labelledby="merker">
          <h2 id="merker" className="mb-3 text-xl font-extrabold text-spruce">Merker</h2>
          <ul className="flex flex-wrap gap-3">
            {s.badges.map((b) => (
              <li key={b.code} className="rounded-md bg-spruce px-4 py-2 text-snow">
                <p className="font-extrabold">{b.title}</p>
                <p className="text-sm text-birch">{b.description}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      {me && String(me.id) !== id && <HeadToHead a={me.id} b={Number(id)} category={category} />}
      {me && String(me.id) === id && <Compare me={me.id} category={category} />}

      <section aria-labelledby="partier">
        <h2 id="partier" className="mb-3 text-xl font-extrabold text-spruce">Siste partier</h2>
        {games.data?.length === 0 && <Empty>Ingen partier ennå.</Empty>}
        {games.data && games.data.length > 0 && <GameList games={games.data} highlightUser={Number(id)} />}
      </section>
    </div>
  );
}

function Stat({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div>
      <dt className="text-sm text-slate">{label}</dt>
      <dd className="text-2xl font-extrabold tabular">{value}</dd>
      {note && <dd className="text-xs text-slate">{note}</dd>}
    </div>
  );
}

function HeadToHead({ a, b, category }: { a: number; b: number; category: Category }) {
  const matchup = useApi<Matchup>(`/api/matchups?a=${a}&b=${b}&category=${category}`);
  return matchup.data ? (
    <section aria-labelledby="innbyrdes">
      <h2 id="innbyrdes" className="mb-3 text-xl font-extrabold text-spruce">Du mot {matchup.data.playerB.displayName}</h2>
      <MatchupCard matchup={matchup.data} />
    </section>
  ) : null;
}

function Compare({ me, category }: { me: number; category: Category }) {
  const users = useApi<User[]>('/api/users');
  const [other, setOther] = useState<number | ''>('');
  return (
    <section aria-labelledby="sammenlign">
      <h2 id="sammenlign" className="mb-3 text-xl font-extrabold text-spruce">Sammenlign med</h2>
      <div className="max-w-xs"><PlayerPicker label="Motstander" users={users.data ?? []} value={other} onChange={setOther} exclude={me} /></div>
      {other !== '' && <div className="mt-4"><HeadToHead a={me} b={other} category={category} /></div>}
    </section>
  );
}
