import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { bookings, payments, reviews } from '../api/endpoints';
import { newIdempotencyKey } from '../api/client';
import type { Booking, BookingStatus } from '../api/types';
import { usePaged } from '../components/paged';
import { Badge, Card, Copy, Field, LoadMore, Loading, Money, Notice, ProblemView, Time, useAction, useLoad, usePolling } from '../components/ui';
import { countdown, refundTier } from '../lib';
import { useAuth } from '../state/auth';

const STATUSES: BookingStatus[] = [
  'PENDING_PAYMENT',
  'AWAITING_HOST_APPROVAL',
  'CONFIRMED',
  'CHECKED_IN',
  'COMPLETED',
  'CANCELLED_BY_DRIVER',
  'CANCELLED_BY_HOST',
  'DECLINED_BY_HOST',
  'NO_SHOW',
  'EXPIRED',
  'PAYMENT_FAILED',
];
const ACTIVE: BookingStatus[] = ['PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL', 'CONFIRMED', 'CHECKED_IN'];

export function useNow(ms = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

function BookingList({ role }: { role: 'driver' | 'host' }) {
  const [when, setWhen] = useState<'all' | 'upcoming' | 'past'>('all');
  const [status, setStatus] = useState('');
  const p = usePaged((c) => bookings.list(role, when, status || undefined, c), [role, when, status]);
  const act = useAction();
  const [reason, setReason] = useState('Not available');
  // Status polling: refresh the list while anything is in flight.
  usePolling(p.reload, 5000, p.items.some((b) => ['PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL'].includes(b.status)));

  return (
    <div className="stack">
      <div className="row">
        <h2>{role === 'driver' ? 'My bookings' : 'Booking requests and bookings on my listings'}</h2>
        <div className="grow" />
        <select value={when} onChange={(e) => setWhen(e.target.value as typeof when)} aria-label="When">
          <option value="all">all</option>
          <option value="upcoming">upcoming</option>
          <option value="past">past</option>
        </select>
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
          <option value="">any status</option>
          {STATUSES.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
        <button onClick={p.reload}>Refresh</button>
      </div>
      {role === 'host' && (
        <Notice>
          Request-to-book listings land in AWAITING_HOST_APPROVAL after the driver pays. Approve or decline before the deadline (min of 24h and the start). Decline reason:{' '}
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} />
        </Notice>
      )}
      <ProblemView error={p.error} />
      <ProblemView error={act.error} />
      {p.loading && p.items.length === 0 && <Loading />}
      {!p.loading && p.items.length === 0 && !p.error && <p className="muted">Nothing here.</p>}
      <table className="table">
        <thead>
          <tr>
            <th>When</th>
            <th>Status</th>
            <th>Total</th>
            <th>Mode</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {p.items.map((b) => (
            <tr key={b.id}>
              <td>
                <Time value={b.startTime} /> to <Time value={b.endTime} />
                <div className="muted">
                  <code>{b.id.slice(0, 8)}</code> listing <Link to={`/listings/${b.listingId}`}>{b.listingId.slice(0, 8)}</Link>
                </div>
              </td>
              <td>
                <Badge value={b.status} />
                {b.hostDecisionDueBy && (
                  <div className="muted">
                    decide by <Time value={b.hostDecisionDueBy} />
                  </div>
                )}
              </td>
              <td>
                <Money value={b.totalAmount} currency={b.currency} />
              </td>
              <td>{b.instantBook ? 'instant' : 'request'}</td>
              <td className="actions">
                <Link to={`/bookings/${b.id}`}>Open</Link>
                {role === 'host' && b.status === 'AWAITING_HOST_APPROVAL' && (
                  <>
                    <button
                      disabled={act.busy}
                      onClick={async () => {
                        await act.run(() => bookings.approve(b.id));
                        p.reload();
                      }}
                    >
                      Approve
                    </button>
                    <button
                      disabled={act.busy}
                      onClick={async () => {
                        await act.run(() => bookings.decline(b.id, reason || 'Declined'));
                        p.reload();
                      }}
                    >
                      Decline
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
    </div>
  );
}
export const MyBookingsPage = () => <BookingList role="driver" />;
export const HostBookingsPage = () => <BookingList role="host" />;

// ------------------------------------------------------------------ earnings
export function EarningsPage() {
  const e = useLoad(() => payments.earnings());
  const p = usePaged((c) => payments.payouts(c));
  return (
    <div className="stack">
      <h2>Earnings and payouts</h2>
      <Notice>
        Earnings come from the Payment ledger once bookings are CAPTURED (host gets 85%, platform keeps a 15% commission). Payouts are created by a weekly
        job, so locally the list is usually empty. Payout holds and admin payout views are not implemented.
      </Notice>
      <ProblemView error={e.error} />
      {e.data && (
        <div className="stats">
          <div>
            <span>Total earned</span>
            <strong>
              <Money value={e.data.totalEarned} currency={e.data.currency} />
            </strong>
          </div>
          <div>
            <span>Paid out</span>
            <strong>
              <Money value={e.data.totalPaidOut} currency={e.data.currency} />
            </strong>
          </div>
          <div>
            <span>Pending payout</span>
            <strong>
              <Money value={e.data.pendingPayout} currency={e.data.currency} />
            </strong>
          </div>
        </div>
      )}
      <Card title="Payouts (newest first)">
        <ProblemView error={p.error} />
        {p.items.length === 0 && !p.loading && <p className="muted">No payouts yet.</p>}
        <table className="table">
          <tbody>
            {p.items.map((x) => (
              <tr key={x.id}>
                <td>
                  {x.periodStart} to {x.periodEnd}
                </td>
                <td>
                  <Money value={x.amount} currency={x.currency} />
                </td>
                <td>
                  <Badge value={x.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
      </Card>
    </div>
  );
}

// ------------------------------------------------------------------ booking detail
export function BookingDetailPage() {
  const { id = '' } = useParams();
  const a = useAuth();
  const now = useNow();
  const b = useLoad(() => bookings.get(id), [id]);
  const bk = b.data;
  const isDriver = !!bk && bk.driverAccountId === a.accountId;
  const isHost = !!bk && bk.hostAccountId === a.accountId;
  // Status polling: the saga and the timers move bookings in the background.
  usePolling(b.reload, 4000, !!bk && ACTIVE.includes(bk.status));

  if (b.loading && !bk) return <Loading what="booking" />;
  if (!bk) return <ProblemView error={b.error} />;

  return (
    <div className="stack">
      <div className="row">
        <h2>Booking</h2> <Badge value={bk.status} />
        <span className="muted">
          polling every 4 s while active <Copy text={bk.id} label="copy id" />
        </span>
        <div className="grow" />
        <Link to={isHost && !isDriver ? '/host/bookings' : '/bookings'}>Back</Link>
      </div>
      <ProblemView error={b.error} />
      <Card title="Details">
        <dl className="kv">
          <dt>Id</dt>
          <dd>
            <code>{bk.id}</code>
          </dd>
          <dt>Listing</dt>
          <dd>
            <Link to={`/listings/${bk.listingId}`}>{bk.listingId}</Link>
          </dd>
          <dt>Window</dt>
          <dd>
            <Time value={bk.startTime} /> to <Time value={bk.endTime} /> <span className="muted">({bk.startTime} to {bk.endTime})</span>
          </dd>
          <dt>Price</dt>
          <dd>
            <Money value={bk.pricePerHour} currency={bk.currency} />/h, total <Money value={bk.totalAmount} currency={bk.currency} />, buffer {bk.bufferMinutes} min
          </dd>
          <dt>Policy / mode</dt>
          <dd>
            {bk.cancellationPolicy}, {bk.instantBook ? 'instant book' : 'request to book'}
          </dd>
          {bk.paymentDueBy && (
            <>
              <dt>Pay by</dt>
              <dd>
                <Time value={bk.paymentDueBy} /> ({countdown(new Date(bk.paymentDueBy).getTime(), now)} left)
              </dd>
            </>
          )}
          {bk.hostDecisionDueBy && (
            <>
              <dt>Host decides by</dt>
              <dd>
                <Time value={bk.hostDecisionDueBy} />
              </dd>
            </>
          )}
          {bk.refundAmount && (
            <>
              <dt>Refund</dt>
              <dd>
                <Money value={bk.refundAmount} currency={bk.currency} />
              </dd>
            </>
          )}
          <dt>You are</dt>
          <dd>{isDriver ? 'the driver' : isHost ? 'the host' : a.hasRole('ADMIN') ? 'an admin' : 'someone else'}</dd>
        </dl>
      </Card>

      {isDriver && bk.status === 'PENDING_PAYMENT' && <PayCard booking={bk} onDone={b.reload} />}
      {isDriver && bk.status === 'AWAITING_HOST_APPROVAL' && (
        <Notice kind="warn">Paid (authorized). Waiting for the host to approve or decline; the status refreshes by itself. Cancelling now refunds 100%.</Notice>
      )}
      {isDriver && <DriverActions booking={bk} now={now} onDone={b.reload} />}
      {isHost && !isDriver && <HostActions booking={bk} onDone={b.reload} />}
      {bk.status === 'COMPLETED' && (isDriver || isHost) && <ReviewCard booking={bk} isDriver={isDriver} />}
    </div>
  );
}

const FAKE_TOKENS = [
  { token: 'fake_ok', text: 'authorizes (booking becomes CONFIRMED or AWAITING_HOST_APPROVAL)' },
  { token: 'fake_decline', text: 'card_declined: intent FAILED, booking EXPIRED (final; book again)' },
  { token: 'fake_capture_fail', text: 'authorizes, but capture is rejected on confirmation: booking becomes PAYMENT_FAILED' },
];

function PayCard({ booking, onDone }: { booking: Booking; onDone: () => void }) {
  const [token, setToken] = useState('fake_ok');
  // One key per payment attempt: a double-click or retry replays instead of charging twice.
  const [key, setKey] = useState(newIdempotencyKey);
  const intent = useLoad(() => payments.intentFor(booking.id), [booking.id]);
  usePolling(intent.reload, 2000, !intent.data && !!intent.error);
  const act = useAction();
  const [last, setLast] = useState<string | null>(null);
  const i = intent.data;
  return (
    <Card title="Pay (FakeProcessor)">
      <p className="muted">
        The payment intent is created asynchronously from BookingRequested: a 404 for a second or two is normal; this retries. No real card data is involved.
      </p>
      {!i && <Loading what="payment intent" />}
      {!i && intent.error ? (
        (intent.error as { status?: number }).status === 404 ? <p className="muted">Intent not there yet, retrying...</p> : <ProblemView error={intent.error} />
      ) : null}
      {i && (
        <>
          <p>
            Intent <code>{i.id}</code> <Badge value={i.status} /> amount <Money value={i.amount} currency={i.currency} />
            {i.failureCode && <span className="bad-text"> failure: {i.failureCode}</span>}
          </p>
          <ul className="tokens">
            {FAKE_TOKENS.map((t) => (
              <li key={t.token}>
                <label>
                  <input type="radio" name="tok" checked={token === t.token} onChange={() => setToken(t.token)} /> <code>{t.token}</code> {t.text}
                </label>
              </li>
            ))}
          </ul>
          <Field label="Or any other token (3-255 chars)">
            <input value={token} onChange={(e) => setToken(e.target.value)} minLength={3} maxLength={255} />
          </Field>
          <p className="muted">
            Idempotency-Key for this attempt: <code>{key}</code> (re-click to replay the stored result;{' '}
            <button className="link" onClick={() => setKey(newIdempotencyKey())}>
              new key
            </button>
            )
          </p>
          <button
            disabled={act.busy || i.status !== 'CREATED'}
            onClick={async () => {
              const r = await act.run(() => payments.authorize(i.id, token, key));
              if (r) {
                setLast(`authorize -> HTTP ${r.status}, intent ${r.data.status}${r.data.failureCode ? ' (' + r.data.failureCode + ')' : ''}`);
                intent.reload();
                onDone();
              }
            }}
          >
            Authorize payment
          </button>
          {last && <Notice kind="info">{last}. The booking updates through events; watch the status above.</Notice>}
          <ProblemView error={act.error} />
        </>
      )}
    </Card>
  );
}

function DriverActions({ booking, now, onDone }: { booking: Booking; now: number; onDone: () => void }) {
  const act = useAction();
  const [reason, setReason] = useState('Plans changed');
  const startMs = new Date(booking.startTime).getTime();
  const opens = startMs - 15 * 60_000;
  const closes = startMs + 30 * 60_000;
  const inWindow = now >= opens && now < closes;
  const tier = refundTier(booking.cancellationPolicy, booking.startTime, now, booking.totalAmount);
  const canCancel = ['CONFIRMED', 'AWAITING_HOST_APPROVAL'].includes(booking.status);
  return (
    <Card title="Driver actions">
      {booking.status === 'CONFIRMED' && (
        <>
          <p>
            Check-in window: <Time value={new Date(opens).toISOString()} /> to <Time value={new Date(closes).toISOString()} />.{' '}
            {now < opens ? `Opens in ${countdown(opens, now)}.` : inWindow ? 'Open now.' : 'Closed (the no-show timer will mark it NO_SHOW).'}
          </p>
          <button
            disabled={act.busy || !inWindow}
            onClick={async () => {
              await act.run(() => bookings.checkIn(booking.id));
              onDone();
            }}
          >
            Check in
          </button>
        </>
      )}
      {booking.status === 'CHECKED_IN' && (
        <>
          <p>Check-out completes the booking immediately (no refund for unused time). Otherwise the completion timer does it at the end time.</p>
          <button
            disabled={act.busy}
            onClick={async () => {
              await act.run(() => bookings.checkOut(booking.id));
              onDone();
            }}
          >
            Check out (complete)
          </button>
        </>
      )}
      {canCancel && (
        <div className="stack-sm">
          <p>
            {booking.status === 'AWAITING_HOST_APPROVAL' ? (
              <>If you cancel now the refund is <strong>100%</strong> (awaiting host approval).</>
            ) : (
              <>
                If you cancel now: <strong>{tier.percent}%</strong> refund = <Money value={tier.amount} currency={booking.currency} /> ({tier.explanation}). The server
                computes the real amount from the snapshotted policy.
              </>
            )}
          </p>
          <Field label="Reason">
            <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} required />
          </Field>
          <button
            disabled={act.busy || !reason}
            onClick={async () => {
              await act.run(() => bookings.cancel(booking.id, reason));
              onDone();
            }}
          >
            Cancel booking
          </button>
        </div>
      )}
      {!canCancel && !['CONFIRMED', 'CHECKED_IN'].includes(booking.status) && <p className="muted">No driver actions for {booking.status}.</p>}
      <ProblemView error={act.error} />
    </Card>
  );
}

function HostActions({ booking, onDone }: { booking: Booking; onDone: () => void }) {
  const act = useAction();
  const [reason, setReason] = useState('Not available');
  return (
    <Card title="Host actions">
      <Field label="Reason (decline / cancel)">
        <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} />
      </Field>
      <div className="row">
        <button
          disabled={act.busy || booking.status !== 'AWAITING_HOST_APPROVAL'}
          onClick={async () => {
            await act.run(() => bookings.approve(booking.id));
            onDone();
          }}
        >
          Approve
        </button>
        <button
          disabled={act.busy || booking.status !== 'AWAITING_HOST_APPROVAL'}
          onClick={async () => {
            await act.run(() => bookings.decline(booking.id, reason));
            onDone();
          }}
        >
          Decline (100% refund)
        </button>
        <button
          disabled={act.busy || booking.status !== 'CONFIRMED'}
          onClick={async () => {
            await act.run(() => bookings.cancel(booking.id, reason));
            onDone();
          }}
        >
          Cancel as host (100% refund)
        </button>
      </div>
      <ProblemView error={act.error} />
    </Card>
  );
}

function ReviewCard({ booking, isDriver }: { booking: Booking; isDriver: boolean }) {
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState('');
  const act = useAction();
  const [done, setDone] = useState<string | null>(null);
  return (
    <Card title={isDriver ? 'Review the listing (driver to listing)' : 'Review the driver (host to driver)'}>
      <p className="muted">
        Review learns about completion from the BookingCompleted event, so a 404 right after completing means "not seen yet": retry in a few seconds. One review per
        booking and direction (409 on a duplicate); window closes 14 days after completion.
      </p>
      <form
        className="form"
        onSubmit={async (e) => {
          e.preventDefault();
          const r = await act.run(() => reviews.submit(booking.id, rating, comment));
          if (r) setDone(`Published: ${r.direction}, ${r.rating} stars.`);
        }}
      >
        <Field label="Rating">
          <select value={rating} onChange={(e) => setRating(Number(e.target.value))}>
            {[5, 4, 3, 2, 1].map((n) => (
              <option key={n}>{n}</option>
            ))}
          </select>
        </Field>
        <Field label="Comment (optional, max 1000)">
          <textarea value={comment} onChange={(e) => setComment(e.target.value)} maxLength={1000} rows={3} />
        </Field>
        <button type="submit" disabled={act.busy}>
          Submit review
        </button>
      </form>
      {done && <Notice kind="ok">{done}</Notice>}
      <ProblemView error={act.error} />
      <p>
        <Link to={`/reviews?targetType=${isDriver ? 'LISTING' : 'DRIVER'}&targetId=${isDriver ? booking.listingId : booking.driverAccountId}`}>
          View reviews for the {isDriver ? 'listing' : 'driver'}
        </Link>
      </p>
    </Card>
  );
}
