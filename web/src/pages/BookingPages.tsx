import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { bookings, payments, profile, reviews } from '../api/endpoints';
import { newIdempotencyKey } from '../api/client';
import type { Booking, BookingStatus, PaymentIntent } from '../api/types';
import { POLICY_META, VEHICLE_LABEL } from '../labels';
import {
  countdown,
  directionsUrl,
  durationLabel,
  expiryValid,
  formatCardNumber,
  formatMoney,
  luhnValid,
  refundTier,
  TEST_CARDS,
  testCardToken,
} from '../lib';
import { Icon, type IconName } from '../components/Icon';
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
  Modal,
  ProblemView,
  RatingInline,
  StarInput,
  Stars,
  StatusBadge,
  Tabs,
  fmtDate,
  fmtDateTime,
  fmtTime,
  isStatus,
  toast,
  useAction,
  useDocumentTitle,
  useLoad,
  useNow,
  usePolling,
} from '../components/ui';
import { TEST_MODE } from '../components/Layout';
import { coverPhoto, useListingBrief, usePublicProfile } from '../state/cache';
import { useAuth } from '../state/auth';

const ACTIVE: BookingStatus[] = ['PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL', 'CONFIRMED', 'CHECKED_IN'];
const MIN = 60_000;

// ------------------------------------------------------------------ list rows
export function BookingRow({ b, perspective }: { b: Booking; perspective: 'driver' | 'host' }) {
  const l = useListingBrief(b.listingId);
  const driver = usePublicProfile(perspective === 'host' ? b.driverAccountId : null);
  const img = coverPhoto(l);
  return (
    <Link to={`/bookings/${b.id}`} className="card row-card">
      <div className="thumb">{img ? <img src={img} alt="" /> : <div className="noimg"><Icon name="car" /></div>}</div>
      <div className="body">
        <div className="row between nowrap">
          <span className="title">{l ? l.title : l === null ? 'Parking space' : '…'}</span>
          <StatusBadge kind="booking" value={b.status} />
        </div>
        <span className="small">
          <DateRange start={b.startTime} end={b.endTime} />
        </span>
        <span className="small muted">
          {perspective === 'host' ? (
            <>Driver: {driver?.displayName ?? 'ParkSmart driver'} · </>
          ) : l ? (
            <>{l.address} · </>
          ) : null}
          {formatMoney(b.totalAmount, b.currency)}
        </span>
        {perspective === 'driver' && b.status === 'PENDING_PAYMENT' && <span className="small strong warn-text">Payment needed to hold this space</span>}
        {perspective === 'host' && b.status === 'AWAITING_HOST_APPROVAL' && b.hostDecisionDueBy && (
          <span className="small strong warn-text">Respond by {fmtDateTime(b.hostDecisionDueBy)}</span>
        )}
      </div>
      <Icon name="chevronRight" className="muted" />
    </Link>
  );
}

// ------------------------------------------------------------------ driver: my bookings
const ENDED_EARLY: BookingStatus[] = ['CANCELLED_BY_DRIVER', 'CANCELLED_BY_HOST', 'DECLINED_BY_HOST', 'EXPIRED', 'PAYMENT_FAILED'];
type TripTab = 'upcoming' | 'past' | 'cancelled';
/** The API splits by time; cancelled and expired bookings get their own tab so "Upcoming" only shows live ones. */
const TRIP_TABS: Record<TripTab, { when: 'upcoming' | 'past' | 'all'; keep: (b: Booking) => boolean; empty: string; hint: string }> = {
  upcoming: { when: 'upcoming', keep: (b) => !ENDED_EARLY.includes(b.status), empty: 'No upcoming bookings', hint: 'When you book a space it shows up here.' },
  past: { when: 'past', keep: (b) => !ENDED_EARLY.includes(b.status), empty: 'No past bookings yet', hint: 'Completed bookings show up here, ready for a review.' },
  cancelled: { when: 'all', keep: (b) => ENDED_EARLY.includes(b.status), empty: 'Nothing cancelled', hint: 'Cancelled, declined and expired bookings show up here.' },
};

