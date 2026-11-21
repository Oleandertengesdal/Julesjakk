import { useEffect, useRef } from 'react';

/**
 * Stands in for lib/live in page tests: no WebSocket, but tests can push messages to whatever
 * topics a page subscribed to. Use with vi.mock('../lib/live', () => import('../test/liveMock')).
 */
const listeners = new Map<string, Set<(body: unknown) => void>>();

export function useTopic<T>(topic: string | null, onMessage: (body: T) => void): void {
  const handler = useRef(onMessage);
  handler.current = onMessage;
  useEffect(() => {
    if (!topic) return;
    const listener = (body: unknown) => handler.current(body as T);
    if (!listeners.has(topic)) listeners.set(topic, new Set());
    listeners.get(topic)!.add(listener);
    return () => { listeners.get(topic)?.delete(listener); };
  }, [topic]);
}

export function resolveSocketUrl(): string {
  return 'ws://test/ws';
}

/** Topics a mounted page currently listens to. */
export function subscribedTopics(): string[] {
  return [...listeners.entries()].filter(([, set]) => set.size > 0).map(([topic]) => topic);
}

export function emit(topic: string, body: unknown): void {
  listeners.get(topic)?.forEach((listener) => listener(body));
}
