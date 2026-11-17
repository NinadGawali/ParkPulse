import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { admin, bookings, listings, profile, reviews } from '../api/endpoints';
import { newIdempotencyKey } from '../api/client';
import { DAYS, VEHICLE_TYPES, type AvailabilityRule, type Listing, type Review } from '../api/types';
import { AMENITY_META, DAY_LABEL, POLICY_META, VEHICLE_FITS, VEHICLE_LABEL } from '../labels';
import { defaultWindow, directionsUrl, durationLabel, estimateTotal, formatMoney, timeLabel, validateWindow } from '../lib';
import { Icon } from '../components/Icon';
import { LeafletMap } from '../components/LeafletMap';
import { WhenPicker } from '../components/WhenPicker';
import {
  Alert,
  Avatar,
  Button,
  Field,
  Loading,
  LoadMore,
  Modal,
  ProblemView,
  RatingInline,
  Stars,
  StatusBadge,
  fmtDate,
  toast,
  useAction,
  useDocumentTitle,
  useLoad,
  useNow,
} from '../components/ui';
import { usePaged } from '../components/paged';
import { forgetListing, usePublicProfile } from '../state/cache';
import { useAuth } from '../state/auth';

export function ListingPage() {
  const { id = '' } = useParams();
  const a = useAuth();
  const l = useLoad(async () => (await listings.get(id, a.loggedIn)).data, [id, a.loggedIn]);
  const sum = useLoad(() => reviews.summary('LISTING', id, false).catch(() => null), [id]);
  useDocumentTitle(l.data?.title);

  if (!a.ready || (l.loading && !l.data)) return <Loading what="space" />;
  if (!l.data) {
    return (
      <div className="container narrow page">
        <ProblemView error={l.error} />
        <p style={{ marginTop: 16 }}>
          <Link to="/search">Back to search</Link>
        </p>
      </div>
    );
  }
  const x = l.data;
  const isOwner = x.hostAccountId === a.accountId;
  const photos = x.photos.filter((p) => p.status === 'READY' && p.url).map((p) => p.url as string);

  return (
    <div className="container page">
      <div className="stack-lg">
        {a.hasRole('ADMIN') && x.status === 'PENDING_REVIEW' && !isOwner && <ModerationBar listing={x} onDone={l.reload} />}
        {isOwner && (
          <Alert kind="info" title="This is your listing">
            <span className="row">
              <span>
                Status: <StatusBadge kind="listing" value={x.status} />
              </span>
              <Link to={`/host/listings/${x.id}`}>Manage listing</Link>
            </span>
          </Alert>
        )}
        {!isOwner && x.status !== 'PUBLISHED' && !a.hasRole('ADMIN') && <Alert kind="warn">This space isn't taking bookings right now.</Alert>}

        <div className="stack-sm">
          <h1 style={{ fontSize: '1.8rem' }}>{x.title}</h1>
          <div className="row small">
            <RatingInline avg={sum.data?.avgRating} count={sum.data?.reviewCount ?? 0} />
            <span className="muted">·</span>
            <span className="row" style={{ gap: 4 }}>
              <Icon name="pin" size={15} /> {x.address}
            </span>
            {x.instantBook && (
              <span className="badge brand">
                <Icon name="bolt" size={12} /> Instant book
              </span>
            )}
          </div>
        </div>

        <Gallery photos={photos} />

        <div className="with-aside">
          <div className="stack-lg">
            <HostBox hostId={x.hostAccountId} />
            <hr className="divider" style={{ margin: 0 }} />
            {x.description && (
              <section className="stack-sm">
                <h3>About this space</h3>
                <p style={{ whiteSpace: 'pre-line' }}>{x.description}</p>
              </section>
            )}
            <section className="stack">
              <h3>What this space offers</h3>
              <div className="amenity-list">
                <div className="amenity">
                  <Icon name="car" size={22} /> {VEHICLE_FITS[x.maxVehicleType]}
                </div>
                {x.amenities.map((am) => (
                  <div className="amenity" key={am}>
                    <Icon name={AMENITY_META[am]?.icon ?? 'check'} size={22} /> {AMENITY_META[am]?.label ?? am}
                  </div>
                ))}
              </div>
            </section>
            <section className="stack-sm">
              <h3>Opening hours</h3>
              <Hours rules={x.availabilityRules} timeZone={x.timeZone} />
            </section>
            <section className="stack-sm" id="policy">
              <h3>Cancellation policy: {POLICY_META[x.cancellationPolicy].label}</h3>
              <p className="muted">{POLICY_META[x.cancellationPolicy].long}</p>
              {x.bufferMinutes > 0 && <p className="muted small">The host keeps {x.bufferMinutes} minutes free after each booking.</p>}
            </section>
            <section className="stack-sm">
              <h3>Where you'll park</h3>
              <p className="muted">{x.address}</p>
              <LeafletMap center={x.location} markers={[{ ...x.location, label: x.title }]} height={300} zoom={15} />
              <a href={directionsUrl(x.location.lat, x.location.lng)} target="_blank" rel="noreferrer" className="small">
                Open in Google Maps <Icon name="external" size={13} />
              </a>
            </section>
            <ReviewsSection listingId={x.id} avg={sum.data?.avgRating} count={sum.data?.reviewCount ?? 0} />
          </div>
          <aside className="sticky">
            <ReserveBox listing={x} isOwner={isOwner} />
          </aside>
        </div>
      </div>
    </div>
  );
}

function Gallery({ photos }: { photos: string[] }) {
  const [open, setOpen] = useState<number | null>(null);
  if (photos.length === 0) {
    return (
      <div className="gallery n1">
        <div className="noimg">
          <Icon name="image" size={40} />
        </div>
      </div>
    );
  }
  const shown = photos.slice(0, 5);
  return (
    <>
      <div className={`gallery n${Math.min(shown.length, 5)}`}>
        {shown.map((u, i) => (
          <div key={u}>
            <img src={u} alt={`Photo ${i + 1}`} onClick={() => setOpen(i)} />
          </div>
        ))}
      </div>
      {open !== null && (
        <div className="lightbox" onClick={() => setOpen(null)} role="dialog" aria-label="Photos">
          <img src={photos[open]} alt={`Photo ${open + 1} of ${photos.length}`} onClick={(e) => e.stopPropagation()} />
          <button type="button" className="icon-btn lb-close" onClick={() => setOpen(null)} aria-label="Close">
            <Icon name="x" />
          </button>
          {photos.length > 1 && (
            <>
              <button type="button" className="icon-btn lb-prev" aria-label="Previous" onClick={(e) => (e.stopPropagation(), setOpen((open + photos.length - 1) % photos.length))}>
                <Icon name="chevronLeft" />
              </button>
              <button type="button" className="icon-btn lb-next" aria-label="Next" onClick={(e) => (e.stopPropagation(), setOpen((open + 1) % photos.length))}>
                <Icon name="chevronRight" />
              </button>
            </>
          )}
        </div>
      )}
    </>
  );
}

function HostBox({ hostId }: { hostId: string }) {
  const host = usePublicProfile(hostId);
  return (
    <div className="host-box">
      <Avatar name={host?.displayName} url={host?.photoUrl} size={52} />
      <div>
        <div className="strong">Hosted by {host?.displayName ?? 'a ParkSmart host'}</div>
        <div className="small muted">Hosts have a verified phone number, and every listing is reviewed by our team.</div>
      </div>
    </div>
  );
}

export function Hours({ rules, timeZone }: { rules: AvailabilityRule[]; timeZone: string }) {
  if (rules.length === 0) return <p className="muted">No opening hours set yet.</p>;
  const allDay = (r: AvailabilityRule) => r.startTime === '00:00' && r.endTime === '24:00';
  if (DAYS.every((d) => rules.some((r) => r.dayOfWeek === d && allDay(r)))) {
    return (
      <p className="row">
        <Icon name="sun" /> Open 24/7
      </p>
    );
  }
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return (
    <div className="stack-xs">
      <dl className="kv">
        {DAYS.map((d) => {
          const day = rules.filter((r) => r.dayOfWeek === d).sort((p, q) => p.startTime.localeCompare(q.startTime));
          return (
            <div key={d} style={{ display: 'contents' }}>
              <dt>{DAY_LABEL[d]}</dt>
              <dd>{day.length === 0 ? <span className="muted">Closed</span> : day.map((r) => (allDay(r) ? 'Open 24 hours' : `${timeLabel(r.startTime)} – ${timeLabel(r.endTime)}`)).join(', ')}</dd>
            </div>
          );
        })}
      </dl>
      {timeZone !== browserZone && <p className="tiny muted">Times are local to the space ({timeZone}).</p>}
    </div>
  );
}

const VEHICLE_RANK = Object.fromEntries(VEHICLE_TYPES.map((v, i) => [v, i])) as Record<string, number>;

function ReserveBox({ listing, isOwner }: { listing: Listing; isOwner: boolean }) {
  const a = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const now = useNow(30_000);
  const q = new URLSearchParams(loc.search);
  const [win, setWin] = useState(() => {
    const qs = q.get('start');
    const qe = q.get('end');
    if (qs && qe && !validateWindow(qs, qe, Date.now())) return { start: qs, end: qe };
    const w = defaultWindow(Date.now());
    return { start: w.start.toISOString(), end: w.end.toISOString() };
  });
  const veh = useLoad(() => (a.loggedIn ? profile.vehicles().then((r) => r.items).catch(() => []) : Promise.resolve([])), [a.loggedIn]);
  const [vehicleId, setVehicleId] = useState('');
  useEffect(() => {
    const primary = veh.data?.find((v) => v.primary) ?? veh.data?.[0];
    if (primary && !vehicleId) setVehicleId(primary.id);
  }, [veh.data, vehicleId]);
  const act = useAction();
  const lastKey = useRef<{ sig: string; key: string } | null>(null);

  const problem = validateWindow(win.start, win.end, now);
  const total = problem ? null : estimateTotal(listing.pricePerHour, win.start, win.end);
  const hours = (new Date(win.end).getTime() - new Date(win.start).getTime()) / 3_600_000;
  const vehicle = veh.data?.find((v) => v.id === vehicleId);
  const tooBig = !!vehicle && VEHICLE_RANK[vehicle.vehicleType] > VEHICLE_RANK[listing.maxVehicleType];
  const bookable = listing.status === 'PUBLISHED' && !isOwner;

  const book = async () => {
    const body = { listingId: listing.id, vehicleId: vehicleId || null, startTime: win.start, endTime: win.end };
    const sig = JSON.stringify(body);
    // A double click or retry with the same details replays the same booking (same Idempotency-Key).
    if (!lastKey.current || lastKey.current.sig !== sig) lastKey.current = { sig, key: newIdempotencyKey() };
    const key = lastKey.current.key;
    const r = await act.run(() => bookings.create(body, key));
    if (r) nav(`/bookings/${r.data.id}`);
  };

  return (
    <div className="card reserve">
      <div className="row between">
        <span>
          <span className="price">{formatMoney(listing.pricePerHour, listing.currency)}</span> <span className="muted">/ hour</span>
        </span>
        {listing.instantBook ? (
          <span className="badge brand">
            <Icon name="bolt" size={12} /> Instant
          </span>
        ) : (
          <span className="badge">Host approves</span>
        )}
      </div>
      <WhenPicker start={win.start} end={win.end} onChange={(start, end) => setWin({ start, end })} />
      {a.loggedIn && (
        <Field label="Vehicle" error={tooBig ? `This space fits ${VEHICLE_FITS[listing.maxVehicleType].toLowerCase()}.` : null}>
          <select value={vehicleId} onChange={(e) => setVehicleId(e.target.value)}>
            <option value="">I'll add it later</option>
            {veh.data?.map((v) => (
              <option key={v.id} value={v.id}>
                {v.licensePlate} · {VEHICLE_LABEL[v.vehicleType]}
              </option>
            ))}
          </select>
        </Field>
      )}
      {problem ? (
        <p className="small warn-text">{problem}</p>
      ) : (
        <div className="breakdown">
          <div className="line">
            <span className="muted">
              {formatMoney(listing.pricePerHour)} × {Number.isInteger(hours) ? hours : hours.toFixed(2)} h ({durationLabel(win.start, win.end)})
            </span>
            <span>{formatMoney(total)}</span>
          </div>
          <div className="line total">
            <span>Total</span>
            <span>{formatMoney(total)}</span>
          </div>
        </div>
      )}
      {!bookable ? (
        <Button block disabled>
          {isOwner ? 'You host this space' : 'Not available'}
        </Button>
      ) : !a.loggedIn ? (
        <Button variant="primary" size="lg" block onClick={() => nav('/login', { state: { from: `${loc.pathname}?start=${encodeURIComponent(win.start)}&end=${encodeURIComponent(win.end)}` } })}>
          Log in to book
        </Button>
      ) : (
        <Button variant="primary" size="lg" block busy={act.busy} disabled={!!problem || tooBig} onClick={() => void book()}>
          {listing.instantBook ? 'Reserve' : 'Request to book'}
        </Button>
      )}
      <ProblemView error={act.error} compact />
      <p className="tiny muted center">
        {listing.instantBook ? "You'll pay on the next step. The booking is confirmed as soon as payment goes through." : 'You pay now; the host has up to 24 hours to accept. Fully refunded if they decline.'}{' '}
        {POLICY_META[listing.cancellationPolicy].short}.
      </p>
    </div>
  );
}

function ReviewsSection({ listingId, avg, count }: { listingId: string; avg?: string | null; count: number }) {
  const a = useAuth();
  const p = usePaged((c) => reviews.list('LISTING', listingId, c, false), [listingId]);
  const [hiding, setHiding] = useState<Review | null>(null);
  const hidden = useMemo(() => new Set<string>(), []);
  const [, force] = useState(0);
  return (
    <section className="stack-sm" id="reviews">
      <h3 className="row">
        {count > 0 ? (
          <>
            <Icon name="star" size={18} /> {Number(avg).toFixed(1)} · {count} review{count === 1 ? '' : 's'}
          </>
        ) : (
          'Reviews'
        )}
      </h3>
      {p.items.length === 0 && !p.loading && <p className="muted">No reviews yet. Be the first to park here.</p>}
      <div>
        {p.items
          .filter((r) => !hidden.has(r.id))
          .map((r) => (
            <div className="review" key={r.id}>
              <div className="row between">
                <Stars value={r.rating} />
                <span className="small muted">{fmtDate(r.createdAt)}</span>
              </div>
              {r.comment && <p>{r.comment}</p>}
              {a.hasRole('ADMIN') && (
                <button type="button" className="link-btn small" onClick={() => setHiding(r)} style={{ alignSelf: 'flex-start' }}>
                  Hide review
                </button>
              )}
            </div>
          ))}
      </div>
      <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
      {hiding && (
        <HideReviewModal
          review={hiding}
          onClose={() => setHiding(null)}
          onHidden={() => {
            hidden.add(hiding.id);
            setHiding(null);
            force((n) => n + 1);
          }}
        />
      )}
    </section>
  );
}

export function HideReviewModal({ review, onClose, onHidden }: { review: Review; onClose: () => void; onHidden: () => void }) {
  const [reason, setReason] = useState('');
  const act = useAction();
  return (
    <Modal
      title="Hide this review?"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="danger"
            className="solid"
            busy={act.busy}
            disabled={reason.trim().length < 3}
            onClick={async () => {
              const r = await act.run(() => admin.hideReview(review.id, reason.trim()));
              if (r) {
                toast('Review hidden');
                onHidden();
              }
            }}
          >
            Hide review
          </Button>
        </>
      }
    >
      <p className="muted">Hidden reviews disappear from the listing and its rating is recalculated.</p>
      <Field label="Reason (kept in the audit log)">
        <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} placeholder="e.g. Contains personal information" />
      </Field>
      <ProblemView error={act.error} compact />
    </Modal>
  );
}

function ModerationBar({ listing, onDone }: { listing: Listing; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const act = useAction();
  const nav = useNavigate();
  const decide = async (approve: boolean) => {
    const r = await act.run(() => (approve ? admin.approve(listing.id, reason || undefined) : admin.reject(listing.id, reason)));
    if (r) {
      forgetListing(listing.id);
      toast(approve ? 'Listing approved and published' : 'Listing sent back to the host');
      onDone();
      nav('/admin');
    }
  };
  return (
    <div className="stack-sm">
      <div className="admin-bar">
        <Icon name="shield" />
        <strong>Review this listing</strong>
        <div className="grow" />
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Reason (required to reject)" maxLength={500} />
        <Button size="sm" busy={act.busy} disabled={!reason.trim()} onClick={() => void decide(false)}>
          Reject
        </Button>
        <Button size="sm" variant="primary" busy={act.busy} onClick={() => void decide(true)}>
          Approve
        </Button>
      </div>
      <ProblemView error={act.error} compact />
    </div>
  );
}