export function MyBookingsPage() {
  useDocumentTitle('My bookings');
  const [tab, setTab] = useState<TripTab>('upcoming');
  const cfg = TRIP_TABS[tab];
  const p = usePaged((c) => bookings.list('driver', cfg.when, undefined, c), [tab]);
  const items = p.items.filter(cfg.keep);
  usePolling(p.reload, 8000, p.items.some((b) => ['PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL'].includes(b.status)));
  return (
    <div className="container narrow page">
      <div className="page-head">
        <div>
          <h1>My bookings</h1>
          <p className="sub">Your parking, past and upcoming.</p>
        </div>
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'upcoming', label: 'Upcoming' },
          { value: 'past', label: 'Past' },
          { value: 'cancelled', label: 'Cancelled' },
        ]}
      />
      <ProblemView error={p.error} />
      {p.loading && items.length === 0 ? (
        <Loading />
      ) : items.length === 0 && !p.error ? (
        <Empty icon="calendar" title={cfg.empty} action={tab === 'upcoming' ? <Link className="btn btn-primary" to="/search">Find parking</Link> : undefined}>
          {cfg.hint}
        </Empty>
      ) : (
        <div className="list-cards">
          {items.map((b) => (
            <BookingRow key={b.id} b={b} perspective="driver" />
          ))}
        </div>
      )}
      <div style={{ marginTop: 16 }}>
        <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ booking detail
type Step = { label: string; state: 'done' | 'on' | 'todo' | 'bad' };
function stepsFor(b: Booking): Step[] {
  const order: BookingStatus[] = ['PENDING_PAYMENT', ...(b.instantBook ? [] : (['AWAITING_HOST_APPROVAL'] as BookingStatus[])), 'CONFIRMED', 'CHECKED_IN', 'COMPLETED'];
  const labels: Record<string, string> = { PENDING_PAYMENT: 'Payment', AWAITING_HOST_APPROVAL: 'Host accepts', CONFIRMED: 'Confirmed', CHECKED_IN: 'Parked', COMPLETED: 'Done' };
  const idx = order.indexOf(b.status);
  if (idx >= 0) return order.map((s, i) => ({ label: labels[s], state: i < idx || b.status === 'COMPLETED' ? 'done' : i === idx ? 'on' : 'todo' }));
  // Terminal, unhappy states: mark where it stopped.
  const stoppedAt: Record<string, BookingStatus> = {
    EXPIRED: 'PENDING_PAYMENT',
    PAYMENT_FAILED: 'CONFIRMED',
    DECLINED_BY_HOST: 'AWAITING_HOST_APPROVAL',
    CANCELLED_BY_DRIVER: 'CONFIRMED',
    CANCELLED_BY_HOST: 'CONFIRMED',
    NO_SHOW: 'CHECKED_IN',
  };
  const stop = order.indexOf(stoppedAt[b.status] ?? 'CONFIRMED');
  return order.map((s, i) => ({ label: i === stop ? (b.status === 'NO_SHOW' ? 'No-show' : b.status === 'EXPIRED' ? 'Not paid' : 'Ended') : labels[s], state: i < stop ? 'done' : i === stop ? 'bad' : 'todo' }));
}

function Timeline({ b }: { b: Booking }) {
  return (
    <ol className="timeline" aria-label="Booking progress">
      {stepsFor(b).map((s) => (
        <li key={s.label} className={s.state}>
          <span className="dot">{s.state === 'done' ? <Icon name="check" size={13} /> : s.state === 'bad' ? <Icon name="x" size={13} /> : null}</span>
          {s.label}
        </li>
      ))}
    </ol>
  );
}

