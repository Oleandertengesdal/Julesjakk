import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './lib/api';

vi.mock('./lib/live', () => import('./test/liveMock'));
afterEach(() => vi.restoreAllMocks());

describe('App', () => {
  it('restores no session and lands on the login page', async () => {
    vi.spyOn(api, 'refresh').mockResolvedValue(false);
    const { App } = await import('./App');
    render(<App />);
    expect(await screen.findByRole('button', { name: 'Logg inn' })).toBeInTheDocument();
  });
});
