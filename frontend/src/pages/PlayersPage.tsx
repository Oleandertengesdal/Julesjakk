import { useState } from 'react';
import { Link } from 'react-router';
import { Empty, ErrorNotice, Loading, PageTitle, Tabs } from '../components/ui';
import { CATEGORY_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';
import type { Category, LeaderboardRow, User } from '../lib/types';

export default function PlayersPage() {
  const [category, setCategory] = useState<Category>('RAPID');
  const board = useApi<LeaderboardRow[]>(`/api/leaderboard?category=${category}`);
  const users = useApi<User[]>('/api/users');
  const unranked = (users.data ?? []).filter((u) => u.enabled && !board.data?.some((row) => row.user.id === u.id));

  return (
    <div className="flex flex-col gap-8">
      <PageTitle aside={<Tabs label="Kategori" value={category} onChange={setCategory}
        options={(['BLITZ', 'RAPID', 'CLASSICAL'] as Category[]).map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))} />}>
        Spillere
      </PageTitle>
      <ErrorNotice error={board.error ?? users.error} />
      {board.loading && !board.data && <Loading />}
      {board.data && board.data.length === 0 && <Empty>Ingen har spilt ratede partier i {CATEGORY_LABEL[category].toLowerCase()} ennå.</Empty>}
      {board.data && board.data.length > 0 && (
        <table className="w-full max-w-3xl tabular">
          <caption className="sr-only">Rangering i {CATEGORY_LABEL[category].toLowerCase()}</caption>
          <thead>
            <tr className="border-b-2 border-spruce text-left text-sm">
              <th className="py-2 pr-3 font-semibold">#</th>
              <th className="py-2 pr-3 font-semibold">Spiller</th>
              <th className="py-2 pr-3 text-right font-semibold">Rating</th>
              <th className="py-2 pr-3 text-right font-semibold">Toppnotering</th>
              <th className="py-2 text-right font-semibold">Partier</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-mist">
            {board.data.map((row) => (
              <tr key={row.user.id}>
                <td className="py-3 pr-3 text-lg font-extrabold text-walnut">{row.rank}</td>
                <td className="py-3 pr-3"><Link to={`/players/${row.user.id}`} className="text-lg font-semibold hover:underline">{row.user.displayName}</Link></td>
                <td className="py-3 pr-3 text-right text-xl font-extrabold">{row.rating}</td>
                <td className="py-3 pr-3 text-right text-slate">{row.peak}</td>
                <td className="py-3 text-right text-slate">{row.ratedGames}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {unranked.length > 0 && (
        <section>
          <h2 className="mb-2 font-semibold text-slate">Uten ratede partier i {CATEGORY_LABEL[category].toLowerCase()}</h2>
          <p className="flex flex-wrap gap-x-4 gap-y-1">
            {unranked.map((u) => <Link key={u.id} to={`/players/${u.id}`} className="hover:underline">{u.displayName}</Link>)}
          </p>
        </section>
      )}
    </div>
  );
}