export function BookingPage() {
  const { id = '' } = useParams();
  const a = useAuth();
  const now = useNow();
  const b = useLoad(() => bookings.get(id), [id]);
  const bk = b.data;
  const listing = useListingBrief(bk?.listingId);
  useDocumentTitle(bk ? 'Booking' : null);
  // The saga and the timers move bookings in the background: poll while anything can still change.
  usePolling(b.reload, 4000, !!bk && ACTIVE.includes(bk.status));

  if (b.loading && !bk) return <Loading what="booking" />;
  if (!bk) {
    return (
      <div className="container narrow page">
        <ProblemView error={b.error} />
      </div>
    );
  }
  const isDriver = bk.driverAccountId === a.accountId;
  const isHost = bk.hostAccountId === a.accountId;
  const img = coverPhoto(listing);
  const back = isDriver ? '/bookings' : isHost ? '/host/bookings' : '/admin/bookings';

  return (
    <div className="container page">
      <Link to={back} className="small row" style={{ gap: 4, marginBottom: 16 }}>
        <Icon name="chevronLeft" size={15} /> {isDriver ? 'My bookings' : isHost ? 'Bookings' : 'Back'}
      </Link>
      <div className="with-aside">
        <div className="stack-lg">
          <div className="stack">
            <div className="row between">
              <h1 style={{ fontSize: '1.7rem' }}>{headline(bk, isDriver)}</h1>
              <StatusBadge kind="booking" value={bk.status} />
            </div>
            <Timeline b={bk} />
          </div>
          {isDriver && <DriverPanel booking={bk} now={now} onChange={b.reload} listingLoc={listing?.location} />}
          {isHost && !isDriver && <HostPanel booking={bk} onChange={b.reload} />}
          {!isDriver && !isHost && a.hasRole('ADMIN') && (
            <Alert kind="info">
              You are viewing this booking as an admin. <Link to={`/admin/bookings?id=${bk.id}`}>Open the support case</Link>.
            </Alert>
          )}
          {bk.status === 'COMPLETED' && (isDriver || isHost) && <ReviewPanel booking={bk} isDriver={isDriver} />}
        </div>

        <aside className="sticky stack">
          <div className="card">
            <div className="card-body stack">
              <div className="trip-hero" style={{ gridTemplateColumns: '88px 1fr' }}>
                <div className="thumb" style={{ width: 88, height: 70 }}>
                  {img ? <img src={img} alt="" /> : <div className="noimg"><Icon name="car" /></div>}
                </div>
                <div className="stack-xs">
                  <Link to={`/listings/${bk.listingId}`} className="strong" style={{ color: 'var(--ink)' }}>
                    {listing?.title ?? 'Parking space'}
                  </Link>
                  <span className="small muted">{listing?.address}</span>
                </div>
              </div>
              <hr className="divider" style={{ margin: 0 }} />
              <dl className="kv" style={{ gridTemplateColumns: '90px 1fr' }}>
                <dt>Arrive</dt>
                <dd>{fmtDateTime(bk.startTime)}</dd>
                <dt>Leave</dt>
                <dd>{fmtDateTime(bk.endTime)}</dd>
                <dt>Duration</dt>
                <dd>{durationLabel(bk.startTime, bk.endTime)}</dd>
                {isDriver && bk.vehicleId && <VehicleLine id={bk.vehicleId} />}
              </dl>
              <hr className="divider" style={{ margin: 0 }} />
              <div className="breakdown">
                <div className="line">
                  <span className="muted">{formatMoney(bk.pricePerHour, bk.currency)} / hour</span>
                  <span>{formatMoney(bk.totalAmount, bk.currency)}</span>
                </div>
                {bk.refundAmount && Number(bk.refundAmount) > 0 && (
                  <div className="line ok-text">
                    <span>Refunded</span>
                    <span>−{formatMoney(bk.refundAmount, bk.currency)}</span>
                  </div>
                )}
                <div className="line total">
                  <span>Total</span>
                  <span>{formatMoney(bk.totalAmount, bk.currency)}</span>
                </div>
              </div>
              <p className="tiny muted">
                {POLICY_META[bk.cancellationPolicy].label} cancellation: {POLICY_META[bk.cancellationPolicy].short.toLowerCase()}. Booking ref {bk.id.slice(0, 8).toUpperCase()}
              </p>
            </div>
          </div>
        </aside>
      </div>
    </div>
  );
}

