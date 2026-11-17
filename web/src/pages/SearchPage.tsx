import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { search, type SearchParams } from '../api/endpoints';
import { AMENITIES, VEHICLE_TYPES, type SearchResult } from '../api/types';
import { AMENITY_META, CITIES, VEHICLE_LABEL } from '../labels';
import { defaultWindow, durationLabel, formatMoney } from '../lib';
import { Icon } from '../components/Icon';
import { LeafletMap } from '../components/LeafletMap';
import { ListingCard, ListingCardSkeleton } from '../components/ListingCard';
import { WhenPicker } from '../components/WhenPicker';
import { Button, Empty, LoadMore, Modal, ProblemView, useAction, useDocumentTitle } from '../components/ui';

const PRICE_CAPS = ['', '30', '50', '80', '120', '200'];

export function SearchPage() {
  const [sp, setSp] = useSearchParams();
  const lat = Number(sp.get('lat') ?? CITIES[0].lat);
  const lng = Number(sp.get('lng') ?? CITIES[0].lng);
  const place = sp.get('place') ?? (sp.get('lat') ? 'this area' : CITIES[0].name);
  const fallback = useMemo(() => defaultWindow(Date.now()), []);
  const startParam = sp.get('start');
  const endParam = sp.get('end');
  // A window in the past (old link) falls back to the next available slot.
  const stale = !startParam || new Date(startParam).getTime() < Date.now() + 15 * 60_000;
  const start = stale ? fallback.start.toISOString() : (startParam as string);
  const end = stale || !endParam ? fallback.end.toISOString() : endParam;
  const vehicleType = sp.get('vehicle') ?? '';
  const maxPrice = sp.get('maxPrice') ?? '';
  const instant = sp.get('instant') === '1';
  const amenities = (sp.get('amenities') ?? '').split(',').filter(Boolean);
  const sort = (sp.get('sort') as 'distance' | 'price') ?? 'distance';
  const radius = Number(sp.get('radius') ?? 5);
  useDocumentTitle(`Parking in ${place}`);

  const update = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(sp);
    if (stale) {
      next.set('start', start);
      next.set('end', end);
    }
    for (const [k, v] of Object.entries(patch)) {
      if (v === null || v === '') next.delete(k);
      else next.set(k, v);
    }
    setSp(next, { replace: true });
  };

  const [results, setResults] = useState<SearchResult[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);
  const [hover, setHover] = useState<string | null>(null);
  const [mapCenter, setMapCenter] = useState<{ lat: number; lng: number } | null>(null);
  const [showMap, setShowMap] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const act = useAction();

  const params = (): SearchParams => ({
    lat,
    lng,
    radiusKm: radius,
    startTime: start,
    endTime: end,
    vehicleType: vehicleType || undefined,
    maxPricePerHour: maxPrice ? `${maxPrice}.00` : undefined,
    amenities: amenities.length ? amenities.join(',') : undefined,
    instantBook: instant ? true : undefined,
    sort,
    limit: 20,
  });

  // With a time window a page can be short (unavailable spaces are filtered after paging); follow a few cursors.
  const fetchFrom = async (cursor: string | undefined, acc: SearchResult[]): Promise<{ items: SearchResult[]; next: string | null }> => {
    let items = acc;
    let c = cursor;
    for (let i = 0; i < 4; i++) {
      const page = await search.listings({ ...params(), cursor: c });
      items = [...items, ...page.items];
      c = page.nextCursor ?? undefined;
      if (!c || items.length - acc.length >= 8) break;
    }
    return { items, next: c ?? null };
  };

  const key = sp.toString() + '|' + start + end;
  useEffect(() => {
    let alive = true;
    setMapCenter(null);
    void act
      .run(() => fetchFrom(undefined, []))
      .then((r) => {
        if (!alive || !r) return;
        setResults(r.items);
        setNext(r.next);
        setSearched(true);
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const more = async () => {
    const r = await act.run(() => fetchFrom(next ?? undefined, results));
    if (r) {
      setResults(r.items);
      setNext(r.next);
    }
  };

  const markers = useMemo(
    () => results.map((r) => ({ id: r.listingId, lat: r.location.lat, lng: r.location.lng, pill: formatMoney(r.pricePerHour, r.currency), onClick: () => setHover(r.listingId) })),
    [results],
  );
  const cardQuery = new URLSearchParams({ start, end }).toString();
  const moreCount = amenities.length + (vehicleType ? 1 : 0) + (radius !== 5 ? 1 : 0);

  return (
    <div className={`search-layout ${showMap ? 'show-map' : ''}`}>
      <div className="search-results">
        <div className="search-filters">
          <div className="row between">
            <div>
              <h2 style={{ fontSize: '1.25rem' }}>Parking in {place}</h2>
              <span className="small muted">
                {durationLabel(start, end)} · {searched ? `${results.length}${next ? '+' : ''} spaces` : 'searching…'}
              </span>
            </div>
            <select
              value={place && CITIES.some((c) => c.name === place) ? place : ''}
              onChange={(e) => {
                const c = CITIES.find((x) => x.name === e.target.value);
                if (c) update({ lat: String(c.lat), lng: String(c.lng), place: c.name });
              }}
              style={{ width: 'auto' }}
              aria-label="City"
            >
              <option value="" disabled>
                Change city
              </option>
              {CITIES.map((c) => (
                <option key={c.name}>{c.name}</option>
              ))}
            </select>
          </div>
          <WhenPicker start={start} end={end} onChange={(s, e) => update({ start: s, end: e })} layout="inline" />
          <div className="filter-row">
            <button type="button" className={`chip ${instant ? 'on' : ''}`} onClick={() => update({ instant: instant ? null : '1' })}>
              <Icon name="bolt" size={14} /> Instant book
            </button>
            <select value={maxPrice} onChange={(e) => update({ maxPrice: e.target.value })} style={{ width: 'auto' }} aria-label="Maximum price per hour" className="chip">
              {PRICE_CAPS.map((p) => (
                <option key={p} value={p}>
                  {p ? `Up to ₹${p}/h` : 'Any price'}
                </option>
              ))}
            </select>
            <select value={sort} onChange={(e) => update({ sort: e.target.value })} style={{ width: 'auto' }} aria-label="Sort" className="chip">
              <option value="distance">Closest first</option>
              <option value="price">Cheapest first</option>
            </select>
            <button type="button" className={`chip ${moreCount ? 'on' : ''}`} onClick={() => setMoreOpen(true)}>
              <Icon name="filter" size={14} /> Filters{moreCount ? ` (${moreCount})` : ''}
            </button>
            <button
              type="button"
              className="chip"
              onClick={() =>
                navigator.geolocation?.getCurrentPosition((p) =>
                  update({ lat: p.coords.latitude.toFixed(6), lng: p.coords.longitude.toFixed(6), place: 'Near you' }),
                )
              }
            >
              <Icon name="locate" size={14} /> Near me
            </button>
          </div>
        </div>

        <ProblemView error={act.error} />
        {act.busy && results.length === 0 ? (
          <div className="grid-auto">
            {[0, 1, 2, 3].map((i) => (
              <ListingCardSkeleton key={i} />
            ))}
          </div>
        ) : searched && results.length === 0 && !act.error ? (
          <Empty icon="search" title="No spaces available for these times">
            Try a wider area, different times or fewer filters.
            <span className="row" style={{ justifyContent: 'center', marginTop: 12 }}>
              {radius < 25 && (
                <Button size="sm" onClick={() => update({ radius: String(Math.min(25, radius * 2)) })}>
                  Search within {Math.min(25, radius * 2)} km
                </Button>
              )}
            </span>
          </Empty>
        ) : (
          <div className="grid-auto">
            {results.map((r) => (
              <ListingCard key={r.listingId} r={r} query={cardQuery} active={hover === r.listingId} onHover={setHover} />
            ))}
          </div>
        )}
        <div style={{ marginTop: 20 }}>
          <LoadMore cursor={next} onMore={() => void more()} busy={act.busy} />
        </div>
      </div>

      <div className="search-map">
        <LeafletMap center={{ lat, lng }} markers={markers} highlight={hover} fitToMarkers={false} zoom={13} height="100%" onMoveEnd={(c, byUser) => byUser && setMapCenter(c)} />
        {mapCenter && (
          <div className="map-overlay">
            <Button variant="dark" size="sm" icon="refresh" onClick={() => update({ lat: String(mapCenter.lat), lng: String(mapCenter.lng), place: 'this area' })}>
              Search this area
            </Button>
          </div>
        )}
      </div>

      <Button variant="dark" className="map-toggle mobile-only" icon={showMap ? 'list' : 'map'} onClick={() => setShowMap(!showMap)}>
        {showMap ? 'List' : 'Map'}
      </Button>

      {moreOpen && (
        <FiltersModal
          initial={{ vehicleType, amenities, radius }}
          onClose={() => setMoreOpen(false)}
          onApply={(f) => {
            update({ vehicle: f.vehicleType, amenities: f.amenities.join(','), radius: f.radius === 5 ? null : String(f.radius) });
            setMoreOpen(false);
          }}
        />
      )}
    </div>
  );
}

function FiltersModal({
  initial,
  onClose,
  onApply,
}: {
  initial: { vehicleType: string; amenities: string[]; radius: number };
  onClose: () => void;
  onApply: (f: { vehicleType: string; amenities: string[]; radius: number }) => void;
}) {
  const [f, setF] = useState(initial);
  return (
    <Modal
      title="Filters"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={() => setF({ vehicleType: '', amenities: [], radius: 5 })}>
            Clear all
          </Button>
          <Button variant="primary" onClick={() => onApply(f)}>
            Show spaces
          </Button>
        </>
      }
    >
      <div className="stack-sm">
        <span className="field-label">Your vehicle</span>
        <div className="chips">
          <button type="button" className={`chip ${f.vehicleType === '' ? 'on' : ''}`} onClick={() => setF({ ...f, vehicleType: '' })}>
            Any
          </button>
          {VEHICLE_TYPES.map((v) => (
            <button key={v} type="button" className={`chip ${f.vehicleType === v ? 'on' : ''}`} onClick={() => setF({ ...f, vehicleType: v })}>
              {VEHICLE_LABEL[v]}
            </button>
          ))}
        </div>
      </div>
      <div className="stack-sm">
        <span className="field-label">Must have</span>
        <div className="chips">
          {AMENITIES.map((a) => {
            const on = f.amenities.includes(a);
            return (
              <button
                key={a}
                type="button"
                className={`chip ${on ? 'on' : ''}`}
                onClick={() => setF({ ...f, amenities: on ? f.amenities.filter((x) => x !== a) : [...f.amenities, a] })}
              >
                <Icon name={AMENITY_META[a].icon} size={14} /> {AMENITY_META[a].label}
              </button>
            );
          })}
        </div>
      </div>
      <div className="stack-sm">
        <span className="field-label">Distance: within {f.radius} km</span>
        <input type="range" min={1} max={50} value={f.radius} onChange={(e) => setF({ ...f, radius: Number(e.target.value) })} />
      </div>
    </Modal>
  );
}
