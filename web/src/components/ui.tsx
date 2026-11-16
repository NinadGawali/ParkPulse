import { cloneElement, isValidElement, useCallback, useEffect, useId, useRef, useState, useSyncExternalStore, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import type { Problem } from '../api/types';
import { BOOKING_STATUS, LISTING_STATUS, PAYMENT_STATUS } from '../labels';
import { formatMoney, initials } from '../lib';
import { Icon, type IconName } from './Icon';

const DEV = import.meta.env.DEV;

// ------------------------------------------------------------------ errors (RFC 7807)
export function toProblem(e: unknown): Problem {
  if (e instanceof ApiError) return e.problem;
  return { type: 'about:blank', title: 'Something went wrong', status: 0, detail: e instanceof Error ? e.message : String(e) };
}

/** What the user sees for a status when the server gave nothing more specific. */
function friendly(p: Problem): { title: string; detail?: string } {
  switch (p.status) {
    case 0:
      return p.title === 'Network error'
        ? { title: "Can't reach ParkSmart", detail: 'Check your connection and try again.' }
        : { title: p.title, detail: p.detail };
    case 401:
      return { title: p.title === 'Unauthorized' ? 'Please log in again' : p.title, detail: p.detail };
    case 403:
      return { title: "You don't have access to this", detail: p.detail };
    case 429:
      return { title: 'Too many attempts', detail: p.detail ?? 'Please wait a few minutes and try again.' };
    case 500:
    case 502:
    case 503:
    case 504:
      return { title: 'Something went wrong on our side', detail: 'Please try again in a moment.' };
    default:
      return { title: p.title, detail: p.detail };
  }
}

const DEV_HINTS: Record<number, string> = {
  429: 'Dev: the gateway limits login/register per IP. Run `npm run dev:direct-auth` to bypass it locally.',
  503: 'Dev: a downstream service is unreachable; check logs/<service>.log.',
};

/** Renders an API error as a friendly alert; nothing when there is no error. */
export function ProblemView({ error, compact }: { error: unknown; compact?: boolean }) {
  if (!error) return null;
  const p = toProblem(error);
  const f = friendly(p);
  return (
    <div className="alert bad" role="alert">
      <Icon name="alert" />
      <div className="alert-body">
        <strong>{f.title}</strong>
        {f.detail && <span>{f.detail}</span>}
        {p.errors && p.errors.length > 0 && (
          <ul>
            {p.errors.map((fe, i) => (
              <li key={i}>
                <span className="strong">{fe.field}</span>: {fe.message}
              </li>
            ))}
          </ul>
        )}
        {!compact && p.correlationId && <span className="ref">Reference: {p.correlationId}</span>}
        {DEV && (
          <span className="ref">
            {p.status || 'network'} · {p.type}
            {DEV_HINTS[p.status] ? ` · ${DEV_HINTS[p.status]}` : ''}
          </span>
        )}
      </div>
    </div>
  );
}
export const ErrorAlert = ProblemView;

export function isStatus(e: unknown, status: number): boolean {
  return e instanceof ApiError && e.status === status;
}

// ------------------------------------------------------------------ data hooks
export interface Loaded<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  reload(): void;
  setData(d: T | null): void;
}
/** Runs `fn` on mount and whenever `deps` change; `reload()` runs it again (keeping the old data while loading). */
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

/** Wraps a mutating call: tracks busy + error. Returns undefined when the call failed. */
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
  const clear = useCallback(() => setError(null), []);
  return { busy, error, run, clear };
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