function headline(b: Booking, isDriver: boolean): string {
  switch (b.status) {
    case 'PENDING_PAYMENT':
      return isDriver ? 'Confirm and pay' : 'Waiting for the driver to pay';
    case 'AWAITING_HOST_APPROVAL':
      return isDriver ? 'Request sent' : 'New booking request';
    case 'CONFIRMED':
      return isDriver ? "You're booked" : 'Upcoming booking';
    case 'CHECKED_IN':
      return isDriver ? "You're parked" : 'Driver is parked';
    case 'COMPLETED':
      return 'Booking complete';
    default:
      return 'Booking ' + (b.status === 'EXPIRED' ? 'expired' : 'ended');
  }
}

function VehicleLine({ id }: { id: string }) {
  const v = useLoad(() => profile.vehicles().then((r) => r.items.find((x) => x.id === id) ?? null), [id]);
  if (!v.data) return null;
  return (
    <>
      <dt>Vehicle</dt>
      <dd>
        {v.data.licensePlate} · {VEHICLE_LABEL[v.data.vehicleType]}
      </dd>
    </>
  );
}

// ------------------------------------------------------------------ driver side
function DriverPanel({ booking: b, now, onChange, listingLoc }: { booking: Booking; now: number; onChange: () => void; listingLoc?: { lat: number; lng: number } }) {
  const [cancelling, setCancelling] = useState(false);
  const act = useAction();
  const startMs = new Date(b.startTime).getTime();
  const opens = startMs - 15 * MIN;
  const closes = startMs + 30 * MIN;
  const canCancel = ['CONFIRMED', 'AWAITING_HOST_APPROVAL'].includes(b.status);

  const directions = listingLoc && ['CONFIRMED', 'CHECKED_IN'].includes(b.status) && (
    <a className="btn" href={directionsUrl(listingLoc.lat, listingLoc.lng)} target="_blank" rel="noreferrer">
      <Icon name="map" size={17} /> Directions
    </a>
  );

  return (
    <div className="stack">
      {b.status === 'PENDING_PAYMENT' && <Checkout booking={b} now={now} onChange={onChange} />}

      {b.status === 'AWAITING_HOST_APPROVAL' && (
        <Alert kind="info" title="Waiting for the host">
          Your card is authorized but not charged. The host has until {b.hostDecisionDueBy ? fmtDateTime(b.hostDecisionDueBy) : 'shortly before arrival'} to accept. If they decline or
          don't respond, the hold is released.
        </Alert>
      )}

      {b.status === 'CONFIRMED' && (
        <Card>
          <div className="stack">
            <div className="row">
              <Icon name="checkCircle" size={22} className="ok-text" />
              <strong>Your space is reserved for {fmtDate(b.startTime)}, {fmtTime(b.startTime)}.</strong>
            </div>
            <p className="muted">
              {now < opens
                ? `Check-in opens 15 minutes before arrival (in ${countdown(opens, now)}).`
                : now < closes
                  ? 'Check-in is open. Check in when you arrive.'
                  : 'The check-in window has closed.'}{' '}
              Check in within 30 minutes after your arrival time or the booking is marked as a no-show.
            </p>
            <div className="row">
              <Button
                variant="primary"
                icon="pin"
                busy={act.busy}
                disabled={now < opens || now >= closes}
                onClick={async () => {
                  const r = await act.run(() => bookings.checkIn(b.id));
                  if (r) {
                    toast('Checked in. Enjoy your stay!');
                    onChange();
                  }
                }}
              >
                I've arrived — check in
              </Button>
              {directions}
            </div>
          </div>
        </Card>
      )}

      {b.status === 'CHECKED_IN' && (
        <Card>
          <div className="stack">
            <p>
              You're checked in until <strong>{fmtTime(b.endTime)}</strong>. Check out when you leave; if you forget, we'll close the booking at your departure time.
            </p>
            <div className="row">
              <Button
                variant="primary"
                icon="logout"
                busy={act.busy}
                onClick={async () => {
                  const r = await act.run(() => bookings.checkOut(b.id));
                  if (r) {
                    toast('Checked out. Thanks for parking with ParkSmart!');
                    onChange();
                  }
                }}
              >
                Check out
              </Button>
              {directions}
            </div>
            <p className="tiny muted">Leaving early doesn't refund unused time.</p>
          </div>
        </Card>
      )}

      {b.status === 'EXPIRED' && (
        <Alert kind="warn" title="This booking expired">
          It wasn't paid in time (or the card was declined), so the space was released. <Link to={`/listings/${b.listingId}`}>Book again</Link>
        </Alert>
      )}
      {b.status === 'PAYMENT_FAILED' && (
        <Alert kind="bad" title="Payment couldn't be completed">
          The bank rejected the charge when the booking was confirmed, so it was cancelled. You haven't been charged. <Link to={`/listings/${b.listingId}`}>Try again</Link>
        </Alert>
      )}
      {b.status === 'DECLINED_BY_HOST' && (
        <Alert kind="warn" title="The host couldn't take this booking">
          The authorization on your card has been released. <Link to="/search">Find another space</Link>
        </Alert>
      )}
      {b.status === 'CANCELLED_BY_HOST' && (
        <Alert kind="warn" title="The host cancelled">
          You get a full refund of {formatMoney(b.refundAmount ?? b.totalAmount)}. Sorry about that. <Link to="/search">Find another space</Link>
        </Alert>
      )}
      {b.status === 'CANCELLED_BY_DRIVER' && (
        <Alert kind="info" title="You cancelled this booking">
          {b.refundAmount && Number(b.refundAmount) > 0 ? `A refund of ${formatMoney(b.refundAmount)} goes back to your card within 5–7 business days.` : 'No refund was due under the cancellation policy.'}
        </Alert>
      )}
      {b.status === 'NO_SHOW' && (
        <Alert kind="warn" title="Marked as a no-show">
          You didn't check in within 30 minutes of your arrival time.
        </Alert>
      )}

      <ProblemView error={act.error} compact />

      {canCancel && (
        <div>
          <button type="button" className="link-btn" style={{ color: 'var(--bad)' }} onClick={() => setCancelling(true)}>
            Cancel booking
          </button>
        </div>
      )}
      {cancelling && <CancelModal booking={b} now={now} asHost={false} onClose={() => setCancelling(false)} onDone={onChange} />}
    </div>
  );
}

