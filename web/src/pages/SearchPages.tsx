import { useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { bookings, listings, profile, reviews, search } from '../api/endpoints';
import { newIdempotencyKey } from '../api/client';
import { AMENITIES, VEHICLE_TYPES, type SearchResult } from '../api/types';
import { LeafletMap } from '../components/LeafletMap';
import { POLICY_TEXT } from '../components/ListingForm';
import { Badge, Card, Field, LoadMore, Loading, Money, Notice, ProblemView, Time, useAction, useLoad } from '../components/ui';
import { estimateTotal, fromLocalInput, soonestSlot, toLocalInput } from '../lib';
import { useAuth } from '../state/auth';
import { useNow } from './BookingPages';
import { countdown } from '../lib';

export function SearchPage() {
  const [lat, setLat] = useState(12.9716);
  const [lng, setLng] = useState(77.5946);
  const [radius, setRadius] = useState(5);
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [vehicleType, setVehicleType] = useState('');
  const [maxPrice, setMaxPrice] = useState('');
  const [amen, setAmen] = useState<string[]>([]);
  const [instant, setInstant] = useState('');
  const [sort, setSort] = useState<'distance' | 'price'>('distance');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);
  const act = useAction();

  const params = () => ({
    lat,
    lng,
    radiusKm: radius,
    startTime: start && end ? fromLocalInput(start) : undefined,
    endTime: start && end ? fromLocalInput(end) : undefined,
    vehicleType: vehicleType || undefined,
    maxPricePerHour: maxPrice || undefined,
    amenities: amen.length ? amen.join(',') : undefined,
    instantBook: instant === '' ? undefined : instant === 'true',
    sort,
    limit: 20,
  });
  const run = async (cursor?: string) => {
    const r = await act.run(() => search.listings({ ...params(), cursor }));
    if (!r) return;
    setSearched(true);
    setResults((cur) => (cursor ? [...cur, ...r.items] : r.items));
    setNext(r.nextCursor ?? null);
  };
  const markers = useMemo(
    () => results.map((r) => ({ lat: r.location.lat, lng: r.location.lng, label: `${r.title} (${r.pricePerHour}/h)` })),
    [results],
  );

  return (
    <div className="stack">
      <h2>Search parking</h2>
      <div className="grid2">
        <Card title="Filters">
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault();
              void run();
            }}
          >
            <div className="row">
              <Field label="Latitude">
                <input type="number" step="any" value={lat} onChange={(e) => setLat(Number(e.target.value))} required />
              </Field>
              <Field label="Longitude">
                <input type="number" step="any" value={lng} onChange={(e) => setLng(Number(e.target.value))} required />
              </Field>
              <Field label="Radius km (max 50)">
                <input type="number" step="any" min={0.1} max={50} value={radius} onChange={(e) => setRadius(Number(e.target.value))} />
              </Field>
            </div>
            <div className="row">
              <Field label="From (optional)">
                <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
              </Field>
              <Field label="To (needs From, max 31 days)">
                <input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />
              </Field>
              <button
                type="button"
                onClick={() => {
                  const s = soonestSlot(Date.now());
                  setStart(toLocalInput(s.start));
                  setEnd(toLocalInput(s.end));
                }}
              >
                Soonest slot
              </button>
            </div>
            <div className="row">
              <Field label="Vehicle">
                <select value={vehicleType} onChange={(e) => setVehicleType(e.target.value)}>
                  <option value="">any</option>
                  {VEHICLE_TYPES.map((v) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
              </Field>
              <Field label="Max price/h">
                <input value={maxPrice} onChange={(e) => setMaxPrice(e.target.value)} pattern="\d{1,8}(\.\d{1,2})?" placeholder="100.00" />
              </Field>
              <Field label="Booking mode">
                <select value={instant} onChange={(e) => setInstant(e.target.value)}>
                  <option value="">any</option>
                  <option value="true">instant book</option>
                  <option value="false">request to book</option>
                </select>
              </Field>
              <Field label="Sort">
                <select value={sort} onChange={(e) => setSort(e.target.value as 'distance' | 'price')}>
                  <option value="distance">distance</option>
                  <option value="price">price</option>
                </select>
              </Field>
            </div>
            <div className="row wrap">
              {AMENITIES.map((a) => (
                <label key={a} className="check">
                  <input type="checkbox" checked={amen.includes(a)} onChange={() => setAmen(amen.includes(a) ? amen.filter((x) => x !== a) : [...amen, a])} /> {a}
                </label>
              ))}
            </div>
            <div className="row">
              <button type="submit" disabled={act.busy}>
                Search
              </button>
              <button
                type="button"
                onClick={() =>
                  navigator.geolocation?.getCurrentPosition((p) => {
                    setLat(Number(p.coords.latitude.toFixed(6)));
                    setLng(Number(p.coords.longitude.toFixed(6)));
                  })
                }
              >
                Use my location
              </button>
            </div>
          </form>
          <ProblemView error={act.error} />
        </Card>
        <Card title="Map (click to move the search centre)">
          <LeafletMap center={{ lat, lng }} pick={{ lat, lng }} onPick={(la, ln) => (setLat(la), setLng(ln))} markers={markers} height={300} />
        </Card>
      </div>
      <Notice>
        Search is eventually consistent (a projection built from Kafka events): a just-approved listing shows up after a few seconds. With a time window a page can be short
        or empty and still carry a next cursor: keep loading until there is none.
      </Notice>
      {searched && results.length === 0 && !act.busy && <p className="muted">No results{next ? ' on this page; load more' : ''}.</p>}
      <div className="cards">
        {results.map((r) => (
          <Link key={r.listingId} to={`/listings/${r.listingId}${start && end ? `?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}` : ''}`} className="listing-card">
            {r.thumbnailUrl ? <img src={r.thumbnailUrl} alt="" /> : <div className="noimg">no photo</div>}
            <div>
              <strong>{r.title}</strong>
              <div>{r.address}</div>
              <div>
                <Money value={r.pricePerHour} currency={r.currency} />/h
                {r.estimatedTotal && (
                  <>
                    {' '}
                    | est. <Money value={r.estimatedTotal} currency={r.currency} />
                  </>
                )}
              </div>
              <div className="muted">
                {r.distanceKm != null ? `${r.distanceKm.toFixed(2)} km | ` : ''}
                {r.instantBook ? 'instant' : 'request'} | {r.cancellationPolicy} | up to {r.maxVehicleType} | rating {r.ratingAvg ?? '-'} ({r.ratingCount})
              </div>
              <div className="muted">{r.amenities.join(', ')}</div>
            </div>
          </Link>
        ))}
      </div>
      <LoadMore cursor={next} onMore={() => void run(next ?? undefined)} busy={act.busy} />
    </div>
  );
}

