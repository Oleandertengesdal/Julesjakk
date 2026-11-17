import { CATEGORY_LABEL, percentages, signed } from '../lib/format';
import type { Matchup, Stakes as StakesView } from '../lib/types';
import { Form, PlayerLink } from './ui';

/** Pre-match card: head-to-head, recent form, odds and what is at stake. */
export function MatchupCard({ matchup }: { matchup: Matchup }) {
  const { playerA, playerB, headToHead: h2h, probabilities: p } = matchup;
  const [a, draw, b] = percentages([p.aWin, p.draw, p.bWin]);
  return (
    <section aria-labelledby="matchup-title" className="rounded-lg bg-spruce p-5 text-snow sm:p-6">
      <h2 id="matchup-title" className="sr-only">Før partiet</h2>
      <div className="grid grid-cols-[1fr_auto_1fr] items-start gap-4">
        <div>
          <PlayerLink player={playerA} className="text-xl text-snow" />
          <p className="text-3xl font-extrabold tabular text-birch">{matchup.ratingA}</p>
          <div className="mt-2"><Form results={matchup.formA} /></div>
        </div>
        <div className="pt-2 text-center text-sm text-birch">
          {CATEGORY_LABEL[matchup.category]}
          <p className="mt-1 text-2xl font-extrabold tabular text-snow">{h2h.aWins}–{h2h.draws}–{h2h.bWins}</p>
          <p className="text-xs">{h2h.games === 0 ? 'Første møte' : `${h2h.games} innbyrdes partier`}</p>
        </div>
        <div className="text-right">
          <PlayerLink player={playerB} className="text-xl text-snow" />
          <p className="text-3xl font-extrabold tabular text-birch">{matchup.ratingB}</p>
          <div className="mt-2 flex justify-end"><Form results={matchup.formB} /></div>
        </div>
      </div>

      <div className="mt-6">
        <div className="flex h-3 overflow-hidden rounded-full" aria-hidden="true">
          <div className="bg-birch" style={{ width: `${a}%` }} />
          <div className="bg-spruce-3" style={{ width: `${draw}%` }} />
          <div className="bg-walnut" style={{ width: `${b}%` }} />
        </div>
        <p className="mt-2 flex justify-between gap-2 text-sm">
          <span>{playerA.displayName} vinner {a} %</span>
          <span>Remis {draw} %</span>
          <span>{playerB.displayName} vinner {b} %</span>
        </p>
      </div>

      <dl className="mt-5 grid grid-cols-2 gap-4 border-t border-spruce-3 pt-4 text-sm">
        <Stakes name={playerA.displayName} stakes={matchup.stakesA} />
        <Stakes name={playerB.displayName} stakes={matchup.stakesB} alignRight />
      </dl>
    </section>
  );
}

/** Rating points won or lost for each outcome, as three labelled numbers. */
function Stakes({ name, stakes, alignRight = false }: { name: string; stakes: StakesView; alignRight?: boolean }) {
  const cells: [string, number][] = [['Seier', stakes.onWin], ['Remis', stakes.onDraw], ['Tap', stakes.onLoss]];
  return (
    <div className={alignRight ? 'text-right' : ''}>
      <dt className="text-birch">Står på spill for {name}</dt>
      <dd className={`mt-1 flex gap-4 ${alignRight ? 'justify-end' : ''}`}>
        {cells.map(([label, value]) => (
          <span key={label}>
            <span className="block text-xs text-birch">{label}</span>
            <span className="text-lg font-extrabold tabular">{signed(value)}</span>
          </span>
        ))}
      </dd>
    </div>
  );
}
