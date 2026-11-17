/**
 * HTTP client for the Sjakkportal API.
 *
 * The access token lives only in memory. When it expires the client calls /api/auth/refresh once
 * (the refresh token is an HttpOnly cookie the page cannot read) and repeats the request; concurrent
 * requests share that single refresh. Every error becomes an {@link ApiError} carrying the
 * catalog code from the server's problem document.
 *
 * A free-tier server sleeps when nobody uses it. The first request then meets a gateway error or no
 * answer at all; the client waits for /api/ping (reporting it through onWakingChange so the page can
 * say so), then repeats reads by itself. Writes are never repeated automatically: the user gets
 * CHESS-CLIENT-004 and presses the button again.
 */

/** Codes for failures that happen before a problem document exists. Documented in docs/API_AND_ERRORS.md. */
export const CLIENT_ERRORS = {
  NETWORK: 'CHESS-CLIENT-001',
  UNEXPECTED_RESPONSE: 'CHESS-CLIENT-002',
  RENDER: 'CHESS-CLIENT-003',
  SERVER_WOKE: 'CHESS-CLIENT-004',
} as const;

export interface ApiClientOptions {
  /** How long to wait for a sleeping server; 0 turns waiting off. */
  wakeTimeoutMs?: number;
  pollIntervalMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

const COLD_STATUSES = new Set([502, 503, 504]);
const NETWORK_MESSAGE = 'Får ikke kontakt med serveren. Sjekk nettforbindelsen og prøv igjen.';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly traceId: string | null,
    readonly fieldErrors: Record<string, string> = {},
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(detail);
    this.name = 'ApiError';
  }
}

export interface SessionResponse<U> {
  accessToken: string;
  expiresIn: number;
  user: U;
}

const REFRESHABLE = 'CHESS-AUTH-002';

