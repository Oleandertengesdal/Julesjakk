import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, type SessionResponse } from './api';
import type { User } from './types';

export interface AuthState {
  user: User | null;
  loading: boolean;
  login(username: string, password: string): Promise<User>;
  logout(): Promise<void>;
  reload(): Promise<void>;
}

/** Exported so tests can provide a signed-in user without a server. */
export const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.onSessionChange((session) => setUser(session ? (session.user as User) : null));
    // The refresh cookie restores the session after a reload.
    api.refresh().finally(() => setLoading(false));
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const session = await api.post<SessionResponse<User>>('/api/auth/login', { username, password });
    api.setAccessToken(session.accessToken);
    setUser(session.user);
    return session.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.post('/api/auth/logout');
    } finally {
      api.setAccessToken(null);
      setUser(null);
    }
  }, []);

  const reload = useCallback(async () => {
    setUser(await api.get<User>('/api/users/me'));
  }, []);

  const value = useMemo(() => ({ user, loading, login, logout, reload }), [user, loading, login, logout, reload]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
