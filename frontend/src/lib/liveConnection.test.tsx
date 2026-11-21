import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/** A stand-in STOMP client: records subscriptions and lets the test deliver frames. */
const stomp = vi.hoisted(() => {
  const state = {
    instances: [] as FakeClient[],
  };
  class FakeClient {
    connected = false;
    connectHeaders: Record<string, string> = {};
    subscriptions = new Map<string, { callback: (m: { body: string }) => void; unsubscribe: () => void; active: boolean }>();
    constructor(public config: { brokerURL: string; beforeConnect: () => Promise<void>; onConnect: () => void; onStompError: () => void }) {
      state.instances.push(this);
    }
    activate = vi.fn();
    subscribe(topic: string, callback: (m: { body: string }) => void) {
      const sub = { callback, active: true, unsubscribe: () => { sub.active = false; } };
      this.subscriptions.set(topic, sub);
      return sub;
    }
    async connect() {
      await this.config.beforeConnect();
      this.connected = true;
      this.config.onConnect();
    }
    deliver(topic: string, body: string) {
      const sub = this.subscriptions.get(topic);
      if (sub?.active) sub.callback({ body });
    }
  }
  return { state, FakeClient };
});
vi.mock('@stomp/stompjs', () => ({ Client: stomp.FakeClient }));

const { api } = await import('./api');
const { useTopic } = await import('./live');

function Listener({ topic, onMessage }: { topic: string | null; onMessage: (body: unknown) => void }) {
  useTopic(topic, onMessage);
  return null;
}

beforeEach(() => {
  vi.spyOn(api, 'getAccessToken').mockReturnValue('token-1');
  vi.spyOn(api, 'refresh').mockResolvedValue(true);
});
afterEach(() => vi.restoreAllMocks());

describe('the shared live connection', () => {
  it('opens one connection with the access token and delivers parsed messages to every listener', async () => {
    const a = vi.fn();
    const b = vi.fn();
    const view = render(<><Listener topic="/topic/tv" onMessage={a} /><Listener topic="/topic/tv" onMessage={b} /></>);
    expect(stomp.state.instances).toHaveLength(1);
    const client = stomp.state.instances[0];
    expect(client.activate).toHaveBeenCalledTimes(1);
    expect(client.config.brokerURL).toMatch(/\/ws$/);
    await act(() => client.connect());
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer token-1' });
    act(() => client.deliver('/topic/tv', '{"type":"tv"}'));
    expect(a).toHaveBeenCalledWith({ type: 'tv' });
    expect(b).toHaveBeenCalledWith({ type: 'tv' });
    act(() => client.deliver('/topic/tv', 'not json'));
    expect(a).toHaveBeenCalledTimes(1);
    view.unmount();
  });

  it('subscribes at once when already connected, and unsubscribes when the last listener leaves', async () => {
    const client = stomp.state.instances[0];
    const first = vi.fn();
    const view = render(<Listener topic="/topic/games/17" onMessage={first} />);
    expect(client.subscriptions.get('/topic/games/17')?.active).toBe(true);
    view.rerender(<Listener topic={null} onMessage={first} />);
    expect(client.subscriptions.get('/topic/games/17')?.active).toBe(false);
    act(() => client.deliver('/topic/games/17', '{}'));
    expect(first).not.toHaveBeenCalled();
  });

  it('refreshes an expired token before reconnecting, and after the broker refuses it', async () => {
    const client = stomp.state.instances[0];
    vi.mocked(api.getAccessToken).mockReturnValueOnce(null).mockReturnValue('token-2');
    const onMessage = vi.fn();
    const view = render(<Listener topic="/topic/tournaments/5" onMessage={onMessage} />);
    await act(() => client.connect());
    expect(api.refresh).toHaveBeenCalledTimes(1);
    expect(client.connectHeaders.Authorization).toBe('Bearer token-2');
    act(() => client.deliver('/topic/tournaments/5', '{"n":1}'));
    expect(onMessage).toHaveBeenCalledWith({ n: 1 });
    client.config.onStompError();
    expect(api.refresh).toHaveBeenCalledTimes(2);
    view.unmount();
  });
});
