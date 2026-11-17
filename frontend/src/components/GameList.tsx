import { Link } from 'react-router';
import { CATEGORY_LABEL, formatDate, resultMark, STATUS_LABEL } from '../lib/format';
import type { GameListItem } from '../lib/types';
import { Delta, LiveDot } from './ui';

/** Games as rows: white, result, black. The row links to the match page. */
export function GameList({ games, highlightUser }: { games: GameListItem[]; highlightUser?: number }) {
  return (
    <ul className="divide-y divide-mist border-y border-mist">
      {games.map((game) => (
        <li key={game.id}>
          <Link to={`/matches/${game.id}`} className="grid grid-cols-[1fr_auto_1fr] items-center gap-3 px-2 py-3 hover:bg-white sm:grid-cols-[1fr_auto_1fr_11rem]">
            <span className={`truncate text-right ${game.white.id === highlightUser ? 'font-bold' : ''}`}>
              {game.white.displayName} <Delta value={game.whiteRatingDelta} className="text-sm" />
            </span>
            <span className="min-w-14 text-center font-extrabold tabular">
              {game.status === 'LIVE' ? <LiveDot label="" /> : resultMark(game.result)}
            </span>
            <span className={`truncate ${game.black.id === highlightUser ? 'font-bold' : ''}`}>
              {game.black.displayName} <Delta value={game.blackRatingDelta} className="text-sm" />
            </span>
            <span className="col-span-3 text-sm text-slate sm:col-span-1 sm:text-right">
              {game.status === 'FINISHED' ? `${CATEGORY_LABEL[game.category]}, ${formatDate(game.playedAt)}` : STATUS_LABEL[game.status]}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
