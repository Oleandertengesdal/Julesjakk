import { lazy, Suspense, type ReactNode } from 'react';
import { createBrowserRouter, Outlet, RouterProvider } from 'react-router';
import { ErrorBoundary, NotFoundScreen } from './components/ErrorScreens';
import { Layout } from './components/Layout';
import { RequireAdmin, RequireAuth } from './components/RouteGuards';
import { Loading } from './components/ui';
import { WakeBanner } from './components/WakeBanner';
import { AuthProvider } from './lib/auth';
import { LoginPage, RegisterPage, ResetPasswordPage, ChangePasswordPage } from './pages/AuthPages';
import { HomePage } from './pages/HomePage';

// Heavier pages load on demand so the first screen (often a phone at the chess table) stays light.
const NewGamePage = lazy(() => import('./pages/NewGamePage'));
const ImportPage = lazy(() => import('./pages/ImportPage'));
const MatchPage = lazy(() => import('./pages/MatchPage'));
const TvPage = lazy(() => import('./pages/TvPage'));
const TournamentsPage = lazy(() => import('./pages/TournamentsPage'));
const TournamentPage = lazy(() => import('./pages/TournamentPage'));
const PlayersPage = lazy(() => import('./pages/PlayersPage'));
const PlayerPage = lazy(() => import('./pages/PlayerPage'));
const AdminPage = lazy(() => import('./pages/AdminPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const ClockPage = lazy(() => import('./pages/ClockPage'));


function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<Loading />}>{children}</Suspense>;
}

function Root() {
  return (
    <ErrorBoundary>
      <WakeBanner />
      <Outlet />
    </ErrorBoundary>
  );
}

const router = createBrowserRouter([
  {
    element: <Root />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
      { path: '/reset-password', element: <ResetPasswordPage /> },
      { path: '/change-password', element: <RequireAuth><ChangePasswordPage /></RequireAuth> },
      { path: '/tv', element: <RequireAuth><Lazy><TvPage /></Lazy></RequireAuth> },
      { path: '/matches/:id/klokke', element: <RequireAuth><Lazy><ClockPage /></Lazy></RequireAuth> },
      {
        element: <RequireAuth><Layout /></RequireAuth>,
        children: [
          { path: '/', element: <HomePage /> },
          { path: '/games/new', element: <Lazy><NewGamePage /></Lazy> },
          { path: '/games/import', element: <Lazy><ImportPage /></Lazy> },
          { path: '/matches/:id', element: <Lazy><MatchPage /></Lazy> },
          { path: '/tournaments', element: <Lazy><TournamentsPage /></Lazy> },
          { path: '/tournaments/:id', element: <Lazy><TournamentPage /></Lazy> },
          { path: '/players', element: <Lazy><PlayersPage /></Lazy> },
          { path: '/players/:id', element: <Lazy><PlayerPage /></Lazy> },
          { path: '/settings', element: <Lazy><SettingsPage /></Lazy> },
          { path: '/admin', element: <RequireAdmin><Lazy><AdminPage /></Lazy></RequireAdmin> },
          { path: '*', element: <NotFoundScreen /> },
        ],
      },
    ],
  },
]);

export function App() {
  return (
    <AuthProvider>
      <RouterProvider router={router} />
    </AuthProvider>
  );
}
