import { useEffect } from 'react';

/**
 * Stands in for react-chessboard in tests: jsdom has no layout, which the real board needs to size
 * its squares. Shows the position as data attributes and lets tests make moves with drop().
 */
type DropArgs = { sourceSquare: string; targetSquare: string | null; piece: { pieceType: string } };
type Options = { id?: string; position?: string; boardOrientation?: string; onPieceDrop?: (args: DropArgs) => boolean; arrows?: unknown[] };

const handlers = new Map<string, (args: DropArgs) => boolean>();

export function Chessboard({ options }: { options: Options }) {
  const id = options.id ?? 'board';
  useEffect(() => {
    if (options.onPieceDrop) handlers.set(id, options.onPieceDrop);
    return () => { handlers.delete(id); };
  }, [id, options.onPieceDrop]);
  return <div data-testid={`chessboard-${id}`} data-fen={options.position} data-orientation={options.boardOrientation}
    data-arrows={(options.arrows ?? []).length} data-draggable={options.onPieceDrop ? 'yes' : 'no'} />;
}

/** Drops a piece on a test board; returns what the page's handler answered. */
export function drop(boardId: string, from: string, to: string, pieceType = 'wP'): boolean {
  const handler = handlers.get(boardId);
  if (!handler) throw new Error(`Board ${boardId} does not accept moves`);
  return handler({ sourceSquare: from, targetSquare: to, piece: { pieceType } });
}
