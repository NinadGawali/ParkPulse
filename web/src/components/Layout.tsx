import { Component, lazy, Suspense, useEffect, useRef, useState, type ErrorInfo, type ReactNode } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../state/auth';
import { notifications } from '../api/endpoints';
import type { AppNotification, Role } from '../api/types';
import { relativeTime } from '../lib';
import { usePublicProfile } from '../state/cache';
import { Avatar, Loading, Toaster, usePolling } from './ui';
import { Icon } from './Icon';

const DEV = import.meta.env.DEV;
/** Payments run against the FakeProcessor until a real adapter is configured (VITE_TEST_MODE=false hides the banner). */
export const TEST_MODE = import.meta.env.VITE_TEST_MODE !== 'false';
const DebugPanel = DEV ? lazy(() => import('./DebugPanel').then((m) => ({ default: m.DebugPanel }))) : null;

function useClickOutside(ref: React.RefObject<HTMLElement>, onOut: () => void, active: boolean) {
  useEffect(() => {
    if (!active) return;
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onOut();
    };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [ref, onOut, active]);
}

function NotificationBell() {
  const a = useAuth();
  const [unread, setUnread] = useState<number>(0);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<AppNotification[] | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useClickOutside(ref, () => setOpen(false), open);

  const refresh = () => {
    notifications
      .unread()
      .then((r) => setUnread(r.unread))
      .catch(() => undefined);
  };
  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a.accountId]);
  usePolling(refresh, 20000, a.loggedIn);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) {
      setItems(null);
      notifications
        .list()
        .then((p) => setItems(p.items.slice(0, 6)))
        .catch(() => setItems([]));
    }
  };

  return (
    <div className="dropdown" ref={ref}>
      <button type="button" className="icon-btn" onClick={toggle} aria-label={`Notifications${unread ? `, ${unread} unread` : ''}`}>
        <Icon name="bell" />
        {unread > 0 && <span className="dot-count">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {open && (
        <div className="dropdown-menu notif-menu">
          <div className="row between head">
            <strong>Notifications</strong>
            {unread > 0 && (
              <button
                type="button"
                className="link-btn small"
                onClick={async () => {
                  await notifications.readAll().catch(() => undefined);
                  setUnread(0);
                  setItems((cur) => cur?.map((n) => ({ ...n, readAt: n.readAt ?? new Date().toISOString() })) ?? null);
                }}
              >
                Mark all read
              </button>
            )}
          </div>
          {items === null && <div className="loading-block" style={{ padding: 20 }}>Loading…</div>}
          {items?.length === 0 && <p className="muted small" style={{ padding: '8px 12px 14px' }}>You're all caught up.</p>}
          {items?.map((n) => (
            <div key={n.id} className={`notif-item ${n.readAt ? '' : 'unread'}`}>
              <strong className="small">{n.title}</strong>
              <span className="small">{n.body}</span>
              <span className="tiny muted">{relativeTime(n.createdAt, Date.now())}</span>
            </div>
          ))}
          <div className="sep" />
          <Link to="/notifications" onClick={() => setOpen(false)}>
            See all notifications
          </Link>
        </div>
      )}
    </div>
  );
}

function UserMenu() {
  const a = useAuth();
  const nav = useNavigate();
  const me = usePublicProfile(a.accountId);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useClickOutside(ref, () => setOpen(false), open);
  const close = () => setOpen(false);
  const name = me?.displayName || a.account?.email?.split('@')[0] || 'Account';
  return (
    <div className="dropdown" ref={ref}>
      <button type="button" className="user-btn" onClick={() => setOpen(!open)} aria-haspopup="menu" aria-expanded={open}>
        <Avatar name={name} url={me?.photoUrl} size={30} />
        <span className="desktop-only">{name}</span>
        <Icon name="chevronDown" size={15} />
      </button>
      {open && (
        <div className="dropdown-menu" role="menu" onClick={close}>
          <div className="head">
            <div className="strong">{name}</div>
            <div className="small muted">{a.account?.email}</div>
          </div>
          <div className="sep" />
          <Link to="/bookings">
            <Icon name="calendar" /> My bookings
          </Link>
          <Link to="/settings/vehicles">
            <Icon name="car" /> Vehicles
          </Link>
          <Link to="/notifications">
            <Icon name="bell" /> Notifications
          </Link>
          <Link to="/settings">
            <Icon name="settings" /> Account settings
          </Link>
          {a.hasRole('HOST') ? (
            <>
              <div className="sep" />
              <Link to="/host">
                <Icon name="home" /> Host dashboard
              </Link>
              <Link to="/host/listings">
                <Icon name="list" /> My listings
              </Link>
              <Link to="/host/earnings">
                <Icon name="wallet" /> Earnings
              </Link>
            </>
          ) : (
            <>
              <div className="sep" />
              <Link to="/host">
                <Icon name="home" /> List your space
              </Link>
            </>
          )}
          {a.hasRole('ADMIN') && (
            <Link to="/admin">
              <Icon name="shield" /> Admin
            </Link>
          )}
          {DEV && (
            <Link to="/dev">
              <Icon name="key" /> Dev tools
            </Link>
          )}
          <div className="sep" />
          <button
            type="button"
            className="item"
            onClick={async () => {
              await a.logout();
              nav('/');
            }}
          >
            <Icon name="logout" /> Log out
          </button>
        </div>
      )}
    </div>
  );
}

function Header() {
  const a = useAuth();
  const loc = useLocation();
  const [mobile, setMobile] = useState(false);
  useEffect(() => {
    setMobile(false);
  }, [loc.pathname]);
  const links: { to: string; label: string; show: boolean }[] = [
    { to: '/search', label: 'Find parking', show: true },
    { to: '/bookings', label: 'My bookings', show: a.loggedIn },
    { to: '/host', label: a.hasRole('HOST') ? 'Hosting' : 'List your space', show: true },
    { to: '/admin', label: 'Admin', show: a.hasRole('ADMIN') },
  ];
  return (
    <header className="site-header">
      {TEST_MODE && (
        <div className="test-mode">
          <strong>Test mode</strong> · payments use test cards, no money moves.
        </div>
      )}
      <div className="container">
        <div className="bar">
          <Link to="/" className="site-brand" aria-label="ParkSmart home">
            <span className="brand-mark">P</span>
            ParkSmart
          </Link>
          <nav className="main-nav" aria-label="Main">
            {links
              .filter((l) => l.show)
              .map((l) => (
                <NavLink key={l.to} to={l.to} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                  {l.label}
                </NavLink>
              ))}
          </nav>
          <div className="header-actions">
            {!a.ready ? null : a.loggedIn ? (
              <>
                <NotificationBell />
                <UserMenu />
              </>
            ) : (
              <>
                <Link to="/login" state={{ from: loc.pathname + loc.search }} className="btn btn-ghost desktop-only">
                  Log in
                </Link>
                <Link to="/register" className="btn btn-primary">
                  Sign up
                </Link>
              </>
            )}
            <button type="button" className="icon-btn mobile-only" onClick={() => setMobile(!mobile)} aria-label="Menu" aria-expanded={mobile}>
              <Icon name={mobile ? 'x' : 'menu'} />
            </button>
          </div>
        </div>
        {mobile && (
          <nav className="mobile-nav mobile-only" aria-label="Mobile">
            {links
              .filter((l) => l.show)
              .map((l) => (
                <Link key={l.to} to={l.to}>
                  {l.label}
                </Link>
              ))}
            {!a.loggedIn && <Link to="/login">Log in</Link>}
            {a.loggedIn && <Link to="/settings">Account settings</Link>}
          </nav>
        )}
      </div>
    </header>
  );
}

function Footer() {
  return (
    <footer className="site-footer">
      <div className="container">
        <div className="cols">
          <div className="stack-sm">
            <span className="site-brand">
              <span className="brand-mark">P</span>
              ParkSmart
            </span>
            <p>Book private parking by the hour from people with a space to spare.</p>
          </div>
          <div>
            <h4>Drivers</h4>
            <Link to="/search">Find parking</Link>
            <Link to="/bookings">My bookings</Link>
            <Link to="/help#cancellation">Cancellation policies</Link>
          </div>
          <div>
            <h4>Hosts</h4>
            <Link to="/host">List your space</Link>
            <Link to="/help#hosting">How hosting works</Link>
            <Link to="/host/earnings">Earnings</Link>
          </div>
          <div>
            <h4>ParkSmart</h4>
            <Link to="/help">Help centre</Link>
            <Link to="/terms">Terms</Link>
            <Link to="/privacy">Privacy</Link>
          </div>
        </div>
        <div className="legal">
          <span>© {new Date().getFullYear()} ParkSmart</span>
          <span>Prices in INR, including all fees.</span>
        </div>
      </div>
    </footer>
  );
}

export function ScrollToTop() {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);
  return null;
}