// ------------------------------------------------------------------ listing detail + booking
export function ListingDetailPage() {
  const { id = '' } = useParams();
  const a = useAuth();
  const nav = useNavigate();
  const q = new URLSearchParams(location.search);
  const l = useLoad(async () => (await listings.get(id, a.loggedIn)).data, [id, a.loggedIn]);
  const sum = useLoad(() => reviews.summary('LISTING', id, false).catch(() => null), [id]);
  const revs = useLoad(() => reviews.list('LISTING', id, undefined, false), [id]);
  const veh = useLoad(() => (a.loggedIn ? profile.vehicles().catch(() => ({ items: [] })) : Promise.resolve({ items: [] })), [a.loggedIn]);
  const now = useNow();

  const [start, setStart] = useState(q.get('start') ?? '');
  const [end, setEnd] = useState(q.get('end') ?? '');
  const [vehicleId, setVehicleId] = useState('');
  const act = useAction();
  const lastKey = useRef<{ sig: string; key: string } | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  const l0 = l.data;
  if (l.loading && !l0) return <Loading what="listing" />;
  if (!l0) return <ProblemView error={l.error} />;

  const startIso = start ? fromLocalInput(start) : '';
  const endIso = end ? fromLocalInput(end) : '';
  const valid = !!start && !!end && new Date(endIso) > new Date(startIso);
  const estimate = valid ? estimateTotal(l0.pricePerHour, startIso, endIso) : null;
  const slot = soonestSlot(now);

  const book = async () => {
    const body = { listingId: l0.id, vehicleId: vehicleId || null, startTime: startIso, endTime: endIso };
    const sig = JSON.stringify(body);
    // Same body again -> same key (server replays: 200). A changed body needs a new key (else 422).
    if (!lastKey.current || lastKey.current.sig !== sig) lastKey.current = { sig, key: newIdempotencyKey() };
    const key = lastKey.current.key;
    const r = await act.run(() => bookings.create(body, key));
    if (r) {
      setOutcome(`HTTP ${r.status} (${r.status === 200 ? 'replay of an earlier request with the same Idempotency-Key' : 'created'}), Idempotency-Key ${key}`);
      nav(`/bookings/${r.data.id}`);
    }
  };

  return (
    <div className="stack">
      <div className="row">
        <h2>{l0.title}</h2> <Badge value={l0.status} />
        <div className="grow" />
        <Link to="/search">Back to search</Link>
      </div>
      <div className="grid2">
        <Card>
          <div className="thumbs">
            {l0.photos
              .filter((p) => p.url)
              .map((p) => (
                <img key={p.id} src={p.url as string} alt="" />
              ))}
            {l0.photos.filter((p) => p.url).length === 0 && <div className="noimg">no photos</div>}
          </div>
          <p>{l0.description}</p>
          <dl className="kv">
            <dt>Address</dt>
            <dd>{l0.address}</dd>
            <dt>Price</dt>
            <dd>
              <Money value={l0.pricePerHour} currency={l0.currency} />/h
            </dd>
            <dt>Vehicles</dt>
            <dd>up to {l0.maxVehicleType}</dd>
            <dt>Mode</dt>
            <dd>{l0.instantBook ? 'Instant book' : 'Request to book (host approves)'}</dd>
            <dt>Cancellation</dt>
            <dd>{POLICY_TEXT[l0.cancellationPolicy]}</dd>
            <dt>Buffer</dt>
            <dd>{l0.bufferMinutes} min after each booking</dd>
            <dt>Amenities</dt>
            <dd>{l0.amenities.join(', ') || '-'}</dd>
            <dt>Time zone</dt>
            <dd>{l0.timeZone}</dd>
            <dt>Rating</dt>
            <dd>{sum.data ? `${sum.data.avgRating ?? '-'} (${sum.data.reviewCount} reviews)` : '-'}</dd>
          </dl>
          <details>
            <summary>Weekly availability</summary>
            <ul>
              {l0.availabilityRules.map((r, i) => (
                <li key={i}>
                  {r.dayOfWeek} {r.startTime}-{r.endTime}
                </li>
              ))}
            </ul>
          </details>
        </Card>
        <Card title="Location">
          <LeafletMap center={l0.location} markers={[{ ...l0.location, label: l0.title }]} height={260} />
        </Card>
      </div>

      <Card title="Quote and book">
        <Notice>
          The real booking quote is an internal Listing endpoint (the gateway never routes <code>/internal/**</code>), so the console shows a client-side estimate. Booking
          fetches the authoritative quote itself and rejects unbookable windows (422 listing-not-bookable with the reason).
        </Notice>
        <div className="row">
          <Field label="Start" hint="15-minute boundary, at least 15 min from now">
            <input type="datetime-local" step={900} value={start} onChange={(e) => setStart(e.target.value)} />
          </Field>
          <Field label="End" hint="30 min to 30 days after start, 15-minute boundary">
            <input type="datetime-local" step={900} value={end} onChange={(e) => setEnd(e.target.value)} />
          </Field>
          <button
            type="button"
            onClick={() => {
              setStart(toLocalInput(slot.start));
              setEnd(toLocalInput(slot.end));
            }}
          >
            Soonest slot (fast lane)
          </button>
        </div>
        <p className="muted">
          Fast lane: start {slot.start.toLocaleTimeString()} (check-in opens {slot.checkInOpens.toLocaleTimeString()}, in {countdown(slot.checkInOpens.getTime(), now)}). After check-in,
          check-out completes the booking at once, which opens reviews. Your listing's availability rules must cover the window (use the 24/7 preset).
        </p>
        <Field label="Vehicle (optional)">
          <select value={vehicleId} onChange={(e) => setVehicleId(e.target.value)}>
            <option value="">none</option>
            {veh.data?.items.map((v) => (
              <option key={v.id} value={v.id}>
                {v.licensePlate} ({v.vehicleType})
              </option>
            ))}
          </select>
        </Field>
        {estimate && (
          <p>
            Estimate: <Money value={estimate} currency={l0.currency} /> ({l0.pricePerHour}/h x hours). UTC window: {startIso} to {endIso}
          </p>
        )}
        {a.loggedIn ? (
          <button disabled={act.busy || !valid || l0.status !== 'PUBLISHED'} onClick={() => void book()}>
            Book (sends an Idempotency-Key)
          </button>
        ) : (
          <p>
            <Link to="/login" state={{ from: `/listings/${l0.id}` }}>
              Log in
            </Link>{' '}
            to book.
          </p>
        )}
        {l0.status !== 'PUBLISHED' && <p className="muted">Only PUBLISHED listings can be booked.</p>}
        {outcome && <Notice kind="ok">{outcome}</Notice>}
        <ProblemView error={act.error} />
        <p className="muted">Click Book twice with the same window to see a replay (same key = same booking). Change the window and a new key is generated.</p>
      </Card>

      <Card title="Reviews">
        <ProblemView error={revs.error} />
        {revs.data?.items.length === 0 && <p className="muted">No reviews yet.</p>}
        <ul className="plain">
          {revs.data?.items.map((r) => (
            <li key={r.id}>
              {'*'.repeat(r.rating)} <span className="muted">{r.rating}/5</span> {r.comment} <span className="muted"><Time value={r.createdAt} /></span> <code className="muted">{r.id.slice(0, 8)}</code>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
