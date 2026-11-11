import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../state/auth';
import { notifications } from '../api/endpoints';
import { usePolling } from './ui';
import { DebugPanel } from './DebugPanel';
import type { Role } from '../api/types';

function Item({ to, children, end }: { to: string; children: ReactNode; end?: boolean }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => (isActive ? 'active' : '')}>
      {children}
    </NavLink>
  );
}

export function Layout() {
  const a = useAuth();
  const nav = useNavigate();
  const [unread, setUnread] = useState<number | null>(null);

  const refresh = () => {
    if (!a.loggedIn) return;
    notifications
      .unread()
      .then((r) => setUnread(r.unread))
      .catch(() => undefined);
  };
  useEffect(() => {
    if (a.loggedIn) refresh();
    else setUnread(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a.loggedIn, a.accountId]);
  usePolling(refresh, 20000, a.loggedIn);

  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
          ParkSmart <span className="muted">test console</span>
        </Link>
        <nav className="menu">
          <div className="menu-group">
            <Item to="/search">Search</Item>
            {a.loggedIn && a.hasRole('DRIVER') && (
              <>
                <Item to="/bookings">My bookings</Item>
                <Item to="/vehicles">Vehicles</Item>
              </>
            )}
          </div>
          {a.loggedIn && a.hasRole('HOST') && (
            <div className="menu-group">
              <span className="menu-label">Host</span>
              <Item to="/host/listings">Listings</Item>
              <Item to="/host/listings/new">New listing</Item>
              <Item to="/host/bookings">Requests</Item>
              <Item to="/host/earnings">Earnings</Item>
              <Item to="/host/payout-accounts">Payout account</Item>
            </div>
          )}
          {a.loggedIn && a.hasRole('ADMIN') && (
            <div className="menu-group">
              <span className="menu-label">Admin</span>
              <Item to="/admin/moderation">Moderation</Item>
              <Item to="/admin/bookings">Booking case</Item>
              <Item to="/admin/reviews">Hide review</Item>
              <Item to="/admin/not-implemented">Not implemented</Item>
            </div>
          )}
          <div className="menu-group">
            <Item to="/dev">Dev helper</Item>
          </div>
        </nav>
        <div className="grow" />
        {a.loggedIn ? (
          <div className="menu-group right">
            <Item to="/notifications">
              Notifications{unread ? <span className="count">{unread}</span> : null}
            </Item>
            <Item to="/account">{a.account?.email ?? 'Account'}</Item>
            <Item to="/profile">Profile</Item>
            <span className="roles">{a.roles.join(' / ')}</span>
            <button
              type="button"
              onClick={async () => {
                await a.logout();
                nav('/login');
              }}
            >
              Log out
            </button>
          </div>
        ) : (
          <div className="menu-group right">
            <Item to="/login">Log in</Item>
            <Item to="/register">Register</Item>
          </div>
        )}
      </header>
      <main className="page">
        <Outlet />
      </main>
      <DebugPanel />
    </>
  );
}

/** Redirects to /login (remembering where we were) unless logged in, and 403s on a missing role. */
export function Protected({ role, children }: { role?: Role; children: ReactNode }) {
  const a = useAuth();
  const loc = useLocation();
  if (!a.loggedIn) return <Navigate to="/login" replace state={{ from: loc.pathname + loc.search }} />;
  if (role && !a.hasRole(role)) {
    return (
      <div className="notice bad">
        <strong>403 (client-side check)</strong>: this page needs the {role} role. Your token has: {a.roles.join(', ') || 'none'}.
        {role === 'HOST' && (
          <>
            {' '}
            <Link to="/account">Become a host</Link> (needs a verified email).
          </>
        )}
        {role === 'ADMIN' && (
          <>
            {' '}
            See <Link to="/dev">Dev helper</Link> for how to grant ADMIN locally.
          </>
        )}
      </div>
    );
  }
  return <>{children}</>;
}