function Checkout({ booking: b, now, onChange }: { booking: Booking; now: number; onChange: () => void }) {
  const intent = useLoad(() => payments.intentFor(b.id), [b.id]);
  // The intent is created from the BookingRequested event: a 404 for a moment is normal.
  usePolling(intent.reload, 1500, !intent.data && isStatus(intent.error, 404));
  usePolling(intent.reload, 2500, intent.data?.status === 'AUTHORIZED');
  const due = b.paymentDueBy ? new Date(b.paymentDueBy).getTime() : null;

  return (
    <Card title="Payment">
      <div className="stack">
        {due && due > now && (
          <Alert kind="warn">
            We're holding this space for you for <span className="countdown">{countdown(due, now)}</span>. Complete payment to keep it.
          </Alert>
        )}
        {!intent.data && (!intent.error || isStatus(intent.error, 404)) && <Loading what="payment" />}
        {!intent.data && !!intent.error && !isStatus(intent.error, 404) && <ProblemView error={intent.error} />}
        {intent.data && <PayForm intent={intent.data} instant={b.instantBook} onPaid={() => (intent.reload(), onChange())} />}
      </div>
    </Card>
  );
}

function PayForm({ intent, instant, onPaid }: { intent: PaymentIntent; instant: boolean; onPaid: () => void }) {
  const [name, setName] = useState('');
  const [number, setNumber] = useState('');
  const [exp, setExp] = useState('');
  const [cvc, setCvc] = useState('');
  const [touched, setTouched] = useState(false);
  // One key per payment attempt: a double click or a retry replays instead of charging twice.
  const [key] = useState(newIdempotencyKey);
  const act = useAction();

  if (intent.status === 'FAILED') {
    return (
      <Alert kind="bad" title="Your card was declined">
        {intent.failureCode ? `Reason: ${intent.failureCode.replace(/_/g, ' ')}. ` : ''}This booking has been released. Please book again with a different card.
      </Alert>
    );
  }
  if (intent.status === 'AUTHORIZED' || intent.status === 'CAPTURED') {
    return (
      <div className="row">
        <span className="spinner" /> Payment received. {instant ? 'Confirming your booking…' : 'Sending your request to the host…'}
      </div>
    );
  }
  if (intent.status !== 'CREATED') return <p className="muted">Payment is {intent.status.toLowerCase()}.</p>;

  const digits = number.replace(/\D/g, '');
  const errors = {
    number: !luhnValid(digits) ? 'Enter a valid card number.' : TEST_MODE && !testCardToken(digits) ? 'Test mode: use one of the test cards below.' : null,
    exp: !expiryValid(exp, new Date()) ? 'Enter a future expiry date (MM/YY).' : null,
    cvc: !/^\d{3,4}$/.test(cvc) ? 'Enter the 3 or 4 digit code.' : null,
    name: name.trim().length < 2 ? 'Enter the name on the card.' : null,
  };
  const valid = !errors.number && !errors.exp && !errors.cvc && !errors.name;

  const pay = async () => {
    setTouched(true);
    if (!valid) return;
    // Card details stay in the browser; the processor only ever receives a token.
    const token = testCardToken(digits) ?? 'fake_ok';
    const r = await act.run(() => payments.authorize(intent.id, token, key));
    if (r) onPaid();
  };

  return (
    <form
      className="pay-card"
      onSubmit={(e) => {
        e.preventDefault();
        void pay();
      }}
    >
      <Field label="Name on card" error={touched ? errors.name : null}>
        <input value={name} onChange={(e) => setName(e.target.value)} autoComplete="cc-name" />
      </Field>
      <Field label="Card number" error={touched ? errors.number : null}>
        <input value={number} onChange={(e) => setNumber(formatCardNumber(e.target.value))} inputMode="numeric" autoComplete="cc-number" placeholder="1234 1234 1234 1234" />
      </Field>
      <div className="card-row">
        <Field label="Expiry" error={touched ? errors.exp : null}>
          <input
            value={exp}
            onChange={(e) => {
              const d = e.target.value.replace(/\D/g, '').slice(0, 4);
              setExp(d.length > 2 ? `${d.slice(0, 2)}/${d.slice(2)}` : d);
            }}
            inputMode="numeric"
            autoComplete="cc-exp"
            placeholder="MM/YY"
          />
        </Field>
        <Field label="CVC" error={touched ? errors.cvc : null}>
          <input value={cvc} onChange={(e) => setCvc(e.target.value.replace(/\D/g, '').slice(0, 4))} inputMode="numeric" autoComplete="cc-csc" placeholder="123" />
        </Field>
      </div>
      <Button type="submit" variant="primary" size="lg" block busy={act.busy} icon="shield">
        Pay {formatMoney(intent.amount, intent.currency)}
      </Button>
      <ProblemView error={act.error} compact />
      <p className="tiny muted center">
        {instant ? 'Your card is charged when the booking is confirmed.' : 'Your card is authorized now and charged only if the host accepts.'}
      </p>
      {TEST_MODE && (
        <div className="panel testcards">
          <strong>Test cards</strong> (any future expiry, any CVC)
          {TEST_CARDS.map((c) => (
            <div key={c.token} className="row between">
              <button type="button" className="link-btn" onClick={() => setNumber(c.number)}>
                {c.number}
              </button>
              <span className="muted">{c.result}</span>
            </div>
          ))}
        </div>
      )}
    </form>
  );
}

