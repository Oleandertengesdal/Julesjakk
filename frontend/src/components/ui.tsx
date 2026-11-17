import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react';
import { useId } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../lib/api';
import { signed } from '../lib/format';
import type { PlayerRef } from '../lib/types';

type Variant = 'primary' | 'secondary' | 'quiet' | 'danger';

const VARIANT: Record<Variant, string> = {
  primary: 'bg-berry text-white hover:bg-berry-dark',
  secondary: 'bg-spruce text-snow hover:bg-spruce-2',
  quiet: 'border border-mist bg-white text-ink hover:border-spruce',
  danger: 'border border-berry text-berry hover:bg-berry hover:text-white',
};

export function Button({ variant = 'secondary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${VARIANT[variant]} ${className}`}
    />
  );
}

interface FieldShell {
  label: string;
  hint?: string;
  error?: string;
  children: (id: string, describedBy: string | undefined) => ReactNode;
}

function Field({ label, hint, error, children }: FieldShell) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-semibold">{label}</label>
      {children(id, describedBy)}
      {hint && !error && <p id={`${id}-hint`} className="text-sm text-slate">{hint}</p>}
      {error && <p id={`${id}-error`} className="text-sm font-semibold text-berry">{error}</p>}
    </div>
  );
}

const CONTROL = 'rounded-md border border-mist bg-white px-3 py-2 text-ink placeholder:text-slate/70 focus:border-spruce';

export function TextField({ label, hint, error, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string; error?: string }) {
  return (
    <Field label={label} hint={hint} error={error}>
      {(id, describedBy) => <input id={id} aria-describedby={describedBy} aria-invalid={!!error} className={CONTROL} {...props} />}
    </Field>
  );
}

export function TextArea({ label, hint, error, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; hint?: string; error?: string }) {
  return (
    <Field label={label} hint={hint} error={error}>
      {(id, describedBy) => <textarea id={id} aria-describedby={describedBy} aria-invalid={!!error} className={`${CONTROL} font-mono text-sm`} {...props} />}
    </Field>
  );
}

export function SelectField({ label, hint, error, children, ...props }: SelectHTMLAttributes<HTMLSelectElement> & { label: string; hint?: string; error?: string }) {
  return (
    <Field label={label} hint={hint} error={error}>
      {(id, describedBy) => (
        <select id={id} aria-describedby={describedBy} aria-invalid={!!error} className={CONTROL} {...props}>{children}</select>
      )}
    </Field>
  );
}

/** A server error inline, with its code small so it can be reported. */
export function ErrorNotice({ error }: { error: ApiError | null }) {
  if (!error) return null;
  return (
    <div role="alert" className="rounded-md border-l-4 border-berry bg-white px-4 py-3">
      <p className="font-semibold text-berry">{error.detail}</p>
      {error.retryAfterSeconds !== null && <p className="text-sm text-slate">Prøv igjen om {error.retryAfterSeconds} sekunder.</p>}
      <p className="mt-1 text-xs text-slate tabular">{error.code}{error.traceId ? ` / ${error.traceId}` : ''}</p>
    </div>
  );
}

export function Loading({ label = 'Laster' }: { label?: string }) {
  return <p role="status" className="py-8 text-slate">{label}…</p>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="rounded-md border border-dashed border-mist px-5 py-6 text-slate">{children}</div>;
}

export function PlayerLink({ player, className = '' }: { player: PlayerRef | null | undefined; className?: string }) {
  if (!player) return <span className="text-slate">Fri</span>;
  return <Link to={`/players/${player.id}`} className={`font-semibold hover:underline ${className}`}>{player.displayName}</Link>;
}

export function Delta({ value, className = '' }: { value: number | null | undefined; className?: string }) {
  if (value === null || value === undefined) return null;
  const tone = value > 0 ? 'text-moss' : value < 0 ? 'text-berry' : 'text-slate';
  return <span className={`tabular font-semibold ${tone} ${className}`}>{signed(value)}</span>;
}

export function LiveDot({ label = 'Direkte' }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-sm font-semibold text-berry">
      <span className="live-dot inline-block h-2.5 w-2.5 rounded-full bg-berry" aria-hidden="true" />
      {label}
    </span>
  );
}

export function PageTitle({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <h1 className="text-3xl font-extrabold text-spruce sm:text-4xl">{children}</h1>
      {aside}
    </div>
  );
}

export function Tabs<T extends string>({ value, options, onChange, label }: {
  value: T; options: { value: T; label: string }[]; onChange: (value: T) => void; label: string;
}) {
  return (
    <div role="tablist" aria-label={label} className="inline-flex rounded-md border border-mist bg-white p-1">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="tab"
          aria-selected={value === option.value}
          onClick={() => onChange(option.value)}
          className={`rounded px-3 py-1.5 text-sm font-semibold ${value === option.value ? 'bg-spruce text-snow' : 'text-slate hover:text-ink'}`}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/** "W D L" form as small squares. */
export function Form({ results }: { results: string[] }) {
  if (results.length === 0) return <span className="text-sm text-slate">Ingen partier</span>;
  const tone: Record<string, string> = { W: 'bg-moss text-white', D: 'bg-mist text-ink', L: 'bg-berry text-white' };
  const label: Record<string, string> = { W: 'S', D: 'R', L: 'T' };
  const word: Record<string, string> = { W: 'seier', D: 'remis', L: 'tap' };
  return (
    <span className="inline-flex gap-1" aria-label={`Siste partier: ${results.map((r) => word[r]).join(', ')}`}>
      {results.map((r, i) => (
        <span key={i} className={`grid h-6 w-6 place-items-center rounded text-xs font-bold ${tone[r]}`} aria-hidden="true">{label[r]}</span>
      ))}
    </span>
  );
}
