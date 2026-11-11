import { useState, type FormEvent } from 'react';
import { AMENITIES, POLICIES, VEHICLE_TYPES, type Amenity, type CancellationPolicy, type ListingTerms, type VehicleType } from '../api/types';
import { Field, ProblemView } from './ui';
import { LeafletMap } from './LeafletMap';

export const POLICY_TEXT: Record<CancellationPolicy, string> = {
  FLEXIBLE: 'FLEXIBLE: 100% refund if cancelled 1 hour or more before start, else 0%.',
  MODERATE: 'MODERATE: 100% if 24 hours or more before start, 50% if 1 hour or more, else 0%.',
  STRICT: 'STRICT: 50% if 24 hours or more before start, else 0%.',
};

export const DEFAULT_TERMS: ListingTerms = {
  title: '',
  description: '',
  address: '',
  location: { lat: 12.9716, lng: 77.5946 },
  timeZone: 'Asia/Kolkata',
  pricePerHour: '50.00',
  currency: 'INR',
  maxVehicleType: 'SUV',
  amenities: ['COVERED'],
  instantBook: true,
  cancellationPolicy: 'MODERATE',
  bufferMinutes: 0,
};

export function ListingForm({
  initial,
  submitLabel,
  busy,
  error,
  disabled,
  onSubmit,
}: {
  initial: ListingTerms;
  submitLabel: string;
  busy: boolean;
  error: unknown;
  disabled?: boolean;
  onSubmit: (terms: ListingTerms) => void;
}) {
  const [t, setT] = useState<ListingTerms>(initial);
  const set = <K extends keyof ListingTerms>(k: K, v: ListingTerms[K]) => setT((cur) => ({ ...cur, [k]: v }));
  const toggle = (a: Amenity) => set('amenities', t.amenities.includes(a) ? t.amenities.filter((x) => x !== a) : [...t.amenities, a]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    // Only the editable terms go on the wire (the initial object may be a whole Listing).
    onSubmit({
      title: t.title,
      description: t.description ?? '',
      address: t.address,
      location: { lat: t.location.lat, lng: t.location.lng },
      timeZone: t.timeZone,
      pricePerHour: t.pricePerHour,
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
      <fieldset disabled={disabled || busy}>
        <div className="grid2">
          <div className="form">
            <Field label="Title" hint="5-255 characters">
              <input value={t.title} onChange={(e) => set('title', e.target.value)} required minLength={5} maxLength={255} />
            </Field>
            <Field label="Description">
              <textarea value={t.description ?? ''} onChange={(e) => set('description', e.target.value)} maxLength={5000} rows={3} />
            </Field>
            <Field label="Address" hint="5-512 characters. The public listing shows the exact address.">
              <input value={t.address} onChange={(e) => set('address', e.target.value)} required minLength={5} maxLength={512} />
            </Field>
            <div className="row">
              <Field label="Latitude">
                <input
                  type="number"
                  step="any"
                  min={-90}
                  max={90}
                  value={t.location.lat}
                  onChange={(e) => set('location', { ...t.location, lat: Number(e.target.value) })}
                  required
                />
              </Field>
              <Field label="Longitude">
                <input
                  type="number"
                  step="any"
                  min={-180}
                  max={180}
                  value={t.location.lng}
                  onChange={(e) => set('location', { ...t.location, lng: Number(e.target.value) })}
                  required
                />
              </Field>
            </div>
          </div>
          <div>
            <span className="field-label">Click the map to set the location (changing it later sends a published listing back to review)</span>
            <LeafletMap
              center={t.location}
              pick={t.location}
              onPick={(lat, lng) => set('location', { lat, lng })}
              height={260}
            />
          </div>
        </div>

        <div className="row">
          <Field label="Price per hour (INR)" hint="5.00 - 2000.00">
            <input value={t.pricePerHour} onChange={(e) => set('pricePerHour', e.target.value)} required pattern="\d{1,8}(\.\d{1,2})?" />
          </Field>
          <Field label="Max vehicle type" hint="Admits this size and smaller">
            <select value={t.maxVehicleType} onChange={(e) => set('maxVehicleType', e.target.value as VehicleType)}>
              {VEHICLE_TYPES.map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </Field>
          <Field label="Time zone (IANA)" hint="Availability rules are in this zone">
            <input value={t.timeZone} onChange={(e) => set('timeZone', e.target.value)} required maxLength={64} />
          </Field>
          <Field label="Buffer minutes" hint="0-120, multiples of 5, blocked after each booking">
            <input type="number" min={0} max={120} step={5} value={t.bufferMinutes} onChange={(e) => set('bufferMinutes', Number(e.target.value))} />
          </Field>
        </div>

        <div className="row">
          <Field label="Booking mode">
            <select value={t.instantBook ? 'instant' : 'request'} onChange={(e) => set('instantBook', e.target.value === 'instant')}>
              <option value="instant">Instant book (confirmed right after payment)</option>
              <option value="request">Request to book (host approves each booking)</option>
            </select>
          </Field>
          <Field label="Cancellation policy" hint={POLICY_TEXT[t.cancellationPolicy]}>
            <select value={t.cancellationPolicy} onChange={(e) => set('cancellationPolicy', e.target.value as CancellationPolicy)}>
              {POLICIES.map((p) => (
                <option key={p}>{p}</option>
              ))}
            </select>
          </Field>
        </div>

        <div className="field">
          <span className="field-label">Amenities (max 8)</span>
          <div className="row wrap">
            {AMENITIES.map((a) => (
              <label key={a} className="check">
                <input type="checkbox" checked={t.amenities.includes(a)} onChange={() => toggle(a)} /> {a}
              </label>
            ))}
          </div>
        </div>
      </fieldset>
      <ProblemView error={error} />
      <button type="submit" disabled={disabled || busy}>
        {submitLabel}
      </button>
    </form>
  );
}
