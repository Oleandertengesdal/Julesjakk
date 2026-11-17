import { NavLink, Outlet, useNavigate } from 'react-router';
import { useAuth } from '../lib/auth';
import { useApi } from '../lib/useApi';
import type { Game } from '../lib/types';

function NavItem({ to, children, badge }: { to: string; children: string; badge?: number }) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      className={({ isActive }) =>
        `relative whitespace-nowrap rounded-md px-3 py-2 text-sm font-semibold ${isActive ? 'bg-spruce-3 text-snow' : 'text-birch hover:text-snow'}`}
    >
      {children}
      {!!badge && (
        <span className="ml-1.5 inline-grid min-w-5 place-items-center rounded-full bg-berry px-1 text-xs text-white tabular" aria-label={`${badge} venter`}>
          {badge}
        </span>
      )}
    </NavLink>
  );
}

export function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const pending = useApi<Game[]>(user ? '/api/games/pending' : null);

  return (
    <div className="flex min-h-dvh flex-col">
      <a href="#innhold" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:bg-white focus:px-3 focus:py-2">
        Hopp til innholdet
      </a>
      <header className="bg-spruce text-snow">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 sm:flex-nowrap sm:px-6">
          <NavLink to="/" className="flex items-center gap-2 text-lg font-extrabold tracking-tight text-snow">
            <span className="text-2xl text-birch" aria-hidden="true">♞</span>
            Sjakkportal
          </NavLink>
          <nav aria-label="Hovedmeny" className="order-last -mx-1 flex w-full gap-1 overflow-x-auto px-1 sm:order-none sm:w-auto sm:flex-1">
            <NavItem to="/" badge={pending.data?.length}>Hjem</NavItem>
            <NavItem to="/games/new">Registrer parti</NavItem>
            <NavItem to="/tournaments">Turneringer</NavItem>
            <NavItem to="/players">Spillere</NavItem>
            <NavItem to="/tv">TV</NavItem>
            {user?.role === 'ADMIN' && <NavItem to="/admin">Admin</NavItem>}
          </nav>
          {user && (
            <div className="hidden items-center gap-3 text-sm sm:flex">
              <NavLink to="/settings" className="text-birch hover:text-snow">{user.displayName}</NavLink>
              <button
                type="button"
                className="rounded-md border border-spruce-3 px-3 py-1.5 font-semibold text-birch hover:text-snow"
                onClick={async () => {
                  await logout();
                  navigate('/login');
                }}
              >
                Logg ut
              </button>
            </div>
          )}
        </div>
      </header>
      <main id="innhold" className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6">
        <Outlet />
      </main>
      <footer className="border-t border-mist">
        <div className="mx-auto flex max-w-6xl justify-between px-4 py-4 text-sm text-slate sm:px-6">
          <span>Sjakkportal</span>
          {user && <NavLink to="/settings" className="sm:hidden">Innstillinger og utlogging</NavLink>}
        </div>
      </footer>
    </div>
  );
}
