import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../lib/api';
import { makeGame, SCHOLARS_MATE } from '../test/fixtures';
import { evaluate, terminate } from '../test/engineMock';
import { useGameAnalysis } from './useGameAnalysis';
import { useLiveEval } from './useLiveEval';

vi.mock('./engine', () => import('../test/engineMock'));

beforeEach(() => { evaluate.mockClear(); terminate.mockClear(); });
afterEach(() => vi.restoreAllMocks());

describe('useGameAnalysis', () => {
  const game = makeGame({ moves: SCHOLARS_MATE, plyCount: 7 });

  it('does not look for an analysis of an unfinished or empty game', () => {
    const get = vi.spyOn(api, 'get');
    renderHook(() => useGameAnalysis(makeGame({ status: 'LIVE' }), false));
    renderHook(() => useGameAnalysis(makeGame({ plyCount: 0, moves: [] }), false));
    expect(get).not.toHaveBeenCalled();
  });

  it('analyses in the browser without saving when the viewer may not save', async () => {
    vi.spyOn(api, 'get').mockRejectedValue(new ApiError(404, 'CHESS-GEN-002', '', null));
    const put = vi.spyOn(api, 'put');
    const { result } = renderHook(() => useGameAnalysis(game, false));
    await act(() => result.current.run());
    expect(result.current.analysis?.moves).toHaveLength(7);
    expect(put).not.toHaveBeenCalled();
    expect(terminate).toHaveBeenCalled();
    expect(result.current.progress).toBeNull();
  });

  it('reports an engine that crashed and still shuts it down', async () => {
    vi.spyOn(api, 'get').mockRejectedValue(new ApiError(404, 'CHESS-GEN-002', '', null));
    evaluate.mockRejectedValueOnce(new Error('worker died'));
    const { result } = renderHook(() => useGameAnalysis(game, true));
    await act(() => result.current.run());
    expect(result.current.error?.code).toBe('CHESS-CLIENT-002');
    expect(terminate).toHaveBeenCalled();
  });
});

describe('useLiveEval', () => {
  it('evaluates each new position only while switched on, and stops the engine after', async () => {
    const { result, rerender, unmount } = renderHook(({ fen, on }) => useLiveEval(fen, on, 10), { initialProps: { fen: SCHOLARS_MATE[0].fen, on: false } });
    expect(result.current).toBeNull();
    expect(evaluate).not.toHaveBeenCalled();
    rerender({ fen: SCHOLARS_MATE[0].fen, on: true });
    await waitFor(() => expect(result.current?.best).toBe('e2e4'));
    rerender({ fen: SCHOLARS_MATE[1].fen, on: true });
    expect(evaluate).toHaveBeenLastCalledWith(SCHOLARS_MATE[1].fen, 10);
    rerender({ fen: SCHOLARS_MATE[1].fen, on: false });
    expect(result.current).toBeNull();
    expect(terminate).toHaveBeenCalled();
    unmount();
  });
});
