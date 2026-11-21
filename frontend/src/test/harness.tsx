import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { vi } from 'vitest';
import { api, ApiError } from '../lib/api';
import { AuthContext, type AuthState } from '../lib/auth';
import type { User } from '../lib/types';
import { kariUser } from './fixtures';

type Responder = unknown | ((path: string, body?: unknown) => unknown);
export type Routes = Array<[string | RegExp, Responder]>;

function matches(pattern: string | RegExp, path: string) {
  return typeof pattern === 'string' ? path === pattern || path.split('?')[0] === pattern : pattern.test(path);
}

function respond(routes: Routes, path: string, body?: unknown) {
  for (const [pattern, responder] of routes) {
    if (matches(pattern, path)) {
      const value = typeof responder === 'function' ? (responder as (p: string, b?: unknown) => unknown)(path, body) : responder;
      if (value instanceof ApiError) return Promise.reject(value);
      return Promise.resolve(structuredClone(value));
    }
  }
  return Promise.reject(new ApiError(404, 'CHESS-GEN-002', `Ingen testdata for ${path}`, null));
}

/** Answers api.get/post/put/delete from canned responses; returns the spies for assertions. */
export function mockApi({ get = [], post = [], put = [], del = [] }: { get?: Routes; post?: Routes; put?: Routes; del?: Routes } = {}) {
  return {
    get: vi.spyOn(api, 'get').mockImplementation((path: string) => respond(get, path) as Promise<never>),
    post: vi.spyOn(api, 'post').mockImplementation((path: string, body?: unknown) => respond(post, path, body) as Promise<never>),
    put: vi.spyOn(api, 'put').mockImplementation((path: string, body?: unknown) => respond(put, path, body) as Promise<never>),
    delete: vi.spyOn(api, 'delete').mockImplementation((path: string) => respond(del, path) as Promise<never>),
  };
}

export function authAs(user: User | null, extra: Partial<AuthState> = {}): AuthState {
  return { user, loading: false, login: vi.fn(), logout: vi.fn(async () => {}), reload: vi.fn(async () => {}), ...extra };
}

/**
 * Renders a page at a URL inside a router and a signed-in session. Other paths render a marker
 * paragraph ("Side: /path") so navigation can be asserted.
 */
export function renderPage(element: ReactElement, { url, route, user = kariUser, auth }: {
  url: string; route?: string; user?: User | null; auth?: AuthState;
}) {
  const session = userEvent.setup();
  const result = render(
    <AuthContext.Provider value={auth ?? authAs(user)}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path={route ?? url.split('?')[0]} element={element} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
  return { ...result, user: session };
}

function Where() {
  const location = useLocation();
  return <p>Side: {location.pathname}{location.search}</p>;
}
