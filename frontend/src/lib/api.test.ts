import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, CLIENT_ERRORS } from './api';

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return json(status, { type: 'urn:x', title: 't', status, detail: `detalj ${code}`, code, traceId: 'trace-123', timestamp: 'now', ...extra });
}

describe('ApiClient', () => {
  let handler: Handler;
  let calls: { url: string; init: RequestInit }[];
  let client: ApiClient;
  let expired: ReturnType<typeof vi.fn<() => void>>;

  beforeEach(() => {
    calls = [];
    expired = vi.fn<() => void>();
    const fetchImpl = vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return handler(url, init);
    });
    client = new ApiClient('https://api.example', fetchImpl as unknown as typeof fetch, expired, { wakeTimeoutMs: 0 });
  });

  it('sends the access token and parses JSON', async () => {
    client.setAccessToken('tok-1');
    handler = () => json(200, { id: 7 });
    expect(await client.get<{ id: number }>('/api/users/me')).toEqual({ id: 7 });
    expect(new Headers(calls[0].init.headers).get('Authorization')).toBe('Bearer tok-1');
    expect(calls[0].url).toBe('https://api.example/api/users/me');
  });

  it('turns a problem document into an ApiError with the catalog code', async () => {
    handler = () => problem(409, 'CHESS-GAME-003');
    const error = (await client.post('/api/games/1/confirm', {}).catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: 'CHESS-GAME-003', detail: 'detalj CHESS-GAME-003', traceId: 'trace-123' });
  });

  it('keeps field errors from a validation problem', async () => {
    handler = () => problem(400, 'CHESS-GEN-001', { errors: [{ field: 'username', message: 'for kort' }] });
    const error = (await client.post('/api/auth/register', {}).catch((e: unknown) => e)) as ApiError;
    expect(error.fieldErrors).toEqual({ username: 'for kort' });
  });

  it('refreshes an expired access token once and retries the request', async () => {
    client.setAccessToken('old');
    handler = (url, init) => {
      if (url.endsWith('/api/auth/refresh')) return json(200, { accessToken: 'new', expiresIn: 900, user: { id: 1 } });
      return new Headers(init.headers).get('Authorization') === 'Bearer new' ? json(200, { ok: true }) : problem(401, 'CHESS-AUTH-002');
    };
    expect(await client.get('/api/users/me')).toEqual({ ok: true });
    const refresh = calls.find((c) => c.url.endsWith('/api/auth/refresh'))!;
    expect(refresh.init.credentials).toBe('include');
    expect(client.getAccessToken()).toBe('new');
  });

  it('uses one refresh for many requests that expire at the same moment', async () => {
    client.setAccessToken('old');
    handler = async (url, init) => {
      if (url.endsWith('/api/auth/refresh')) {
        await new Promise((r) => setTimeout(r, 20));
        return json(200, { accessToken: 'new', expiresIn: 900, user: { id: 1 } });
      }
      return new Headers(init.headers).get('Authorization') === 'Bearer new' ? json(200, { ok: true }) : problem(401, 'CHESS-AUTH-002');
    };
    await Promise.all([client.get('/a'), client.get('/b'), client.get('/c'), client.get('/d')]);
    expect(calls.filter((c) => c.url.endsWith('/api/auth/refresh'))).toHaveLength(1);
  });

  it('reports an expired session when the refresh is refused, without looping', async () => {
    client.setAccessToken('old');
    handler = (url) => (url.endsWith('/api/auth/refresh') ? problem(401, 'CHESS-AUTH-005') : problem(401, 'CHESS-AUTH-002'));
    const error = (await client.get('/api/users/me').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('CHESS-AUTH-002');
    expect(expired).toHaveBeenCalledTimes(1);
    expect(calls.filter((c) => c.url.endsWith('/api/auth/refresh'))).toHaveLength(1);
    expect(client.getAccessToken()).toBeNull();
  });

  it('does not try to refresh after a wrong password', async () => {
    handler = () => problem(401, 'CHESS-AUTH-001');
    await client.post('/api/auth/login', {}).catch(() => {});
    expect(calls).toHaveLength(1);
  });

  it('exposes Retry-After on rate limiting', async () => {
    handler = () => problem(429, 'CHESS-RATE-001', {});
    handler = () => new Response(JSON.stringify({ code: 'CHESS-RATE-001', status: 429, detail: 'vent' }), {
      status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '12' },
    });
    const error = (await client.post('/api/auth/login', {}).catch((e: unknown) => e)) as ApiError;
    expect(error.retryAfterSeconds).toBe(12);
  });

  it('gives an offline server its own client-side code', async () => {
    const offline = new ApiClient('https://api.example', (() => Promise.reject(new TypeError('Failed to fetch'))) as unknown as typeof fetch, expired, { wakeTimeoutMs: 0 });
    const error = (await offline.get('/api/tv').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe(CLIENT_ERRORS.NETWORK);
  });

  it('handles a proxy error page that is not JSON', async () => {
    handler = () => new Response('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } });
    const error = (await client.get('/api/tv').catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(502);
    expect(error.code).toBe(CLIENT_ERRORS.UNEXPECTED_RESPONSE);
  });

  it('returns undefined for 204 No Content', async () => {
    handler = () => new Response(null, { status: 204 });
    expect(await client.post('/api/auth/logout')).toBeUndefined();
  });

  it('sends a trace id so frontend and backend logs line up', async () => {
    handler = () => json(200, {});
    await client.get('/x');
    expect(new Headers(calls[0].init.headers).get('X-Trace-Id')).toMatch(/^[A-Za-z0-9-]{8,64}$/);
  });
});

describe('ApiClient while a sleeping server starts', () => {
  let calls: string[];
  let waking: boolean[];
  let pingReadyAfter: number;
  let pings: number;
  let failFirst: () => Response | Promise<Response>;
  let client: ApiClient;
  let attempts: Record<string, number>;

  function gateway(status: number): Response {
    return new Response('<html>Bad gateway</html>', { status, headers: { 'Content-Type': 'text/html' } });
  }

  beforeEach(() => {
    calls = [];
    waking = [];
    pings = 0;
    pingReadyAfter = 2;
    attempts = {};
    failFirst = () => gateway(502);
    const fetchImpl = async (url: string) => {
      const path = url.replace('https://api.example', '');
      calls.push(path);
      if (path === '/api/ping') {
        pings += 1;
        if (pings <= pingReadyAfter) throw new TypeError('Failed to fetch');
        return new Response(null, { status: 204 });
      }
      attempts[path] = (attempts[path] ?? 0) + 1;
      if (attempts[path] === 1) return failFirst();
      return json(200, { ok: path });
    };
    client = new ApiClient('https://api.example', fetchImpl as unknown as typeof fetch, () => {}, {
      sleep: () => Promise.resolve(),
      wakeTimeoutMs: 180_000,
      pollIntervalMs: 3000,
    });
    client.onWakingChange((value) => waking.push(value));
  });

  it('waits for /api/ping and then repeats a read that hit a gateway error', async () => {
    expect(await client.get('/api/leaderboard')).toEqual({ ok: '/api/leaderboard' });
    expect(calls).toEqual(['/api/leaderboard', '/api/ping', '/api/ping', '/api/ping', '/api/leaderboard']);
    expect(waking).toEqual([true, false]);
  });

  it('treats a network failure the same way', async () => {
    failFirst = () => { throw new TypeError('Failed to fetch'); };
    expect(await client.get('/api/tv')).toEqual({ ok: '/api/tv' });
    expect(waking).toEqual([true, false]);
  });

  it('never repeats a write by itself, but says the server is ready for another try', async () => {
    const error = (await client.post('/api/games', {}).catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe(CLIENT_ERRORS.SERVER_WOKE);
    expect(attempts['/api/games']).toBe(1);
    expect(calls.filter((c) => c === '/api/ping')).toHaveLength(3);
  });

  it('restores the session after waking, so a page load survives a cold start', async () => {
    expect(await client.refresh()).toBe(true);
    expect(attempts['/api/auth/refresh']).toBe(2);
  });

  it('gives up with a network error when the server never answers', async () => {
    pingReadyAfter = Number.POSITIVE_INFINITY;
    const error = (await client.get('/api/leaderboard').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe(CLIENT_ERRORS.NETWORK);
    // 180 s / 3 s polls
    expect(pings).toBe(60);
    expect(waking).toEqual([true, false]);
  });

  it('leaves real problem documents alone, even with a 503', async () => {
    failFirst = () => problem(503, 'CHESS-GEN-003');
    const error = (await client.get('/api/leaderboard').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('CHESS-GEN-003');
    expect(pings).toBe(0);
  });

  it('shares one wait between concurrent requests', async () => {
    await Promise.all([client.get('/api/a'), client.get('/api/b'), client.get('/api/c')]);
    expect(pings).toBe(3);
    expect(waking).toEqual([true, false]);
  });
});