/** Page frame. `bare` pages (search) fill the viewport and hide the footer. */
export function Layout({ bare }: { bare?: boolean }) {
  const loc = useLocation();
  useEffect(() => {
    if (DEV) document.body.classList.add('has-debug');
  }, []);
  return (
    <>
      <ScrollToTop />
      <Header />
      <main className="site-main">
        <ErrorBoundary resetKey={loc.pathname}>
          <Suspense fallback={<Loading />}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
      {!bare && <Footer />}
      <Toaster />
      {DebugPanel && (
        <Suspense fallback={null}>
          <DebugPanel />
        </Suspense>
      )}
    </>
  );
}

/**
 * Keeps a crash in one page from blanking the whole app (header and navigation stay usable). A new route
 * (resetKey) clears it. A failed lazy chunk after a deploy is solved by a reload.
 */
class ErrorBoundary extends Component<{ resetKey: string; children: ReactNode }, { error: Error | null; key: string }> {
  state = { error: null as Error | null, key: this.props.resetKey };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  static getDerivedStateFromProps(props: { resetKey: string }, state: { error: Error | null; key: string }) {
    return props.resetKey !== state.key ? { error: null, key: props.resetKey } : null;
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Page crashed', error, info.componentStack);
  }
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="container narrow page">
        <div className="empty">
          <div className="empty-icon">
            <Icon name="alert" size={24} />
          </div>
          <h3>Something went wrong on this page</h3>
          <p>Reloading usually fixes it. If it keeps happening, please contact support.</p>
          <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      </div>
    );
  }
}

/** Requires a session (redirects to /login, then back) and optionally a role. */
export function Protected({ role, children }: { role?: Role; children: ReactNode }) {
  const a = useAuth();
  const loc = useLocation();
  if (!a.ready) return <Loading />;
  if (!a.loggedIn) return <Navigate to="/login" replace state={{ from: loc.pathname + loc.search }} />;
  if (role === 'HOST' && !a.hasRole('HOST')) return <Navigate to="/host" replace />;
  if (role && !a.hasRole(role)) {
    return (
      <div className="container narrow page">
        <div className="empty">
          <div className="empty-icon">
            <Icon name="shield" size={24} />
          </div>
          <h3>You don't have access to this page</h3>
          <p>This area is for {role === 'ADMIN' ? 'ParkSmart staff' : role.toLowerCase() + 's'} only.</p>
          <Link className="btn" to="/">
            Go home
          </Link>
        </div>
      </div>
    );
  }
  return <>{children}</>;
}