function CancelModal({ booking: b, now, asHost, onClose, onDone }: { booking: Booking; now: number; asHost: boolean; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const act = useAction();
  const tier = refundTier(b.cancellationPolicy, b.startTime, now, b.totalAmount);
  const refund = asHost || b.status === 'AWAITING_HOST_APPROVAL' ? { percent: 100, amount: b.totalAmount } : tier;
  return (
    <Modal
      title={asHost ? 'Cancel this booking?' : 'Cancel your booking?'}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Keep booking
          </Button>
          <Button
            variant="danger"
            className="solid"
            busy={act.busy}
            disabled={reason.trim().length < 3}
            onClick={async () => {
              const r = await act.run(() => bookings.cancel(b.id, reason.trim()));
              if (r) {
                toast('Booking cancelled');
                onClose();
                onDone();
              }
            }}
          >
            Cancel booking
          </Button>
        </>
      }
    >
      {asHost ? (
        <p>The driver gets a full refund of {formatMoney(b.totalAmount)}. Frequent cancellations can affect your listing.</p>
      ) : (
        <div className="panel stack-xs">
          <div className="row between">
            <span>Estimated refund</span>
            <strong>
              {formatMoney(refund.amount)} ({refund.percent}%)
            </strong>
          </div>
          <span className="small muted">{b.status === 'AWAITING_HOST_APPROVAL' ? 'The host hasn\'t accepted yet, so the hold on your card is released in full.' : tier.explanation}</span>
        </div>
      )}
      <Field label={asHost ? 'Message to the driver' : 'Why are you cancelling?'}>
        <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} placeholder={asHost ? 'e.g. The space is unavailable that day' : 'e.g. Plans changed'} />
      </Field>
      <ProblemView error={act.error} compact />
    </Modal>
  );
}

