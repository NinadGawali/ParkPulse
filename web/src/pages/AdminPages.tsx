import { useRef, useState } from 'react';
import { Link, NavLink, useSearchParams } from 'react-router-dom';
import { admin } from '../api/endpoints';
import { newIdempotencyKey } from '../api/client';
import type { BookingCase } from '../api/types';
import { formatMoney, relativeTime } from '../lib';
import { Icon } from '../components/Icon';
import { usePaged } from '../components/paged';
import {
  Alert,
  Avatar,
  Button,
  Card,
  DateRange,
  Empty,
  Field,
  Loading,
  LoadMore,
  ProblemView,
  RatingInline,
  StatusBadge,
  toast,
  useAction,
  useDocumentTitle,
  useLoad,
} from '../components/ui';

function AdminShell({ title, sub, children }: { title: string; sub: string; children: React.ReactNode }) {
  useDocumentTitle(`Admin · ${title}`);
  return (
    <div className="container page">
      <div className="page-head">
        <div>
          <span className="eyebrow">ParkSmart admin</span>
          <h1>{title}</h1>
          <p className="sub">{sub}</p>
        </div>
      </div>
      <div className="side-layout">
        <nav className="side-nav" aria-label="Admin">
          <NavLink to="/admin" end className={({ isActive }) => (isActive ? 'active' : '')}>
            <Icon name="flag" /> Listing reviews
          </NavLink>
          <NavLink to="/admin/bookings" className={({ isActive }) => (isActive ? 'active' : '')}>
            <Icon name="receipt" /> Bookings & refunds
          </NavLink>
          <NavLink to="/admin/reviews" className={({ isActive }) => (isActive ? 'active' : '')}>
            <Icon name="star" /> Ratings & reviews
          </NavLink>
        </nav>
        <div className="stack">{children}</div>
      </div>
    </div>
  );
}

export function ModerationPage() {
  const q = usePaged((c) => admin.queue(c));
  return (
    <AdminShell title="Listing reviews" sub="New and relocated listings waiting for approval, oldest first.">
      <div className="row between">
        <span className="muted small">{q.items.length} waiting</span>
        <Button size="sm" icon="refresh" onClick={q.reload}>
          Refresh
        </Button>
      </div>
      <ProblemView error={q.error} />
      {q.loading && q.items.length === 0 ? (
        <Loading />
      ) : q.items.length === 0 && !q.error ? (
        <Empty icon="checkCircle" title="All caught up">
          No listings are waiting for review.
        </Empty>
      ) : (
        <div className="list-cards">
          {q.items.map((i) => (
            <Link key={i.listingId} to={`/listings/${i.listingId}`} className="card row-card">
              <div className="thumb">{i.thumbnailUrl ? <img src={i.thumbnailUrl} alt="" /> : <div className="noimg"><Icon name="image" /></div>}</div>
              <div className="body">
                <span className="title">{i.title}</span>
                <span className="small muted">{i.address ?? '–'}</span>
                <span className="small">
                  {formatMoney(i.pricePerHour, i.currency)}/h · {i.photoCount ?? '?'} photos · {i.availabilityRuleCount ?? '?'} opening-hour rules
                  {i.lastChangedAt ? ` · submitted ${relativeTime(i.lastChangedAt, Date.now())}` : ''}
                </span>
                <span className="row small">
                  <Avatar name={i.host?.displayName} url={i.host?.photoUrl} size={22} /> {i.host?.displayName ?? 'Host'}
                  {i.host && <RatingInline avg={i.host.ratingAvg} count={i.host.ratingCount} />}
                  {i.partial && <span className="warn-text">some details unavailable</span>}
                </span>
              </div>
              <span className="btn btn-sm btn-primary">Review</span>
            </Link>
          ))}
        </div>
      )}
      <LoadMore cursor={q.next} onMore={q.more} busy={q.loading} />
    </AdminShell>
  );
}

export function AdminBookingPage() {
  const [sp, setSp] = useSearchParams();
  const id = sp.get('id') ?? '';
  const [idIn, setIdIn] = useState(id);
  const c = useLoad(() => (id ? admin.bookingCase(id) : Promise.resolve(null)), [id]);
  const d = c.data;
  return (
    <AdminShell title="Bookings & refunds" sub="Look up a booking to see its payment, the people involved, and issue a goodwill refund.">
      <form
        className="row nowrap"
        onSubmit={(e) => {
          e.preventDefault();
          setSp({ id: idIn.trim() });
        }}
      >
        <input value={idIn} onChange={(e) => setIdIn(e.target.value)} placeholder="Booking ID" aria-label="Booking ID" required pattern="[0-9a-fA-F-]{36}" />
        <Button type="submit" variant="primary" icon="search">
          Look up
        </Button>
      </form>
      <ProblemView error={c.error} />
      {c.loading && id && !d && <Loading />}
      {d && <CaseView d={d} onChange={c.reload} />}
    </AdminShell>
  );
}

