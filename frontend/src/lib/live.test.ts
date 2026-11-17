import { describe, expect, it } from 'vitest';
import { resolveSocketUrl } from './live';

describe('resolveSocketUrl', () => {
  it('uses VITE_WS_URL as given when set (the API is proxied but WebSockets are not)', () => {
    expect(resolveSocketUrl('wss://sjakkportal-api.onrender.com/ws', '', 'https://sjakk.vercel.app'))
      .toBe('wss://sjakkportal-api.onrender.com/ws');
  });

  it('derives the socket address from VITE_API_URL otherwise', () => {
    expect(resolveSocketUrl(undefined, 'https://api.example/', 'https://sjakk.example')).toBe('wss://api.example/ws');
  });

  it('falls back to the page origin, which the dev server proxies', () => {
    expect(resolveSocketUrl('', '', 'http://localhost:5173')).toBe('ws://localhost:5173/ws');
  });
});
