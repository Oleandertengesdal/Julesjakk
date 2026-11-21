import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerClock } from '../lib/clock';
import { PlayerClock } from './ViewerClock';

let now = 1000;
const clock: ServerClock = { initialMs: 300_000, incrementMs: 0, whiteMs: 125_000, blackMs: 90_000, running: 'WHITE', sequence: 3,
  updatedAt: '2026-12-24T18:00:00.000Z', serverNow: '2026-12-24T18:00:00.000Z' };

beforeEach(() => {
  now = 1000;
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] });
  vi.spyOn(performance, 'now').mockImplementation(() => now);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function tick(ms: number) {
  now += ms;
  act(() => { vi.advanceTimersByTime(ms); });
}

describe('PlayerClock', () => {
  it('counts down the side whose clock is running', () => {
    render(<><PlayerClock clock={clock} side="WHITE" /><PlayerClock clock={clock} side="BLACK" /></>);
    expect(screen.getByText('2:05')).toBeInTheDocument();
    expect(screen.getByText('1:30')).toBeInTheDocument();
    tick(5000);
    expect(screen.getByText('2:00')).toBeInTheDocument();
    expect(screen.getByText('1:30')).toBeInTheDocument();
  });

  it('marks the running clock for screen readers and sighted viewers', () => {
    render(<PlayerClock clock={clock} side="WHITE" />);
    expect(screen.getByRole('timer')).toHaveAccessibleName('Hvits klokke, går');
  });

  it('shows tenths and a warning colour in the last twenty seconds', () => {
    render(<PlayerClock clock={{ ...clock, whiteMs: 25_000 }} side="WHITE" />);
    tick(6000);
    const face = screen.getByText('19.0');
    expect(face.className).toContain('text-berry');
  });

  it('starts counting from a new press when the clock is updated', () => {
    const { rerender } = render(<PlayerClock clock={clock} side="BLACK" />);
    tick(10_000);
    rerender(<PlayerClock clock={{ ...clock, running: 'BLACK', whiteMs: 115_000, sequence: 4 }} side="BLACK" />);
    tick(3000);
    expect(screen.getByText('1:27')).toBeInTheDocument();
  });

  it('shows nothing when the game has no clock', () => {
    const { container } = render(<PlayerClock clock={undefined} side="WHITE" />);
    expect(container).toBeEmptyDOMElement();
  });
});
