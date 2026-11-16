import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { search } from '../api/endpoints';
import { CITIES } from '../labels';
import { defaultWindow } from '../lib';
import { Icon, type IconName } from '../components/Icon';
import { ListingCard, ListingCardSkeleton } from '../components/ListingCard';
import { WhenPicker } from '../components/WhenPicker';
import { Button, useDocumentTitle, useLoad } from '../components/ui';
import { useAuth } from '../state/auth';

export function searchQuery(p: { lat: number; lng: number; place?: string; start?: string; end?: string }): string {
  const q = new URLSearchParams({ lat: String(p.lat), lng: String(p.lng) });
  if (p.place) q.set('place', p.place);
  if (p.start && p.end) {
    q.set('start', p.start);
    q.set('end', p.end);
  }
  return q.toString();
}

const STEPS: { icon: IconName; title: string; text: string }[] = [
  { icon: 'search', title: 'Search', text: 'Pick a neighbourhood and your arrival and departure times. See prices and the total before you book.' },
  { icon: 'card', title: 'Book and pay', text: 'Instant-book spaces confirm right away. Others confirm once the host accepts. You are only charged when confirmed.' },
  { icon: 'car', title: 'Park', text: 'Get directions to the exact spot, check in when you arrive and check out when you leave.' },
];

export function HomePage() {
  useDocumentTitle(null);
  const nav = useNavigate();
  const a = useAuth();
  const [city, setCity] = useState<(typeof CITIES)[number]>(CITIES[0]);
  const [win, setWin] = useState(() => {
    const w = defaultWindow(Date.now());
    return { start: w.start.toISOString(), end: w.end.toISOString() };
  });
  const [locating, setLocating] = useState(false);
  const featured = useLoad(() => search.listings({ lat: CITIES[0].lat, lng: CITIES[0].lng, radiusKm: 25, limit: 8 }).then((p) => p.items), []);

  const go = (lat: number, lng: number, place: string) => nav(`/search?${searchQuery({ lat, lng, place, start: win.start, end: win.end })}`);
  const useMyLocation = () => {
    if (!navigator.geolocation) return;
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      (p) => {
        setLocating(false);
        go(Number(p.coords.latitude.toFixed(6)), Number(p.coords.longitude.toFixed(6)), 'Near you');
      },
      () => setLocating(false),
      { timeout: 10000 },
    );
  };

  return (
    <>
      <section className="hero">
        <div className="container">
          <span className="eyebrow" style={{ color: '#7ee0cf' }}>
            Hourly parking, booked in a minute
          </span>
          <h1 style={{ marginTop: 10 }}>Park where you're going, not three blocks away.</h1>
          <p className="lead">Book private driveways, garages and lots from local hosts. Know the price up front and have a guaranteed spot when you arrive.</p>
          <form
            className="search-panel"
            onSubmit={(e) => {
              e.preventDefault();
              go(city.lat, city.lng, city.name);
            }}
          >
            <label className="field">
              <span className="field-label">Where</span>
              <select value={city.name} onChange={(e) => setCity(CITIES.find((c) => c.name === e.target.value) ?? CITIES[0])}>
                {CITIES.map((c) => (
                  <option key={c.name}>{c.name}</option>
                ))}
              </select>
            </label>
            <div className="field">
              <span className="field-label">When</span>
              <WhenPicker start={win.start} end={win.end} onChange={(start, end) => setWin({ start, end })} layout="inline" />
            </div>
            <Button type="submit" variant="primary" size="lg" icon="search">
              Find parking
            </Button>
          </form>
          <div className="city-chips">
            <button type="button" className="chip" onClick={useMyLocation} disabled={locating}>
              <Icon name="locate" size={15} /> {locating ? 'Locating…' : 'Near me'}
            </button>
            {CITIES.slice(1).map((c) => (
              <button key={c.name} type="button" className="chip" onClick={() => go(c.lat, c.lng, c.name)}>
                {c.name}
              </button>
            ))}
          </div>
        </div>
      </section>

      <section className="section">
        <div className="container">
          <div className="section-head">
            <div>
              <span className="eyebrow">Popular in Bengaluru</span>
              <h2>Spaces drivers love</h2>
            </div>
            <div className="grow" />
            <Link to={`/search?${searchQuery({ lat: CITIES[0].lat, lng: CITIES[0].lng, place: CITIES[0].name, ...win })}`} className="btn">
              See all <Icon name="arrowRight" size={16} />
            </Link>
          </div>
          {featured.loading ? (
            <div className="grid-auto">
              {[0, 1, 2, 3].map((i) => (
                <ListingCardSkeleton key={i} />
              ))}
            </div>
          ) : featured.data && featured.data.length > 0 ? (
            <div className="grid-auto">
              {featured.data.slice(0, 8).map((r) => (
                <ListingCard key={r.listingId} r={r} />
              ))}
            </div>
          ) : (
            <div className="card card-pad center muted">New spaces are being added. Check back soon, or list your own.</div>
          )}
        </div>
      </section>

      <section className="section alt">
        <div className="container">
          <div className="section-head">
            <div>
              <span className="eyebrow">How it works</span>
              <h2>From search to parked in three steps</h2>
            </div>
          </div>
          <div className="grid-3">
            {STEPS.map((s) => (
              <div className="feature" key={s.title}>
                <div className="feature-icon">
                  <Icon name={s.icon} size={22} />
                </div>
                <h3>{s.title}</h3>
                <p className="muted">{s.text}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="section">
        <div className="container">
          <div className="host-cta">
            <div className="stack-sm">
              <h2>Your empty space could pay for itself</h2>
              <p>List a driveway, garage or lot in minutes. Set your price and hours, choose instant booking or approve each request, and get paid weekly.</p>
            </div>
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <Link to={a.hasRole('HOST') ? '/host/listings/new' : '/host'} className="btn btn-lg">
                {a.hasRole('HOST') ? 'Add a listing' : 'Start hosting'} <Icon name="arrowRight" size={16} />
              </Link>
            </div>
          </div>
        </div>
      </section>
    </>
  );
}
