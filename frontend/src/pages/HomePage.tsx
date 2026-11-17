import { useState } from 'react';
import { Link } from 'react-router';
import { Board } from '../components/Board';
import { GameList } from '../components/GameList';
import { PendingGames } from '../components/PendingGames';
import { Empty, ErrorNotice, LiveDot, Loading, Tabs } from '../components/ui';
import { START_FEN } from '../lib/chess';
import { CATEGORY_LABEL, FORMAT_LABEL, resultMark, TOURNAMENT_STATUS_LABEL } from '../lib/format';
import { useTopic } from '../lib/live';
import { useApi } from '../lib/useApi';
import { useAuth } from '../lib/auth';
import type { Category, Game, GameListItem, LeaderboardRow, TournamentSummary, TvState } from '../lib/types';

function Hero() {
  const tv = useApi<TvState>('/api/tv');
  useTopic<TvState>('/topic/tv', (state) => tv.setData(state));
  const game = tv.data?.game;
  const last = game?.moves.at(-1);

  return (
    <section className="grid items-center gap-8 md:grid-cols-[minmax(0,26rem)_1fr]">
      <Link to={game ? `/matches/${game.id}` : '/games/new'} className="block" aria-label={game ? 'Åpne partiet' : 'Registrer et parti'}>
        <Board fen={game?.currentFen ?? START_FEN} lastMoveUci={last?.uci} id="hero-board" />
      </Link>
      <div>
        {game ? (
          <>
            {game.status === 'LIVE' ? <LiveDot label="Spilles nå" /> : <p className="text-sm font-semibold text-slate">Siste parti</p>}
            <h1 className="mt-3 text-4xl font-extrabold leading-tight text-spruce sm:text-5xl">
              {game.white.displayName}
              <span className="block text-walnut">mot {game.black.displayName}</span>
            </h1>
            <p className="mt-4 text-lg text-slate">
              {CATEGORY_LABEL[game.category]}
              {game.status === 'LIVE' ? `, trekk ${Math.ceil(game.plyCount / 2) || 1}` : `, endte ${resultMark(game.result)}`}
            </p>
            <Link to={`/matches/${game.id}`} className="mt-6 inline-block rounded-md bg-berry px-5 py-2.5 font-semibold text-white hover:bg-berry-dark">
              {game.status === 'LIVE' ? 'Følg partiet' : 'Se gjennom partiet'}
            </Link>
          </>
        ) : (
          <>
            <h1 className="text-4xl font-extrabold text-spruce sm:text-5xl">Brettet står klart</h1>
            <p className="mt-4 text-lg text-slate">Ingen partier er registrert ennå. Spill det første, og før det inn her.</p>
            <Link to="/games/new" className="mt-6 inline-block rounded-md bg-berry px-5 py-2.5 font-semibold text-white hover:bg-berry-dark">Registrer et parti</Link>
          </>
        )}
      </div>
    </section>
  );
}

function Leaderboard() {
  const [category, setCategory] = useState<Category>('RAPID');
  const board = useApi<LeaderboardRow[]>(`/api/leaderboard?category=${category}`);
  return (
    <section aria-labelledby="rangering">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 id="rangering" className="text-xl font-extrabold text-spruce">Rangering</h2>
        <Tabs label="Kategori" value={category} onChange={setCategory}
          options={(['BLITZ', 'RAPID', 'CLASSICAL'] as Category[]).map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))} />
      </div>
      {board.data && board.data.length === 0 && <Empty>Ingen ratede partier i {CATEGORY_LABEL[category].toLowerCase()} ennå.</Empty>}
      <ol className="divide-y divide-mist">
        {board.data?.slice(0, 6).map((row) => (
          <li key={row.user.id} className="flex items-center gap-3 py-2">
            <span className="w-6 text-right font-extrabold tabular text-walnut">{row.rank}</span>
            <Link to={`/players/${row.user.id}`} className="flex-1 font-semibold hover:underline">{row.user.displayName}</Link>
            <span className="font-extrabold tabular">{row.rating}</span>
          </li>
        ))}
      </ol>
      <Link to="/players" className="mt-3 inline-block text-sm font-semibold text-spruce underline">Alle spillere</Link>
    </section>
  );
}

export function HomePage() {
  const { user } = useAuth();
  const pending = useApi<Game[]>('/api/games/pending');
  const recent = useApi<GameListItem[]>('/api/games?limit=8');
  const tournaments = useApi<TournamentSummary[]>('/api/tournaments');
  const open = tournaments.data?.filter((t) => t.status === 'ACTIVE' || t.status === 'DRAFT') ?? [];

  return (
    <div className="flex flex-col gap-12">
      <Hero />

      {pending.data && pending.data.length > 0 && (
        <section aria-labelledby="venter" className="border-l-4 border-berry pl-5">
          <h2 id="venter" className="text-xl font-extrabold text-spruce">Venter på deg</h2>
          <p className="mb-3 text-slate">Motstanderen har registrert disse resultatene. De teller først når du har bekreftet.</p>
          <PendingGames games={pending.data} onChange={() => { void pending.reload(); void recent.reload(); }} />
        </section>
      )}

      <div className="grid gap-12 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <section aria-labelledby="siste">
          <div className="mb-3 flex items-baseline justify-between">
            <h2 id="siste" className="text-xl font-extrabold text-spruce">Siste partier</h2>
            <Link to="/games/new" className="text-sm font-semibold text-spruce underline">Registrer parti</Link>
          </div>
          <ErrorNotice error={recent.error} />
          {recent.loading && !recent.data && <Loading />}
          {recent.data?.length === 0 && <Empty>Ingen partier ennå. <Link to="/games/new" className="font-semibold underline">Registrer det første.</Link></Empty>}
          {recent.data && recent.data.length > 0 && <GameList games={recent.data} highlightUser={user?.id} />}
        </section>
        <div className="flex flex-col gap-10">
          <Leaderboard />
          {open.length > 0 && (
            <section aria-labelledby="turneringer">
              <h2 id="turneringer" className="mb-3 text-xl font-extrabold text-spruce">Turneringer</h2>
              <ul className="divide-y divide-mist">
                {open.map((t) => (
                  <li key={t.id} className="py-2">
                    <Link to={`/tournaments/${t.id}`} className="font-semibold hover:underline">{t.name}</Link>
                    <p className="text-sm text-slate">{FORMAT_LABEL[t.format]}, {TOURNAMENT_STATUS_LABEL[t.status].toLowerCase()}, {t.participantCount} spillere</p>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