export class ApiClient {
  private accessToken: string | null = null;
  private refreshing: Promise<boolean> | null = null;
  private sessionListener: (session: SessionResponse<unknown> | null) => void = () => {};
  private readonly wakingListeners = new Set<(waking: boolean) => void>();
  private waking: Promise<boolean> | null = null;
  private readonly wakeTimeoutMs: number;
  private readonly pollIntervalMs: number;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(
    private readonly baseUrl: string,
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
    private readonly onSessionExpired: () => void = () => {},
    options: ApiClientOptions = {},
  ) {
    this.wakeTimeoutMs = options.wakeTimeoutMs ?? 180_000;
    this.pollIntervalMs = options.pollIntervalMs ?? 3000;
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  /** Called with true when the client starts waiting for a sleeping server, and false when it stops. */
  onWakingChange(listener: (waking: boolean) => void): () => void {
    this.wakingListeners.add(listener);
    return () => this.wakingListeners.delete(listener);
  }

  isWaking(): boolean {
    return this.waking !== null;
  }

  setAccessToken(token: string | null): void {
    this.accessToken = token;
  }

  getAccessToken(): string | null {
    return this.accessToken;
  }

  /** Called with the new session after every successful refresh, or null when it fails. */
  onSessionChange(listener: (session: SessionResponse<unknown> | null) => void): void {
    this.sessionListener = listener;
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  post<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
    return this.request<T>('POST', path, body, headers);
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body);
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path);
  }

  /** Restores a session from the refresh cookie (on page load). */
  async refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = this.doRefresh().finally(() => {
        this.refreshing = null;
      });
    }
    return this.refreshing;
  }

  private async doRefresh(woke = false): Promise<boolean> {
    try {
      let response: Response;
      try {
        response = await this.fetchImpl(this.url('/api/auth/refresh'), {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Trace-Id': traceId() },
        });
      } catch {
        return !woke && (await this.waitUntilAwake()) ? this.doRefresh(true) : false;
      }
      if (!woke && COLD_STATUSES.has(response.status) && !isProblem(response)) {
        // Nothing was decided about the session; keep it and try again once the server is up.
        return (await this.waitUntilAwake()) ? this.doRefresh(true) : false;
      }
      if (!response.ok) {
        this.accessToken = null;
        this.sessionListener(null);
        return false;
      }
      const session = (await response.json()) as SessionResponse<unknown>;
      this.accessToken = session.accessToken;
      this.sessionListener(session);
      return true;
    } catch {
      return false;
    }
  }

  private async request<T>(method: string, path: string, body?: unknown, extraHeaders?: Record<string, string>, retried = false, woke = false): Promise<T> {
    const headers = new Headers({ Accept: 'application/json', 'X-Trace-Id': traceId(), ...extraHeaders });
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    if (this.accessToken) headers.set('Authorization', `Bearer ${this.accessToken}`);

    let response: Response;
    try {
      response = await this.fetchImpl(this.url(path), {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        // Auth endpoints read and set the refresh cookie; nothing else needs cookies.
        credentials: path.startsWith('/api/auth/') ? 'include' : 'same-origin',
      });
    } catch {
      if (!woke && (await this.waitUntilAwake())) return this.afterWaking<T>(method, path, body, extraHeaders, retried);
      throw new ApiError(0, CLIENT_ERRORS.NETWORK, NETWORK_MESSAGE, null);
    }

    if (response.ok) {
      if (response.status === 204) return undefined as T;
      const text = await response.text();
      return (text ? JSON.parse(text) : undefined) as T;
    }

    const error = await toApiError(response);
    if (!woke && COLD_STATUSES.has(error.status) && error.code === CLIENT_ERRORS.UNEXPECTED_RESPONSE) {
      if (await this.waitUntilAwake()) return this.afterWaking<T>(method, path, body, extraHeaders, retried);
      if (this.wakeTimeoutMs > 0) throw new ApiError(0, CLIENT_ERRORS.NETWORK, NETWORK_MESSAGE, error.traceId);
    }
    if (error.code === REFRESHABLE && !retried) {
      if (await this.refresh()) {
        return this.request<T>(method, path, body, extraHeaders, true, woke);
      }
      this.accessToken = null;
      this.onSessionExpired();
    }
    throw error;
  }

  private afterWaking<T>(method: string, path: string, body: unknown, extraHeaders: Record<string, string> | undefined, retried: boolean): Promise<T> {
    if (method === 'GET') return this.request<T>(method, path, body, extraHeaders, retried, true);
    throw new ApiError(0, CLIENT_ERRORS.SERVER_WOKE,
      'Serveren hadde tatt en pause og er klar nå. Prøv igjen.', null);
  }

  /** Polls /api/ping until the server answers. Concurrent callers share one wait. */
  private waitUntilAwake(): Promise<boolean> {
    if (this.wakeTimeoutMs <= 0) return Promise.resolve(false);
    if (!this.waking) {
      this.waking = this.poll().finally(() => {
        this.waking = null;
        this.wakingListeners.forEach((listener) => listener(false));
      });
      this.wakingListeners.forEach((listener) => listener(true));
    }
    return this.waking;
  }

  private async poll(): Promise<boolean> {
    const attempts = Math.max(1, Math.ceil(this.wakeTimeoutMs / this.pollIntervalMs));
    for (let attempt = 0; attempt < attempts; attempt++) {
      try {
        const response = await this.fetchImpl(this.url('/api/ping'), { method: 'GET', headers: { 'X-Trace-Id': traceId() } });
        if (response.ok) return true;
      } catch {
        // still asleep
      }
      if (attempt < attempts - 1) await this.sleep(this.pollIntervalMs);
    }
    return false;
  }

  private url(path: string): string {
    return `${this.baseUrl}${path}`;
  }
}

function isProblem(response: Response): boolean {
  return (response.headers.get('Content-Type') ?? '').includes('problem+json');
}

async function toApiError(response: Response): Promise<ApiError> {
  const retryAfter = response.headers.get('Retry-After');
  const retryAfterSeconds = retryAfter && /^\d+$/.test(retryAfter) ? Number(retryAfter) : null;
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('json')) {
    try {
      const problem = (await response.json()) as {
        code?: string;
        detail?: string;
        traceId?: string;
        errors?: { field: string; message: string }[];
      };
      if (problem.code) {
        const fieldErrors = Object.fromEntries((problem.errors ?? []).map((e) => [e.field, e.message]));
        return new ApiError(response.status, problem.code, problem.detail ?? '', problem.traceId ?? null, fieldErrors, retryAfterSeconds);
      }
    } catch {
      // fall through to the generic error
    }
  }
  return new ApiError(response.status, CLIENT_ERRORS.UNEXPECTED_RESPONSE,
    `Serveren svarte med en uventet feil (${response.status}). Prøv igjen om litt.`, response.headers.get('X-Trace-Id'), {}, retryAfterSeconds);
}

export function traceId(): string {
  const random = globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  return `web-${random}`.slice(0, 64);
}

const configuredBase = (import.meta.env.VITE_API_URL as string | undefined) ?? '';
export const api = new ApiClient(configuredBase.replace(/\/$/, ''));