function CaseView({ d, onChange }: { d: BookingCase; onChange: () => void }) {
  const b = d.booking;
  return (
    <>
      {d.partial && <Alert kind="warn">Some details couldn't be loaded (a service didn't answer); what's shown is accurate.</Alert>}
      <div className="grid-2">
        <Card title="Booking">
          <dl className="kv" style={{ gridTemplateColumns: '100px 1fr' }}>
            <dt>Status</dt>
            <dd>
              <StatusBadge kind="booking" value={b.status} />
            </dd>
            <dt>When</dt>
            <dd>
              <DateRange start={b.startTime} end={b.endTime} />
            </dd>
            <dt>Total</dt>
            <dd>{formatMoney(b.totalAmount, b.currency)}</dd>
            <dt>Policy</dt>
            <dd>{b.cancellationPolicy.toLowerCase()}</dd>
            {b.refundAmount && (
              <>
                <dt>Refunded</dt>
                <dd>{formatMoney(b.refundAmount, b.currency)}</dd>
              </>
            )}
          </dl>
        </Card>
        <Card title="Payment">
          {d.payment ? (
            <dl className="kv" style={{ gridTemplateColumns: '100px 1fr' }}>
              <dt>Status</dt>
              <dd>
                <StatusBadge kind="payment" value={d.payment.status} />
              </dd>
              <dt>Amount</dt>
              <dd>{formatMoney(d.payment.amount, d.payment.currency)}</dd>
              <dt>Refunded</dt>
              <dd>{formatMoney(d.payment.refundedAmount, d.payment.currency)}</dd>
              {d.payment.failureCode && (
                <>
                  <dt>Failure</dt>
                  <dd>{d.payment.failureCode}</dd>
                </>
              )}
            </dl>
          ) : (
            <p className="muted">No payment yet.</p>
          )}
        </Card>
        <Card title="Listing">
          {d.listing ? (
            <div className="stack-xs">
              <Link to={`/listings/${d.listing.listingId}`} className="strong">
                {d.listing.title}
              </Link>
              <span className="small muted">{d.listing.address}</span>
              <StatusBadge kind="listing" value={d.listing.status} />
            </div>
          ) : (
            <p className="muted">–</p>
          )}
        </Card>
        <Card title="People">
          <div className="stack">
            {[
              { role: 'Driver', p: d.driver },
              { role: 'Host', p: d.host },
            ].map(({ role, p }) => (
              <div key={role} className="row">
                <Avatar name={p?.displayName} url={p?.photoUrl} size={34} />
                <span>
                  <span className="small muted">{role}</span>
                  <br />
                  <span className="strong">{p?.displayName ?? 'No name'}</span> <RatingInline avg={p?.ratingAvg} count={p?.ratingCount ?? 0} />
                </span>
              </div>
            ))}
          </div>
        </Card>
      </div>
      <RefundCard d={d} onChange={onChange} />
    </>
  );
}

function RefundCard({ d, onChange }: { d: BookingCase; onChange: () => void }) {
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const act = useAction();
  const lastKey = useRef<{ sig: string; key: string } | null>(null);
  const p = d.payment;
  const refundable = p && ['CAPTURED', 'PARTIALLY_REFUNDED'].includes(p.status);
  const remaining = p ? (Number(p.amount) - Number(p.refundedAmount)).toFixed(2) : '0';
  return (
    <Card title="Goodwill refund">
      {!refundable ? (
        <p className="muted">Refunds are possible once a payment has been captured. Current payment status: {p?.status.toLowerCase() ?? 'none'}.</p>
      ) : (
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!confirm(`Refund ${formatMoney(amount)} to the driver?`)) return;
            const sig = `${d.booking.bookingId}|${amount}|${reason}`;
            // The same refund retried (double click, timeout) replays instead of refunding twice.
            if (!lastKey.current || lastKey.current.sig !== sig) lastKey.current = { sig, key: newIdempotencyKey() };
            const r = await act.run(() => admin.refund(d.booking.bookingId, Number(amount).toFixed(2), reason.trim(), lastKey.current!.key));
            if (r) {
              toast(`Refunded. Total refunded: ${formatMoney(r.data.refundedAmount)}`);
              setAmount('');
              setReason('');
              lastKey.current = null;
              onChange();
            }
          }}
        >
          <div className="grid-2">
            <Field label="Amount" id="refund-amount" hint={`Up to ${formatMoney(remaining)} remaining.`}>
              <div className="input-group">
                <span className="addon">₹</span>
                <input id="refund-amount" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))} required inputMode="decimal" />
              </div>
            </Field>
            <Field label="Reason (shared with the driver)">
              <input value={reason} onChange={(e) => setReason(e.target.value)} required minLength={3} maxLength={255} />
            </Field>
          </div>
          <ProblemView error={act.error} compact />
          <div className="form-actions">
            <Button type="submit" variant="danger" className="solid" busy={act.busy} disabled={!amount || Number(amount) <= 0 || Number(amount) > Number(remaining)}>
              Issue refund
            </Button>
          </div>
        </form>
      )}
    </Card>
  );
}

export function AdminReviewsPage() {
  const [id, setId] = useState('');
  const [reason, setReason] = useState('');
  const act = useAction();
  return (
    <AdminShell title="Ratings & reviews" sub="Hide reviews that break the guidelines. You can also hide a review straight from a listing page.">
      <Card title="Hide a review by ID">
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const r = await act.run(() => admin.hideReview(id.trim(), reason.trim()));
            if (r) {
              toast('Review hidden; the rating was recalculated.');
              setId('');
              setReason('');
            }
          }}
        >
          <Field label="Review ID">
            <input value={id} onChange={(e) => setId(e.target.value)} required pattern="[0-9a-fA-F-]{36}" />
          </Field>
          <Field label="Reason (kept in the audit log)">
            <input value={reason} onChange={(e) => setReason(e.target.value)} required minLength={3} maxLength={255} />
          </Field>
          <ProblemView error={act.error} compact />
          <div className="form-actions">
            <Button type="submit" variant="danger" className="solid" busy={act.busy}>
              Hide review
            </Button>
          </div>
        </form>
      </Card>
    </AdminShell>
  );
}