// ------------------------------------------------------------------ host side
function HostPanel({ booking: b, onChange }: { booking: Booking; onChange: () => void }) {
  const now = useNow(30_000);
  const driver = usePublicProfile(b.driverAccountId);
  const driverReviews = useLoad(() => reviews.list('DRIVER', b.driverAccountId).catch(() => null), [b.driverAccountId]);
  const act = useAction();
  const [declining, setDeclining] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const nav = useNavigate();

  return (
    <div className="stack">
      {b.status === 'AWAITING_HOST_APPROVAL' && (
        <Card>
          <div className="stack">
            <p>
              <strong>{driver?.displayName ?? 'A driver'}</strong> wants to park on <strong>{fmtDate(b.startTime)}</strong>, {fmtTime(b.startTime)} – {fmtTime(b.endTime)}. Their card is
              already authorized for {formatMoney(b.totalAmount)}.
            </p>
            {b.hostDecisionDueBy && (
              <Alert kind="warn">
                Respond within <span className="countdown">{countdown(new Date(b.hostDecisionDueBy).getTime(), now)}</span>. Requests you don't answer expire automatically.
              </Alert>
            )}
            <div className="row">
              <Button
                variant="primary"
                icon="check"
                busy={act.busy}
                onClick={async () => {
                  const r = await act.run(() => bookings.approve(b.id));
                  if (r) {
                    toast('Booking accepted');
                    onChange();
                  }
                }}
              >
                Accept
              </Button>
              <Button variant="danger" busy={act.busy} onClick={() => setDeclining(true)}>
                Decline
              </Button>
            </div>
          </div>
        </Card>
      )}
      {b.status === 'CONFIRMED' && (
        <Alert kind="ok" title="Confirmed">
          {driver?.displayName ?? 'The driver'} arrives {fmtDateTime(b.startTime)}. Make sure the space is free.{' '}
          <button type="button" className="link-btn" style={{ color: 'var(--bad)' }} onClick={() => setCancelling(true)}>
            Cancel booking
          </button>
        </Alert>
      )}
      {b.status === 'CHECKED_IN' && <Alert kind="info">The driver has checked in and is parked until {fmtTime(b.endTime)}.</Alert>}
      {b.status === 'PENDING_PAYMENT' && <Alert kind="info">The driver is completing payment. Unpaid bookings expire after 10 minutes.</Alert>}
      <ProblemView error={act.error} compact />

      <Card title="About the driver">
        <div className="stack">
          <div className="host-box">
            <Avatar name={driver?.displayName} url={driver?.photoUrl} size={48} />
            <div>
              <div className="strong">{driver?.displayName ?? 'ParkSmart driver'}</div>
              <RatingInline avg={driver?.ratingAvg} count={driver?.ratingCount ?? 0} />
            </div>
          </div>
          {driverReviews.data && driverReviews.data.items.length > 0 && (
            <div>
              {driverReviews.data.items.slice(0, 3).map((r) => (
                <div className="review" key={r.id}>
                  <Stars value={r.rating} size={13} />
                  {r.comment && <p className="small">{r.comment}</p>}
                </div>
              ))}
            </div>
          )}
        </div>
      </Card>

      {declining && (
        <Modal
          title="Decline this request?"
          onClose={() => setDeclining(false)}
          footer={
            <>
              <Button variant="ghost" onClick={() => setDeclining(false)}>
                Back
              </Button>
              <Button
                variant="danger"
                className="solid"
                busy={act.busy}
                disabled={reason.trim().length < 3}
                onClick={async () => {
                  const r = await act.run(() => bookings.decline(b.id, reason.trim()));
                  setDeclining(false);
                  if (r) {
                    toast('Request declined');
                    onChange();
                    nav('/host/bookings');
                  }
                }}
              >
                Decline
              </Button>
            </>
          }
        >
          <p className="muted">The driver's card hold is released in full.</p>
          <Field label="Message to the driver">
            <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} placeholder="e.g. The space isn't free at that time" />
          </Field>
        </Modal>
      )}
      {cancelling && <CancelModal booking={b} now={now} asHost onClose={() => setCancelling(false)} onDone={onChange} />}
    </div>
  );
}