/** Current time, re-rendering every `ms`. */
export function useNow(ms = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

export function useDocumentTitle(title: string | null | undefined) {
  useEffect(() => {
    document.title = title ? `${title} · ParkSmart` : 'ParkSmart · Book parking near you';
  }, [title]);
}

// ------------------------------------------------------------------ toasts
type ToastItem = { id: number; text: string; kind: 'ok' | 'bad' };
let toasts: ToastItem[] = [];
let toastSeq = 0;
const toastListeners = new Set<() => void>();
const emitToasts = () => toastListeners.forEach((l) => l());
export function toast(text: string, kind: 'ok' | 'bad' = 'ok') {
  const id = ++toastSeq;
  toasts = [...toasts, { id, text, kind }];
  emitToasts();
  setTimeout(() => {
    toasts = toasts.filter((t) => t.id !== id);
    emitToasts();
  }, 3500);
}
export function Toaster() {
  const items = useSyncExternalStore(
    (l) => {
      toastListeners.add(l);
      return () => toastListeners.delete(l);
    },
    () => toasts,
  );
  return (
    <div className="toasts" role="status" aria-live="polite">
      {items.map((t) => (
        <div key={t.id} className={`toast ${t.kind === 'bad' ? 'bad' : ''}`}>
          <Icon name={t.kind === 'bad' ? 'alert' : 'checkCircle'} />
          {t.text}
        </div>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ primitives
type BtnVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'dark';
export function Button({
  variant = 'secondary',
  size,
  block,
  busy,
  icon,
  children,
  className,
  disabled,
  type = 'button',
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: BtnVariant; size?: 'sm' | 'lg'; block?: boolean; busy?: boolean; icon?: IconName }) {
  const cls = ['btn', variant !== 'secondary' ? `btn-${variant}` : '', size ? `btn-${size}` : '', block ? 'btn-block' : '', className ?? '']
    .filter(Boolean)
    .join(' ');
  return (
    <button type={type} className={cls} disabled={disabled || busy} aria-busy={busy || undefined} {...rest}>
      {busy ? <span className="spinner" /> : icon ? <Icon name={icon} size={size === 'sm' ? 15 : 17} /> : null}
      {children}
    </button>
  );
}

export function Spinner() {
  return <span className="spinner" aria-label="Loading" />;
}
export function Loading({ what }: { what?: string }) {
  return (
    <div className="loading-block">
      <Spinner /> Loading{what ? ` ${what}` : ''}…
    </div>
  );
}

export function Empty({ icon = 'inbox', title, children, action }: { icon?: IconName; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon">
        <Icon name={icon} size={24} />
      </div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Card({ title, children, actions, footer, pad = true, className }: { title?: ReactNode; children: ReactNode; actions?: ReactNode; footer?: ReactNode; pad?: boolean; className?: string }) {
  return (
    <section className={`card ${className ?? ''}`}>
      {(title || actions) && (
        <div className="card-head">
          {title && <h3>{title}</h3>}
          <div className="grow" />
          {actions}
        </div>
      )}
      <div className={pad ? 'card-body' : ''}>{children}</div>
      {footer && <div className="card-foot">{footer}</div>}
    </section>
  );
}

/**
 * Label + control + hint. A single input/select/textarea child gets the id, aria-describedby (hint/error) and
 * aria-invalid wired automatically; for a composite child pass `id` and put it on the inner control.
 */
export function Field({ label, hint, error, id: idProp, children }: { label: string; hint?: ReactNode; error?: string | null; id?: string; children: ReactNode }) {
  const auto = useId();
  let id = idProp ?? auto;
  const hintId = `${id}-hint`;
  const described = error || hint ? hintId : undefined;
  let content = children;
  if (isValidElement<Record<string, unknown>>(children) && typeof children.type === 'string' && ['input', 'select', 'textarea'].includes(children.type)) {
    id = (children.props.id as string | undefined) ?? id;
    content = cloneElement(children, { id, 'aria-describedby': described, 'aria-invalid': error ? true : undefined });
  }
  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      {content}
      {error ? (
        <span className="field-error" id={hintId}>
          {error}
        </span>
      ) : hint ? (
        <span className="field-hint" id={hintId}>
          {hint}
        </span>
      ) : null}
    </div>
  );
}

export function Alert({ kind = 'info', title, children }: { kind?: 'info' | 'warn' | 'ok' | 'bad'; title?: ReactNode; children?: ReactNode }) {
  const icon: IconName = kind === 'ok' ? 'checkCircle' : kind === 'info' ? 'info' : 'alert';
  return (
    <div className={`alert ${kind}`} role={kind === 'bad' ? 'alert' : undefined}>
      <Icon name={icon} />
      <div className="alert-body">
        {title && <strong>{title}</strong>}
        {children && <div>{children}</div>}
      </div>
    </div>
  );
}
/** Back-compat name used by older pages. */
export const Notice = ({ kind = 'info', children }: { kind?: 'info' | 'warn' | 'ok' | 'bad'; children: ReactNode }) => <Alert kind={kind}>{children}</Alert>;

export function Badge({ tone = 'muted', children }: { tone?: string; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}
export function StatusBadge({ kind, value }: { kind: 'booking' | 'listing' | 'payment'; value: string }) {
  const table = (kind === 'booking' ? BOOKING_STATUS : kind === 'listing' ? LISTING_STATUS : PAYMENT_STATUS) as Record<string, { label: string; tone: string }>;
  const meta = table[value] ?? { label: value.replace(/_/g, ' ').toLowerCase(), tone: 'muted' };
  return <span className={`badge ${meta.tone}`}>{meta.label}</span>;
}

export function Money({ value, currency = 'INR' }: { value?: string | null; currency?: string }) {
  return <span className="money">{formatMoney(value, currency)}</span>;
}

const dateFmt: Intl.DateTimeFormatOptions = { weekday: 'short', day: 'numeric', month: 'short' };
const timeFmt: Intl.DateTimeFormatOptions = { hour: 'numeric', minute: '2-digit' };
export function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, dateFmt);
}
export function fmtTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, timeFmt);
}
export function fmtDateTime(iso: string): string {
  return `${fmtDate(iso)}, ${fmtTime(iso)}`;
}
export function Time({ value }: { value?: string | null }) {
  if (!value) return <>–</>;
  return (
    <time dateTime={value} title={new Date(value).toString()}>
      {fmtDateTime(value)}
    </time>
  );
}
/** "Mon 3 Nov, 9:00 AM – 11:00 AM" or across days "Mon 3 Nov, 9:00 AM – Tue 4 Nov, 10:00 AM". */
export function DateRange({ start, end }: { start: string; end: string }) {
  const sameDay = new Date(start).toDateString() === new Date(end).toDateString();
  return (
    <span>
      {fmtDateTime(start)} – {sameDay ? fmtTime(end) : fmtDateTime(end)}
    </span>
  );
}

export function Avatar({ name, url, size = 40 }: { name?: string | null; url?: string | null; size?: number }) {
  const style = { width: size, height: size, fontSize: size * 0.38 };
  if (url) return <img className="avatar" src={url} alt={name ?? ''} style={style} />;
  return (
    <span className="avatar" style={style} aria-hidden>
      {initials(name)}
    </span>
  );
}

export function Stars({ value, size = 15 }: { value: number; size?: number }) {
  const full = Math.round(value);
  return (
    <span className="stars" aria-label={`${value} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map((i) => (
        <span key={i} className={i <= full ? '' : 'off'}>
          <Icon name="star" size={size} />
        </span>
      ))}
    </span>
  );
}
export function RatingInline({ avg, count }: { avg?: string | null; count: number }) {
  if (!count || !avg) return <span className="muted small">New</span>;
  return (
    <span className="rating-inline">
      <Icon name="star" size={14} />
      {Number(avg).toFixed(1)} <span className="muted">({count})</span>
    </span>
  );
}
export function StarInput({ value, onChange }: { value: number; onChange: (n: number) => void }) {
  return (
    <span className="star-input" role="radiogroup" aria-label="Rating">
      {[1, 2, 3, 4, 5].map((i) => (
        <button key={i} type="button" className={i <= value ? 'on' : ''} onClick={() => onChange(i)} role="radio" aria-checked={i === value} aria-label={`${i} star${i > 1 ? 's' : ''}`}>
          <Icon name="star" size={30} />
        </button>
      ))}
    </span>
  );
}

export function Switch({ checked, onChange, disabled, label }: { checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; label: string }) {
  return (
    <label className="switch">
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} aria-label={label} />
      <span />
    </label>
  );
}

export function Modal({ title, onClose, children, footer }: { title: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{title}</h3>
          <div className="grow" />
          <button type="button" className="btn btn-ghost btn-icon" onClick={onClose} aria-label="Close">
            <Icon name="x" />
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { value: T; label: ReactNode; count?: number }[] }) {
  return (
    <div className="tabs" role="tablist">
      {items.map((t) => (
        <button key={t.value} type="button" role="tab" aria-selected={t.value === value} className={`tab ${t.value === value ? 'on' : ''}`} onClick={() => onChange(t.value)}>
          {t.label}
          {t.count !== undefined && t.count > 0 && <span className="count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function Copy({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="link-btn"
      onClick={() => {
        void navigator.clipboard?.writeText(text).then(() => {
          setDone(true);
          setTimeout(() => setDone(false), 1200);
        });
      }}
    >
      {done ? 'Copied' : label}
    </button>
  );
}

export function LoadMore({ cursor, onMore, busy }: { cursor?: string | null; onMore: () => void; busy?: boolean }) {
  if (!cursor) return null;
  return (
    <div className="center">
      <Button onClick={onMore} busy={busy}>
        Show more
      </Button>
    </div>
  );
}

export function Json({ value }: { value: unknown }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}
