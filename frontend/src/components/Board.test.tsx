import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { START_FEN } from '../lib/chess';
import { Board } from './Board';
import { EvalBar } from './EvalBar';

describe('Board', () => {
  it('renders the position with a label for screen readers', () => {
    render(<Board fen={START_FEN} id="test-board" label="Startstillingen" lastMoveUci="e2e4" />);
    expect(screen.getByLabelText('Startstillingen')).toBeInTheDocument();
    expect(screen.getByTestId('chessboard-test-board')).toHaveAttribute('data-fen', START_FEN);
    expect(screen.getByTestId('chessboard-test-board')).toHaveAttribute('data-draggable', 'no');
  });

  it('passes moves to the page and only allows them where the page accepts moves', async () => {
    const { drop } = await import('../test/boardMock');
    const onMove = vi.fn(() => true);
    render(<Board fen={START_FEN} id="play" onMove={onMove} />);
    expect(drop('play', 'e2', 'e4', 'wP')).toBe(true);
    expect(onMove).toHaveBeenCalledWith('e2', 'e4', 'wP');
  });
});

describe('EvalBar', () => {
  it('shows the evaluation from White\'s side, and mate as M', () => {
    const { rerender } = render(<EvalBar score={{ cp: 150 }} />);
    expect(screen.getByText('1.5')).toBeInTheDocument();
    rerender(<EvalBar score={{ mate: -3 }} />);
    expect(screen.getByText('M3')).toBeInTheDocument();
    rerender(<EvalBar score={null} />);
  });
});
