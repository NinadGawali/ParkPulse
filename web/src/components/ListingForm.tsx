import { useState, type FormEvent } from 'react';
import { AMENITIES, POLICIES, VEHICLE_TYPES, type Amenity, type ListingTerms } from '../api/types';
import { AMENITY_META, CITIES, POLICY_META, TIME_ZONES, VEHICLE_FITS } from '../labels';
import { Icon } from './Icon';
import { LeafletMap } from './LeafletMap';
import { Button, Field, ProblemView } from './ui';

export const DEFAULT_TERMS: ListingTerms = {
  title: '',
  description: '',
  address: '',
  location: { lat: CITIES[0].lat, lng: CITIES[0].lng },
  timeZone: 'Asia/Kolkata',
  pricePerHour: '50.00',
  currency: 'INR',
  maxVehicleType: 'SEDAN',
  amenities: [],
  instantBook: true,
  cancellationPolicy: 'MODERATE',
  bufferMinutes: 0,
};

const BUFFERS = [0, 5, 10, 15, 30, 45, 60, 90, 120];

/** Normalises "50" / "50.5" to the API's decimal format ("50.00"). */
function money(s: string): string {
  const n = Number(s);
  return Number.isFinite(n) ? n.toFixed(2) : s;
}

export function ListingForm({
  initial,
  submitLabel,
  busy,
  error,
  disabled,
  locationNote,
  onSubmit,
}: {
  initial: ListingTerms;
  submitLabel: string;
  busy: boolean;
  error: unknown;
  disabled?: boolean;
  locationNote?: string;
  onSubmit: (terms: ListingTerms) => void;
}) {
  const [t, setT] = useState<ListingTerms>({ ...initial, pricePerHour: String(Number(initial.pricePerHour)) });
  const set = <K extends keyof ListingTerms>(k: K, v: ListingTerms[K]) => setT((cur) => ({ ...cur, [k]: v }));
  const toggle = (a: Amenity) => set('amenities', t.amenities.includes(a) ? t.amenities.filter((x) => x !== a) : [...t.amenities, a]);
  const price = Number(t.pricePerHour);
  const priceError = !Number.isFinite(price) || price < 5 || price > 2000 ? 'Between ₹5 and ₹2,000 per hour.' : null;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (priceError) return;
    // Only the editable terms go on the wire (the initial object may be a whole Listing).
    onSubmit({
      title: t.title.trim(),
      description: (t.description ?? '').trim(),
      address: t.address.trim(),
      location: { lat: t.location.lat, lng: t.location.lng },
      timeZone: t.timeZone,
      pricePerHour: money(t.pricePerHour),
      currency: 'INR',
      maxVehicleType: t.maxVehicleType,
      amenities: t.amenities,
      instantBook: t.instantBook,
      cancellationPolicy: t.cancellationPolicy,
      bufferMinutes: t.bufferMinutes,
    });
  };

  return (
    <form className="form" onSubmit={submit}>
      <fieldset disabled={disabled || busy} className="stack-lg">
        <section className="form-section">
          <h3>The basics</h3>
          <Field label="Title" hint="What drivers see first, e.g. “Covered garage next to Indiranagar metro”.">
            <input value={t.title} onChange={(e) => set('title', e.target.value)} required minLength={5} maxLength={255} />
          </Field>
          <Field label="Description" hint="Access instructions, height limits, landmarks. Shared publicly.">
            <textarea value={t.description ?? ''} onChange={(e) => set('description', e.target.value)} maxLength={5000} rows={4} />
          </Field>
        </section>

        <section className="form-section">
          <h3>Location</h3>
          <Field label="Street address" hint="Shown on your listing so drivers can find the space.">
            <input value={t.address} onChange={(e) => set('address', e.target.value)} required minLength={5} maxLength={512} autoComplete="street-address" />
          </Field>
          <div className="stack-sm">
            <div className="row between">
              <span className="field-label">Pin the exact spot</span>
              <div className="row">
                <select
                  aria-label="Jump to city"
                  value=""
                  onChange={(e) => {
                    const c = CITIES.find((x) => x.name === e.target.value);
                    if (c) set('location', { lat: c.lat, lng: c.lng });
                  }}
                  style={{ width: 'auto' }}
                >
                  <option value="">Jump to city…</option>
                  {CITIES.map((c) => (
                    <option key={c.name}>{c.name}</option>
                  ))}
                </select>
                <Button
                  size="sm"
                  icon="locate"
                  onClick={() =>
                    navigator.geolocation?.getCurrentPosition((p) => set('location', { lat: Number(p.coords.latitude.toFixed(6)), lng: Number(p.coords.longitude.toFixed(6)) }))
                  }
                >
                  My location
                </Button>
              </div>
            </div>
            <LeafletMap center={t.location} pick={t.location} onPick={(lat, lng) => set('location', { lat, lng })} height={300} zoom={15} />
            <span className="field-hint">
              Click the map or drag the pin. {t.location.lat.toFixed(5)}, {t.location.lng.toFixed(5)}
              {locationNote ? ` · ${locationNote}` : ''}
            </span>
          </div>
        </section>

        <section className="form-section">
          <h3>Price and size</h3>
          <div className="grid-2">
            <Field label="Price per hour" error={priceError} id="listing-price">
              <div className="input-group">
                <span className="addon">₹</span>
                <input id="listing-price" value={t.pricePerHour} onChange={(e) => set('pricePerHour', e.target.value.replace(/[^\d.]/g, ''))} inputMode="decimal" required />
              </div>
            </Field>
            <Field label="Largest vehicle that fits" hint="Smaller vehicles fit too.">
              <select value={t.maxVehicleType} onChange={(e) => set('maxVehicleType', e.target.value as ListingTerms['maxVehicleType'])}>
                {VEHICLE_TYPES.map((v) => (
                  <option key={v} value={v}>
                    {VEHICLE_FITS[v]}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <p className="small muted">ParkSmart keeps a 15% service fee; you earn {Number.isFinite(price) ? `₹${(price * 0.85).toFixed(2)}` : '–'} per booked hour.</p>
        </section>

        <section className="form-section">
          <h3>How drivers book</h3>
          <div className="choice-grid">
            <button type="button" className={`choice ${t.instantBook ? 'on' : ''}`} onClick={() => set('instantBook', true)}>
              <Icon name="bolt" />
              <span>
                <span className="choice-title">Instant book</span>
                <br />
                <span className="choice-text">Confirmed as soon as the driver pays. Most popular with drivers.</span>
              </span>
            </button>
            <button type="button" className={`choice ${!t.instantBook ? 'on' : ''}`} onClick={() => set('instantBook', false)}>
              <Icon name="checkCircle" />
              <span>
                <span className="choice-title">Approve each request</span>
                <br />
                <span className="choice-text">You accept or decline within 24 hours (or before arrival).</span>
              </span>
            </button>
          </div>
          <span className="field-label">Cancellation policy</span>
          <div className="choice-grid">
            {POLICIES.map((p) => (
              <button key={p} type="button" className={`choice ${t.cancellationPolicy === p ? 'on' : ''}`} onClick={() => set('cancellationPolicy', p)}>
                <span>
                  <span className="choice-title">{POLICY_META[p].label}</span>
                  <br />
                  <span className="choice-text">{POLICY_META[p].long}</span>
                </span>
              </button>
            ))}
          </div>
          <div className="grid-2">
            <Field label="Gap between bookings" hint="Blocked after each booking, e.g. to move your own car.">
              <select value={t.bufferMinutes} onChange={(e) => set('bufferMinutes', Number(e.target.value))}>
                {BUFFERS.map((b) => (
                  <option key={b} value={b}>
                    {b === 0 ? 'No gap' : `${b} minutes`}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Time zone" hint="Your opening hours are in this time zone.">
              <select value={t.timeZone} onChange={(e) => set('timeZone', e.target.value)}>
                {Array.from(new Set([t.timeZone, ...TIME_ZONES])).map((z) => (
                  <option key={z}>{z}</option>
                ))}
              </select>
            </Field>
          </div>
        </section>

        <section className="form-section">
          <h3>Amenities</h3>
          <div className="chips">
            {AMENITIES.map((a) => (
              <button key={a} type="button" className={`chip ${t.amenities.includes(a) ? 'on' : ''}`} onClick={() => toggle(a)} aria-pressed={t.amenities.includes(a)}>
                <Icon name={AMENITY_META[a].icon} size={15} /> {AMENITY_META[a].label}
              </button>
            ))}
          </div>
        </section>
      </fieldset>
      <ProblemView error={error} />
      <div className="form-actions">
        <Button type="submit" variant="primary" size="lg" busy={busy} disabled={disabled}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
