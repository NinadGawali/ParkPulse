import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import type { Problem } from '../api/types';

// ------------------------------------------------------------------ problem display
export function toProblem(e: unknown): Problem {
  if (e instanceof ApiError) return e.problem;
  return { type: 'about:blank', title: 'Unexpected error', status: 0, detail: e instanceof Error ? e.message : String(e) };
}

const HINTS: Record<number, string> = {
  401: 'Not logged in or the session expired.',
  403: 'Your role or ownership does not allow this.',
  429: 'Rate limited by the gateway (login/register: 5 burst, then 1 per 3 minutes per IP). Run `npm run dev:direct-auth` to bypass it locally.',
  503: 'A downstream service is unreachable; check logs/<service>.log.',
};

export function ProblemView({ error }: { error: unknown }) {
  if (!error) return null;
  const p = toProblem(error);
  const hint = HINTS[p.status];
  return (
    <div className="problem" role="alert">
      <div className="problem-head">
        <span className="problem-status">{p.status || 'ERR'}</span>
        <strong>{p.title}</strong>
      </div>
      {p.detail && <div className="problem-detail">{p.detail}</div>}
      <dl className="problem-meta">
        <dt>type</dt>
        <dd>{p.type}</dd>
        {p.correlationId && (
          <>
            <dt>correlationId</dt>
            <dd>{p.correlationId}</dd>
          </>
        )}
        {p.instance && (
          <>
            <dt>instance</dt>
            <dd>{p.instance}</dd>
          </>
        )}
      </dl>
      {p.errors && p.errors.length > 0 && (
        <ul className="problem-fields">
          {p.errors.map((f, i) => (
            <li key={i}>
              <code>{f.field}</code>: {f.message}
            </li>
          ))}
        </ul>
      )}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

// ------------------------------------------------------------------ data hooks
export interface Loaded<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  reload(): void;
  setData(d: T | null): void;
}
/** Runs `fn` on mount and whenever `deps` change; `reload()` runs it again. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []): Loaded<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;
  useEffect(() => {
    let alive = true;
    setLoading(true);
    fnRef
      .current()
      .then((d) => {
        if (alive) {
          setData(d);
          setError(null);
        }
      })
      .catch((e) => alive && setError(e))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, reload, setData };
}

/** Wraps a mutating call: tracks busy + error, optional success callback. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const run = useCallback(async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(e);
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  return { busy, error, run, clear: () => setError(null) };
}

/** Calls `fn` every `ms` while mounted and while `active` is true. */
export function usePolling(fn: () => void, ms: number, active = true) {
  const ref = useRef(fn);
  ref.current = fn;
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => ref.current(), ms);
    return () => clearInterval(id);
  }, [ms, active]);
}

// ------------------------------------------------------------------ small widgets
export function Loading({ what }: { what?: string }) {
  return <p className="muted">Loading{what ? ` ${what}` : ''}...</p>;
}

export function Card({ title, children, actions }: { title?: ReactNode; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <div className="card-head">
          {title && <h3>{title}</h3>}
          <div className="grow" />
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

const STATUS_CLASS: Record<string, string> = {
  PUBLISHED: 'ok',
  CONFIRMED: 'ok',
  COMPLETED: 'ok',
  CAPTURED: 'ok',
  AUTHORIZED: 'info',
  PAID: 'ok',
  READY: 'ok',
  ACTIVE: 'ok',
  CHECKED_IN: 'info',
  PENDING_REVIEW: 'warn',
  PENDING_PAYMENT: 'warn',
  AWAITING_HOST_APPROVAL: 'warn',
  PENDING: 'warn',
  CREATED: 'warn',
  PENDING_UPLOAD: 'warn',
  PENDING_VERIFICATION: 'warn',
  DRAFT: 'muted',
  PAUSED: 'muted',
  ARCHIVED: 'muted',
  REJECTED: 'bad',
  FAILED: 'bad',
  PAYMENT_FAILED: 'bad',
  EXPIRED: 'bad',
  NO_SHOW: 'bad',
  VOIDED: 'muted',
  REFUNDED: 'info',
  PARTIALLY_REFUNDED: 'info',
  SUSPENDED: 'bad',
  CANCELLED_BY_DRIVER: 'muted',
  CANCELLED_BY_HOST: 'muted',
  DECLINED_BY_HOST: 'muted',
};
export function Badge({ value }: { value: string }) {
  return <span className={`badge ${STATUS_CLASS[value] ?? 'muted'}`}>{value.replace(/_/g, ' ')}</span>;
}

export function Money({ value, currency = 'INR' }: { value?: string | null; currency?: string }) {
  if (value === undefined || value === null) return <>-</>;
  return (
    <span className="money">
      {currency === 'INR' ? '₹' : currency + ' '}
      {value}
    </span>
  );
}

export function Time({ value, zone }: { value?: string | null; zone?: string }) {
  if (!value) return <>-</>;
  const d = new Date(value);
  const local = d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
  return (
    <time dateTime={value} title={value + (zone ? ` (listing zone ${zone})` : '')}>
      {local}
    </time>
  );
}

export function Json({ value }: { value: unknown }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}

export function Notice({ kind = 'info', children }: { kind?: 'info' | 'warn' | 'ok' | 'bad'; children: ReactNode }) {
  return <div className={`notice ${kind}`}>{children}</div>;
}

export function Copy({ text, label = 'copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="link"
      onClick={() => {
        void navigator.clipboard?.writeText(text).then(() => {
          setDone(true);
          setTimeout(() => setDone(false), 1200);
        });
      }}
    >
      {done ? 'copied' : label}
    </button>
  );
}

export function LoadMore({ cursor, onMore, busy }: { cursor?: string | null; onMore: () => void; busy?: boolean }) {
  if (!cursor) return null;
  return (
    <button type="button" onClick={onMore} disabled={busy}>
      {busy ? 'Loading...' : 'Load more'}
    </button>
  );
}
