import { Chessboard, type Arrow, type PieceDropHandlerArgs } from 'react-chessboard';
import { lastMoveSquares } from '../lib/chess';

interface BoardProps {
  fen: string;
  orientation?: 'white' | 'black';
  lastMoveUci?: string;
  arrows?: Arrow[];
  /** Return true when the move was accepted. Omit for a read-only board. */
  onMove?: (from: string, to: string, piece: string) => boolean;
  id?: string;
  label?: string;
}

/** The wooden board: birch and walnut squares, lingonberry glow on the last move. */
export function Board({ fen, orientation = 'white', lastMoveUci, arrows = [], onMove, id = 'board', label = 'Sjakkbrett' }: BoardProps) {
  const last = lastMoveSquares(lastMoveUci);
  const highlight = { backgroundColor: 'rgba(179, 36, 58, 0.38)' };
  const squareStyles = last ? { [last[0]]: highlight, [last[1]]: highlight } : {};

  return (
    <div role="img" aria-label={label} className="aspect-square w-full overflow-hidden rounded-sm shadow-[0_0_0_6px_#4a3122,0_0_0_8px_#2b1d14]">
      <Chessboard
        options={{
          id,
          position: fen,
          boardOrientation: orientation,
          lightSquareStyle: { backgroundColor: '#E6D5B0' },
          darkSquareStyle: { backgroundColor: '#8A6244' },
          lightSquareNotationStyle: { color: '#8A6244', fontWeight: 700 },
          darkSquareNotationStyle: { color: '#E6D5B0', fontWeight: 700 },
          squareStyles,
          arrows,
          allowDragging: !!onMove,
          allowDrawingArrows: false,
          animationDurationInMs: 180,
          onPieceDrop: onMove
            ? ({ sourceSquare, targetSquare, piece }: PieceDropHandlerArgs) =>
                targetSquare ? onMove(sourceSquare, targetSquare, piece.pieceType) : false
            : undefined,
        }}
      />
    </div>
  );
}
