import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { useEffect, useRef } from 'react';
import { api } from './api';

/**
 * One shared STOMP connection to /ws for the whole page. Components subscribe to topics with
 * {@link useTopic}; the connection reconnects by itself and re-subscribes after a drop.
 */
type Listener = (body: unknown) => void;

const listeners = new Map<string, Set<Listener>>();
const subscriptions = new Map<string, StompSubscription>();
let client: Client | null = null;

/**
 * Where the STOMP socket connects. VITE_WS_URL wins: in production the API is proxied through the
 * frontend host, which cannot proxy WebSockets, so the socket goes straight to the backend.
 */
export function resolveSocketUrl(wsUrl: string | undefined, apiUrl: string | undefined, origin: string): string {
  if (wsUrl) return wsUrl;
  const base = apiUrl || origin;
  return `${base.replace(/^http/, 'ws').replace(/\/$/, '')}/ws`;
}

function socketUrl(): string {
  return resolveSocketUrl(import.meta.env.VITE_WS_URL as string | undefined,
    import.meta.env.VITE_API_URL as string | undefined, window.location.origin);
}

function ensureClient(): Client {
  if (client) return client;
  client = new Client({
    brokerURL: socketUrl(),
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    beforeConnect: async () => {
      // The access token may have expired while the socket was down.
      if (!api.getAccessToken()) await api.refresh();
      client!.connectHeaders = { Authorization: `Bearer ${api.getAccessToken() ?? ''}` };
    },
    onConnect: () => {
      subscriptions.clear();
      for (const topic of listeners.keys()) subscribe(topic);
    },
    onStompError: () => {
      // Usually an expired token: drop it so the next attempt refreshes first.
      void api.refresh();
    },
  });
  client.activate();
  return client;
}

function subscribe(topic: string) {
  if (!client?.connected || subscriptions.has(topic)) return;
  subscriptions.set(topic, client.subscribe(topic, (message: IMessage) => {
    let body: unknown;
    try {
      body = JSON.parse(message.body);
    } catch {
      return;
    }
    listeners.get(topic)?.forEach((listener) => listener(body));
  }));
}

/** Calls {@code onMessage} with every message on the topic while the component is mounted. */
export function useTopic<T>(topic: string | null, onMessage: (body: T) => void): void {
  const handler = useRef(onMessage);
  handler.current = onMessage;

  useEffect(() => {
    if (!topic) return;
    const listener: Listener = (body) => handler.current(body as T);
    if (!listeners.has(topic)) listeners.set(topic, new Set());
    listeners.get(topic)!.add(listener);
    ensureClient();
    subscribe(topic);
    return () => {
      const set = listeners.get(topic);
      set?.delete(listener);
      if (set && set.size === 0) {
        listeners.delete(topic);
        subscriptions.get(topic)?.unsubscribe();
        subscriptions.delete(topic);
      }
    };
  }, [topic]);
}
