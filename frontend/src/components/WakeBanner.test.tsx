import { act, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiClient } from '../lib/api';
import { WakeBanner } from './WakeBanner';

describe('WakeBanner', () => {
  it('tells people the server is starting while the client waits, and disappears afterwards', async () => {
    let finishWaking: () => void = () => {};
    const fetchImpl = (async (url: string) => {
      if (url.endsWith('/api/ping')) {
        await new Promise<void>((resolve) => { finishWaking = resolve; });
        return new Response(null, { status: 204 });
      }
      return new Response('down', { status: 502 });
    }) as unknown as typeof fetch;
    const client = new ApiClient('', fetchImpl, () => {}, { sleep: () => Promise.resolve() });
    render(<WakeBanner client={client} />);
    expect(screen.queryByRole('status')).toBeNull();

    let request: Promise<unknown> = Promise.resolve();
    await act(async () => {
      request = client.get('/api/tv').catch(() => undefined);
      await Promise.resolve();
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Serveren våkner');

    await act(async () => {
      finishWaking();
      await request;
    });
    expect(screen.queryByRole('status')).toBeNull();
  });
});
