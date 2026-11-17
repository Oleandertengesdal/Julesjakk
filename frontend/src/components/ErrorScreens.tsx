import { Component, useState, type ErrorInfo, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ApiError, CLIENT_ERRORS } from '../lib/api';

interface ScreenProps {
  title: string;
  message: string;
  code: string;
  status?: number;
  traceId?: string | null;
  extra?: Record<string, unknown>;
}

/** The one layout for every "something is wrong" page: what happened, what to do, and the code to report. */
function ErrorLayout({ title, message, code, status, traceId, extra }: ScreenProps) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    const info = {
      code,
      status: status ?? null,
      traceId: traceId ?? null,
      message,
      url: window.location.href,
      time: new Date().toISOString(),
      userAgent: navigator.userAgent,
      ...extra,
    };
    try {
      await navigator.clipboard.writeText(JSON.stringify(info, null, 2));
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-[70dvh] max-w-xl flex-col justify-center px-6 py-16">
      <p className="text-7xl text-walnut select-none" aria-hidden="true">♚</p>
      <h1 className="mt-6 text-3xl font-extrabold text-spruce">{title}</h1>
      <p className="mt-3 text-lg text-slate">{message}</p>
      <dl className="mt-8 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 border-l-4 border-birch pl-4 text-sm">
        <dt className="text-slate">Feilkode</dt>
        <dd className="font-semibold tabular">{code}</dd>
        {traceId && (
          <>
            <dt className="text-slate">Sporings-ID</dt>
            <dd className="tabular break-all">{traceId}</dd>
          </>
        )}
      </dl>
      <div className="mt-8 flex flex-wrap items-center gap-3">
        <Link to="/" className="rounded-md bg-spruce px-4 py-2 font-semibold text-snow hover:bg-spruce-2">Til forsiden</Link>
        <button type="button" onClick={copy} className="rounded-md border border-spruce px-4 py-2 font-semibold text-spruce hover:bg-mist">
          Kopier feilsøkingsinfo
        </button>
        {copied && <span role="status" className="text-sm text-moss">Kopiert. Send den til en administrator.</span>}
      </div>
    </main>
  );
}

export function NotFoundScreen() {
  return <ErrorLayout title="Finner ikke siden" message="Lenken kan være feil, eller siden er flyttet." code="CHESS-GEN-002" status={404} />;
}

export function ForbiddenScreen() {
  return <ErrorLayout title="Ingen tilgang" message="Denne siden er bare for administratorer." code="CHESS-AUTH-003" status={403} />;
}

export function ProblemScreen({ error }: { error: ApiError }) {
  const title = error.status === 404 ? 'Finner det ikke' : error.status === 403 ? 'Ingen tilgang' : 'Noe gikk galt';
  return <ErrorLayout title={title} message={error.detail} code={error.code} status={error.status} traceId={error.traceId} />;
}

interface BoundaryState {
  error: Error | null;
  componentStack: string | null;
}

/** Catches rendering crashes so the user gets a code to report instead of a blank page. */
export class ErrorBoundary extends Component<{ children: ReactNode }, BoundaryState> {
  state: BoundaryState = { error: null, componentStack: null };

  static getDerivedStateFromError(error: Error): Partial<BoundaryState> {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    this.setState({ componentStack: info.componentStack ?? null });
    console.error('Sjakkportal crashed while rendering', error, info.componentStack);
  }

  render() {
    const { error, componentStack } = this.state;
    if (!error) return this.props.children;
    if (error instanceof ApiError) return <ProblemScreen error={error} />;
    return (
      <ErrorLayout
        title="Siden krasjet"
        message="Noe uventet gikk galt i nettleseren. Last inn siden på nytt; hvis det skjer igjen, send feilsøkingsinfoen til en administrator."
        code={CLIENT_ERRORS.RENDER}
        extra={{ error: `${error.name}: ${error.message}`, stack: error.stack?.split('\n').slice(0, 8).join('\n'), componentStack: componentStack?.slice(0, 1500) }}
      />
    );
  }
}