// ------------------------------------------------------------------ reviews after completion
function ReviewPanel({ booking: b, isDriver }: { booking: Booking; isDriver: boolean }) {
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [done, setDone] = useState(false);
  const act = useAction();
  const conflict = isStatus(act.error, 409);
  const notYet = isStatus(act.error, 404);
  const icon: IconName = 'star';
  if (done || conflict) {
    return (
      <Alert kind="ok" title={conflict ? "You've already left a review" : 'Thanks for your review!'}>
        {isDriver ? 'It helps other drivers find great spaces.' : 'It helps other hosts know who they are hosting.'}
      </Alert>
    );
  }
  return (
    <Card title={isDriver ? 'How was your parking?' : 'How was this driver?'}>
      <form
        className="form"
        onSubmit={async (e) => {
          e.preventDefault();
          const r = await act.run(() => reviews.submit(b.id, rating, comment.trim()));
          if (r) setDone(true);
        }}
      >
        <StarInput value={rating} onChange={setRating} />
        <Field label="Tell others about it (optional)">
          <textarea value={comment} onChange={(e) => setComment(e.target.value)} maxLength={1000} rows={3} placeholder={isDriver ? 'Easy to find? Safe? Would you park here again?' : 'Punctual, respectful of the space?'} />
        </Field>
        {notYet ? (
          <Alert kind="info">We're still wrapping up this booking. Try again in a few seconds.</Alert>
        ) : (
          <ProblemView error={act.error} compact />
        )}
        <div>
          <Button type="submit" variant="primary" icon={icon} busy={act.busy} disabled={rating === 0}>
            Submit review
          </Button>
        </div>
        <p className="tiny muted">You can review up to 14 days after the booking ends. Reviews are public.</p>
      </form>
    </Card>
  );
}
