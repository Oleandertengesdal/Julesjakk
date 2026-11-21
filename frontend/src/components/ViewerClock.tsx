import { useEffect, useState } from 'react';
import { formatClock, remainingFromView, type ServerClock, type Side } from '../lib/clock';

/**
 * One player's time as the TV or game page sees it: the phone at the board reported it, and this
 * counts the running side down from that moment.
 */
export function PlayerClock({ clock, side, size = 'md' }: { clock: ServerClock | undefined; side: Side; size?: 'md' | 'xl' }) {
  const [receivedAt, setReceivedAt] = useState(() => performance.now());
  const [, setTick] = useState(0);

  useEffect(() => {
    setReceivedAt(performance.now());
  }, [clock]);

  useEffect(() => {
    if (!clock?.running) return;
    const timer = setInterval(() => setTick((n) => n + 1), 100);
    return () => clearInterval(timer);
  }, [clock]);

  if (!clock) return null;
  const left = remainingFromView(clock, receivedAt, performance.now());
  const ms = side === 'WHITE' ? left.white : left.black;
  const running = clock.running === side;
  const low = ms < 20_000;
  const text = size === 'xl' ? 'text-5xl sm:text-6xl' : 'text-2xl';
  return (
    <span role="timer" aria-label={`${side === 'WHITE' ? 'Hvits' : 'Svarts'} klokke${running ? ', går' : ''}`}
      className={`inline-flex items-center gap-2 rounded-md px-3 py-1 font-extrabold tabular ${text} ${
        running ? 'bg-birch text-ink' : 'bg-transparent opacity-80'}`}>
      {running && <span aria-hidden className="h-2.5 w-2.5 rounded-full bg-berry" />}
      <span className={low ? 'text-berry' : ''}>{formatClock(ms)}</span>
    </span>
  );
}
