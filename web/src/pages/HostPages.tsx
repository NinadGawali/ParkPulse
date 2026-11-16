import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { auth, bookings, listings, payments, profile } from '../api/endpoints';
import type { ListingStatus } from '../api/types';
import { PAYMENT_STATUS } from '../labels';
import { formatMoney } from '../lib';
import { Icon } from '../components/Icon';
import { usePaged } from '../components/paged';
import {
  Button,
  Card,
  Empty,
  Field,
  Loading,
  LoadMore,
  ProblemView,
  StatusBadge,
  Tabs,
  toast,
  useAction,
  useDocumentTitle,
  useLoad,
  usePolling,
} from '../components/ui';
import { useAuth } from '../state/auth';
import { BookingRow } from './BookingPages';
import { TEST_MODE } from '../components/Layout';

// ------------------------------------------------------------------ /host
export function HostHome() {
  const a = useAuth();
  if (!a.ready) return <Loading />;
  return a.hasRole('HOST') ? <HostDashboard /> : <BecomeHost />;
}

function BecomeHost() {
  useDocumentTitle('Host your parking space');
  const a = useAuth();
  const nav = useNavigate();
  const act = useAction();
  const verified = !!a.account?.emailVerified;
  return (
    <>
      <section className="hero" style={{ padding: '64px 0 72px' }}>
        <div className="container">
          <span className="eyebrow" style={{ color: '#7ee0cf' }}>
            Become a host
          </span>
          <h1 style={{ marginTop: 10 }}>Turn your empty parking space into income.</h1>
          <p className="lead">Driveways, garages, society parking, office lots after hours. You set the price and the hours; we bring the drivers and handle payments.</p>
        </div>
      </section>
      <div className="container page">
        <div className="with-aside">
          <div className="grid-3" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))' }}>
            {[
              { icon: 'edit' as const, t: 'List for free', d: 'Add photos, a price and your opening hours. Our team reviews every listing.' },
              { icon: 'calendar' as const, t: 'You stay in control', d: 'Instant booking or approve each request. Block dates whenever you need the space.' },
              { icon: 'wallet' as const, t: 'Get paid weekly', d: 'Earnings are paid out every week, minus a 15% service fee. No other charges.' },
            ].map((f) => (
              <div key={f.t} className="feature">
                <div className="feature-icon">
                  <Icon name={f.icon} size={22} />
                </div>
                <h3>{f.t}</h3>
                <p className="muted">{f.d}</p>
              </div>
            ))}
          </div>
          <Card title="Get started">
            {!a.loggedIn ? (
              <div className="stack">
                <p>Create a free account or log in to start hosting.</p>
                <Link to="/register" state={{ from: '/host' }} className="btn btn-primary btn-block">
                  Sign up
                </Link>
                <Link to="/login" state={{ from: '/host' }} className="btn btn-block">
                  Log in
                </Link>
              </div>
            ) : (
              <div className="stack">
                <ul className="checklist">
                  <li className={verified ? 'done' : ''}>
                    <span className="tick">
                      <Icon name="check" size={13} />
                    </span>
                    <span className="label">
                      Verify your email {!verified && <Link to="/settings/security">Verify now</Link>}
                    </span>
                  </li>
                </ul>
                <Button
                  variant="primary"
                  block
                  size="lg"
                  busy={act.busy}
                  disabled={!verified}
                  onClick={async () => {
                    const ok = await act.run(async () => {
                      await auth.becomeHost();
                      await a.refreshRoles();
                      return true;
                    });
                    if (ok) {
                      toast('Welcome, host!');
                      nav('/host');
                    }
                  }}
                >
                  Become a host
                </Button>
                <ProblemView error={act.error} compact />
              </div>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}

/** What a host still needs before a listing can go live. */
function useHostChecklist() {
  const a = useAuth();
  const me = useLoad(() => profile.me().catch(() => null), [a.accountId]);
  const payouts = useLoad(() => profile.payoutAccounts().catch(() => ({ items: [] })), [a.accountId]);
  const items = [
    { done: !!me.data?.displayName, label: 'Add your name', to: '/settings/profile' },
    { done: !!me.data?.hasPhoto, label: 'Add a profile photo (required to publish)', to: '/settings/profile' },
    { done: !!a.account?.phoneVerified, label: 'Verify your phone number (required to publish)', to: '/settings/security' },
    { done: (payouts.data?.items.length ?? 0) > 0, label: 'Add a payout account to get paid', to: '/host/earnings' },
  ];
  return { items, loading: me.loading || payouts.loading };
}

function HostDashboard() {
  useDocumentTitle('Host dashboard');
  const a = useAuth();
  const me = useLoad(() => profile.me().catch(() => null), [a.accountId]);
  const check = useHostChecklist();
  const earnings = useLoad(() => payments.earnings().catch(() => null));
  const requests = useLoad(() => bookings.list('host', 'all', 'AWAITING_HOST_APPROVAL').then((p) => p.items));
  const upcoming = useLoad(() => bookings.list('host', 'upcoming').then((p) => p.items.filter((b) => ['CONFIRMED', 'CHECKED_IN'].includes(b.status)).slice(0, 5)));
  const mine = useLoad(() => listings.mine().then((p) => p.items));
  usePolling(requests.reload, 20000);
  const live = mine.data?.filter((l) => l.status === 'PUBLISHED').length ?? 0;
  const openTasks = check.items.filter((i) => !i.done);

  return (
    <div className="container page">
      <div className="page-head">
        <div>
          <h1>Welcome back{me.data?.displayName ? `, ${me.data.displayName.split(' ')[0]}` : ''}</h1>
          <p className="sub">Here's what's happening with your spaces.</p>
        </div>
        <div className="grow" />
        <Link to="/host/listings/new" className="btn btn-primary">
          <Icon name="plus" size={17} /> New listing
        </Link>
      </div>
      <div className="stack-lg">
        {!check.loading && openTasks.length > 0 && (
          <Card title="Finish setting up">
            <ul className="checklist">
              {check.items.map((i) => (
                <li key={i.label} className={i.done ? 'done' : ''}>
                  <span className="tick">
                    <Icon name="check" size={13} />
                  </span>
                  <span className="label">{i.label}</span>
                  {!i.done && (
                    <Link to={i.to} className="small">
                      Do it
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          </Card>
        )}

        <div className="stat-grid">
          <div className="stat">
            <span className="stat-label">Total earned</span>
            <span className="stat-value">{formatMoney(earnings.data?.totalEarned ?? '0')}</span>
            <span className="stat-note">After service fees</span>
          </div>
          <div className="stat">
            <span className="stat-label">Next payout</span>
            <span className="stat-value">{formatMoney(earnings.data?.pendingPayout ?? '0')}</span>
            <span className="stat-note">Paid weekly</span>
          </div>
          <div className="stat">
            <span className="stat-label">Live listings</span>
            <span className="stat-value">{live}</span>
            <span className="stat-note">{(mine.data?.length ?? 0) - live} not live</span>
          </div>
          <div className="stat">
            <span className="stat-label">Requests waiting</span>
            <span className="stat-value">{requests.data?.length ?? 0}</span>
            <span className="stat-note">Respond within 24 h</span>
          </div>
        </div>

        {requests.data && requests.data.length > 0 && (
          <section className="stack-sm">
            <h3>Booking requests</h3>
            <div className="list-cards">
              {requests.data.map((b) => (
                <BookingRow key={b.id} b={b} perspective="host" />
              ))}
            </div>
          </section>
        )}

        <section className="stack-sm">
          <div className="row between">
            <h3>Coming up</h3>
            <Link to="/host/bookings" className="small">
              All bookings
            </Link>
          </div>
          {upcoming.loading ? (
            <Loading />
          ) : upcoming.data && upcoming.data.length > 0 ? (
            <div className="list-cards">
              {upcoming.data.map((b) => (
                <BookingRow key={b.id} b={b} perspective="host" />
              ))}
            </div>
          ) : (
            <div className="card card-pad muted">No upcoming bookings yet.</div>
          )}
        </section>

        <section className="stack-sm">
          <div className="row between">
            <h3>Your listings</h3>
            <Link to="/host/listings" className="small">
              Manage listings
            </Link>
          </div>
          {mine.data && mine.data.length === 0 ? (
            <Empty icon="home" title="You haven't listed a space yet" action={<Link className="btn btn-primary" to="/host/listings/new">Create your first listing</Link>}>
              It takes about five minutes.
            </Empty>
          ) : (
            <ListingGrid items={(mine.data ?? []).filter((l) => l.status !== 'ARCHIVED').slice(0, 6)} />
          )}
        </section>
      </div>
    </div>
  );
}

function ListingGrid({ items }: { items: { id: string; title: string; status: ListingStatus; thumbnailUrl?: string | null; pricePerHour: string; currency: string }[] }) {
  return (
    <div className="grid-auto">
      {items.map((l) => (
        <Link key={l.id} to={`/host/listings/${l.id}`} className="lcard">
          <div className="lcard-img">
            {l.thumbnailUrl ? <img src={l.thumbnailUrl} alt="" /> : <div className="noimg"><Icon name="image" size={32} /></div>}
            <div className="badges">
              <StatusBadge kind="listing" value={l.status} />
            </div>
          </div>
          <div className="lcard-title">
            <span>{l.title}</span>
          </div>
          <div className="lcard-meta">{formatMoney(l.pricePerHour, l.currency)} / hour</div>
        </Link>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ /host/listings
const LISTING_TABS: { value: string; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'PUBLISHED', label: 'Live' },
  { value: 'PENDING_REVIEW', label: 'In review' },
  { value: 'DRAFT', label: 'Drafts' },
  { value: 'REJECTED', label: 'Changes needed' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'ARCHIVED', label: 'Archived' },
];
export function HostListingsPage() {
  useDocumentTitle('Your listings');
  const [status, setStatus] = useState('');
  const p = usePaged((c) => listings.mine(status || undefined, c), [status]);
  const items = status ? p.items : p.items.filter((l) => l.status !== 'ARCHIVED');
  return (
    <div className="container page">
      <div className="page-head">
        <div>
          <h1>Your listings</h1>
          <p className="sub">Edit details, photos and opening hours, or pause a space.</p>
        </div>
        <div className="grow" />
        <Link className="btn btn-primary" to="/host/listings/new">
          <Icon name="plus" size={17} /> New listing
        </Link>
      </div>
      <Tabs value={status} onChange={setStatus} items={LISTING_TABS} />
      <ProblemView error={p.error} />
      {p.loading && p.items.length === 0 ? (
        <Loading />
      ) : items.length === 0 ? (
        <Empty icon="home" title="Nothing here" action={<Link className="btn btn-primary" to="/host/listings/new">Create a listing</Link>} />
      ) : (
        <ListingGrid items={items} />
      )}
      <div style={{ marginTop: 20 }}>
        <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ /host/bookings
export function HostBookingsPage() {
  useDocumentTitle('Bookings');
  const [tab, setTab] = useState<'requests' | 'upcoming' | 'past'>('requests');
  const p = usePaged(
    (c) => (tab === 'requests' ? bookings.list('host', 'all', 'AWAITING_HOST_APPROVAL', c) : bookings.list('host', tab, undefined, c)),
    [tab],
  );
  usePolling(p.reload, 15000, tab !== 'past');
  return (
    <div className="container narrow page">
      <div className="page-head">
        <div>
          <h1>Bookings</h1>
          <p className="sub">Requests to approve and drivers parking at your spaces.</p>
        </div>
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'requests', label: 'Requests' },
          { value: 'upcoming', label: 'Upcoming' },
          { value: 'past', label: 'Past' },
        ]}
      />
      <ProblemView error={p.error} />
      {p.loading && p.items.length === 0 ? (
        <Loading />
      ) : p.items.length === 0 ? (
        <Empty icon="calendar" title={tab === 'requests' ? 'No requests waiting' : tab === 'upcoming' ? 'No upcoming bookings' : 'No past bookings'} />
      ) : (
        <div className="list-cards">
          {p.items.map((b) => (
            <BookingRow key={b.id} b={b} perspective="host" />
          ))}
        </div>
      )}
      <div style={{ marginTop: 16 }}>
        <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ /host/earnings
export function EarningsPage() {
  useDocumentTitle('Earnings');
  const e = useLoad(() => payments.earnings());
  const p = usePaged((c) => payments.payouts(c));
  return (
    <div className="container page">
      <div className="page-head">
        <div>
          <h1>Earnings</h1>
          <p className="sub">Paid out weekly for completed and confirmed bookings, after the 15% service fee.</p>
        </div>
      </div>
      <div className="stack-lg">
        <ProblemView error={e.error} />
        <div className="stat-grid">
          <div className="stat">
            <span className="stat-label">Total earned</span>
            <span className="stat-value">{formatMoney(e.data?.totalEarned ?? '0')}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Paid out</span>
            <span className="stat-value">{formatMoney(e.data?.totalPaidOut ?? '0')}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Next payout</span>
            <span className="stat-value">{formatMoney(e.data?.pendingPayout ?? '0')}</span>
          </div>
        </div>
        <div className="with-aside">
          <Card title="Payout history" pad={false}>
            <ProblemView error={p.error} />
            {p.items.length === 0 && !p.loading ? (
              <Empty icon="wallet" title="No payouts yet">
                Your first payout is sent in the weekly run after your first booking.
              </Empty>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Period</th>
                      <th>Amount</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {p.items.map((x) => (
                      <tr key={x.id}>
                        <td>
                          {x.periodStart} – {x.periodEnd}
                        </td>
                        <td className="strong">{formatMoney(x.amount, x.currency)}</td>
                        <td>
                          <span className={`badge ${PAYMENT_STATUS[x.status]?.tone ?? ''}`}>{PAYMENT_STATUS[x.status]?.label ?? x.status}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div style={{ padding: 12 }}>
              <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
            </div>
          </Card>
          <PayoutAccounts />
        </div>
      </div>
    </div>
  );
}

const ALL_PROCESSORS: { value: string; label: string; testOnly?: boolean }[] = [
  { value: 'FAKE', label: 'Test account', testOnly: true },
  { value: 'RAZORPAY_ROUTE', label: 'Razorpay' },
  { value: 'STRIPE_CONNECT', label: 'Stripe' },
];
// The FAKE processor is only accepted by the local/test backend.
const PROCESSORS = ALL_PROCESSORS.filter((p) => !p.testOnly || TEST_MODE);
function PayoutAccounts() {
  const list = useLoad(() => profile.payoutAccounts());
  const add = useAction();
  const del = useAction();
  const [adding, setAdding] = useState(false);
  const [type, setType] = useState(PROCESSORS[0].value);
  const [token, setToken] = useState('');
  const label = (t: string) => ALL_PROCESSORS.find((p) => p.value === t)?.label ?? t;
  return (
    <Card title="Payout account">
      <div className="stack">
        <ProblemView error={list.error} compact />
        {list.data?.items.map((x) => (
          <div key={x.id} className="row between">
            <span className="row">
              <Icon name="wallet" />
              <span>
                <span className="strong">{label(x.processorType)}</span> <span className="muted">{x.tokenHint}</span>
                <br />
                <span className={`small ${x.verified ? 'ok-text' : 'muted'}`}>{x.verified ? 'Verified' : 'Verification pending'}</span>
              </span>
            </span>
            <Button
              size="sm"
              variant="ghost"
              icon="trash"
              aria-label="Remove payout account"
              busy={del.busy}
              onClick={async () => {
                if (!confirm('Remove this payout account?')) return;
                await del.run(() => profile.deletePayoutAccount(x.id));
                list.reload();
              }}
            />
          </div>
        ))}
        {list.data?.items.length === 0 && !adding && <p className="muted small">Add an account so we can pay you. Only a token from the payment provider is stored, never your bank details.</p>}
        <ProblemView error={del.error} compact />
        {adding ? (
          <form
            className="form"
            onSubmit={async (e) => {
              e.preventDefault();
              const r = await add.run(() => profile.addPayoutAccount(type, token.trim()));
              if (r) {
                setAdding(false);
                setToken('');
                toast('Payout account added');
                list.reload();
              }
            }}
          >
            <Field label="Provider">
              <select value={type} onChange={(e) => setType(e.target.value)}>
                {PROCESSORS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Account ID from the provider" hint="Letters, digits, _ and -; at least 6 characters.">
              <input value={token} onChange={(e) => setToken(e.target.value)} required minLength={6} pattern="[A-Za-z0-9_\-]+" placeholder={type === 'FAKE' ? 'test_payout_account' : 'acc_…'} />
            </Field>
            <ProblemView error={add.error} compact />
            <div className="row">
              <Button type="submit" variant="primary" busy={add.busy}>
                Save
              </Button>
              <Button variant="ghost" onClick={() => setAdding(false)}>
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          (list.data?.items.length ?? 0) < 3 && (
            <Button icon="plus" onClick={() => setAdding(true)}>
              Add payout account
            </Button>
          )
        )}
      </div>
    </Card>
  );
}
