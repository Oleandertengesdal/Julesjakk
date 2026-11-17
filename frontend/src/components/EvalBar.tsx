import { winPercent } from '../analysis/evaluation';
import type { Score } from '../analysis/uci';

/** Vertical bar: birch for White's share of the winning chances, walnut for Black's. */
export function EvalBar({ score, orientation = 'white', className = '' }: { score: Score | null; orientation?: 'white' | 'black'; className?: string }) {
  const white = score ? winPercent(score) : 50;
  const label = score ? scoreLabel(score) : '…';
  const whiteOnBottom = orientation === 'white';
  return (
    <div
      className={`relative flex w-6 flex-col overflow-hidden rounded-sm bg-walnut ${whiteOnBottom ? 'justify-end' : 'justify-start'} ${className}`}
      role="meter"
      aria-label="Stilling"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(white)}
      aria-valuetext={label}
    >
      <div className="w-full bg-birch transition-[height] duration-500" style={{ height: `${white}%` }} />
      <span className={`absolute left-0 right-0 text-center text-[10px] font-bold tabular ${white >= 50 ? (whiteOnBottom ? 'bottom-1 text-walnut' : 'top-1 text-walnut') : (whiteOnBottom ? 'top-1 text-birch' : 'bottom-1 text-birch')}`}>
        {label}
      </span>
    </div>
  );
}

export function scoreLabel(score: Score): string {
  if ('mate' in score) return Object.is(score.mate, -0) || score.mate === 0 ? '#' : `M${Math.abs(score.mate)}`;
  const pawns = Math.abs(score.cp) / 100;
  return pawns >= 10 ? pawns.toFixed(0) : pawns.toFixed(1);
}
