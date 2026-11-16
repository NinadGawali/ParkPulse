import type { CancellationPolicy } from './api/types';

const MIN = 60_000;
const QUARTER = 15 * MIN;

/**
 * The soonest booking window Booking accepts: start on a 15-minute boundary at least 15 minutes
 * from now (plus a safety margin for request latency), `durationMin` long (30 minutes minimum).
 * Check-in opens 15 minutes before start.
 */
export function soonestSlot(now: number, durationMin = 30, marginMin = 2): { start: Date; end: Date; checkInOpens: Date } {
  const earliest = now + 15 * MIN + marginMin * MIN;
  const start = Math.ceil(earliest / QUARTER) * QUARTER;
  return {
    start: new Date(start),
    end: new Date(start + durationMin * MIN),
    checkInOpens: new Date(start - 15 * MIN),
  };
}

/** Earliest bookable start (as epoch ms) on a 15-minute boundary. */
export function earliestStart(now: number): number {
  return soonestSlot(now).start.getTime();
}

/** Default search/booking window: soonest start, two hours long. */
export function defaultWindow(now: number): { start: Date; end: Date } {
  const s = soonestSlot(now, 120);
  return { start: s.start, end: s.end };
}

/** Value for <input type="datetime-local"> in the browser's local time. */
export function toLocalInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
/** datetime-local string (local time) to an ISO instant. */
export function fromLocalInput(s: string): string {
  return new Date(s).toISOString();
}
export function isQuarterAligned(iso: string): boolean {
  return new Date(iso).getTime() % QUARTER === 0;
}

/** Local date ("YYYY-MM-DD") and time ("HH:MM") parts of an instant. */
export function splitLocal(d: Date): { date: string; time: string } {
  const [date, time] = toLocalInput(d).split('T');
  return { date, time };
}
/** Joins local date + time parts into an instant (browser time zone). */
export function joinLocal(date: string, time: string): Date {
  return new Date(`${date}T${time}`);
}

/** "00:00", "00:15", ... "23:45". */
export const QUARTER_TIMES: string[] = Array.from({ length: 96 }, (_, i) => {
  const h = Math.floor(i / 4);
  const m = (i % 4) * 15;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
});

/** 12-hour label for "HH:MM" ("14:30" → "2:30 PM"; "24:00" → "Midnight"). */
export function timeLabel(hhmm: string): string {
  if (hhmm === '24:00') return 'Midnight';
  const [h, m] = hhmm.split(':').map(Number);
  const suffix = h < 12 ? 'AM' : 'PM';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}:${String(m).padStart(2, '0')} ${suffix}`;
}

/** Human duration between two instants: "45 min", "2 h 30 min", "3 days 2 h". */
export function durationLabel(startIso: string, endIso: string): string {
  const mins = Math.round((new Date(endIso).getTime() - new Date(startIso).getTime()) / MIN);
  if (mins <= 0) return '0 min';
  const days = Math.floor(mins / 1440);
  const hours = Math.floor((mins % 1440) / 60);
  const rest = mins % 60;
  const parts: string[] = [];
  if (days) parts.push(`${days} day${days > 1 ? 's' : ''}`);
  if (hours) parts.push(`${hours} h`);
  if (rest) parts.push(`${rest} min`);
  return parts.join(' ');
}

export type WindowProblem = null | string;
/** Mirrors Booking's own checks so the UI can explain them before the request (the server stays authoritative). */
export function validateWindow(startIso: string, endIso: string, now: number): WindowProblem {
  const s = new Date(startIso).getTime();
  const e = new Date(endIso).getTime();
  if (Number.isNaN(s) || Number.isNaN(e)) return 'Choose when you arrive and leave.';
  if (s % QUARTER !== 0 || e % QUARTER !== 0) return 'Times must be on a 15-minute mark.';
  if (s < now + 15 * MIN) return 'Arrival must be at least 15 minutes from now.';
  if (e - s < 30 * MIN) return 'Book at least 30 minutes.';
  if (e - s > 30 * 1440 * MIN) return 'Bookings can last at most 30 days.';
  if (s > now + 365 * 1440 * MIN) return 'Bookings can start at most a year ahead.';
  return null;
}

export interface RefundTier {
  percent: number;
  amount: string;
  explanation: string;
}
/** Mirrors the policy table in docs/openapi/common.yaml (driver cancellation of a CONFIRMED booking). */
export function refundTier(policy: CancellationPolicy, startIso: string, nowMs: number, total: string): RefundTier {
  const hours = (new Date(startIso).getTime() - nowMs) / (60 * MIN);
  let percent = 0;
  let explanation = '';
  if (policy === 'FLEXIBLE') {
    percent = hours >= 1 ? 100 : 0;
    explanation = hours >= 1 ? 'Flexible policy, 1 hour or more before arrival: full refund.' : 'Flexible policy, less than 1 hour before arrival: no refund.';
  } else if (policy === 'MODERATE') {
    percent = hours >= 24 ? 100 : hours >= 1 ? 50 : 0;
    explanation =
      hours >= 24
        ? 'Moderate policy, 24 hours or more before arrival: full refund.'
        : hours >= 1
          ? 'Moderate policy, 1 to 24 hours before arrival: 50% refund.'
          : 'Moderate policy, less than 1 hour before arrival: no refund.';
  } else {
    percent = hours >= 24 ? 50 : 0;
    explanation = hours >= 24 ? 'Strict policy, 24 hours or more before arrival: 50% refund.' : 'Strict policy, less than 24 hours before arrival: no refund.';
  }
  const cents = Math.round(Number(total) * 100);
  const amount = ((cents * percent) / 100 / 100).toFixed(2);
  return { percent, amount, explanation };
}

/** Hours between two instants times pricePerHour, HALF_UP to 2 dp (display estimate only). */
export function estimateTotal(pricePerHour: string, startIso: string, endIso: string): string {
  const hours = (new Date(endIso).getTime() - new Date(startIso).getTime()) / (60 * MIN);
  return (Math.round(Number(pricePerHour) * hours * 100) / 100).toFixed(2);
}

export function countdown(targetMs: number, nowMs: number): string {
  const s = Math.max(0, Math.round((targetMs - nowMs) / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
  return `${m}:${String(s % 60).padStart(2, '0')}`;
}

/** "just now", "5 min ago", "3 h ago", "2 days ago", then a date. */
export function relativeTime(iso: string, nowMs: number): string {
  const diff = Math.round((nowMs - new Date(iso).getTime()) / MIN);
  if (diff < 1) return 'just now';
  if (diff < 60) return `${diff} min ago`;
  if (diff < 1440) return `${Math.floor(diff / 60)} h ago`;
  if (diff < 7 * 1440) {
    const d = Math.floor(diff / 1440);
    return `${d} day${d > 1 ? 's' : ''} ago`;
  }
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

const inrWhole = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const inrCents = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** "₹1,250" / "₹62.50". Money arrives as a decimal string. */
export function formatMoney(value: string | number | null | undefined, currency = 'INR'): string {
  if (value === null || value === undefined || value === '') return '–';
  const n = Number(value);
  if (Number.isNaN(n)) return String(value);
  if (currency === 'INR') return Math.round(n * 100) % 100 === 0 ? inrWhole.format(n) : inrCents.format(n);
  return `${currency} ${n.toFixed(2)}`;
}

// ------------------------------------------------------------------ test-mode card payments
/** Luhn checksum for card numbers (digits only). */
export function luhnValid(digits: string): boolean {
  if (!/^\d{12,19}$/.test(digits)) return false;
  let sum = 0;
  let dbl = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = Number(digits[i]);
    if (dbl) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    dbl = !dbl;
  }
  return sum % 10 === 0;
}

/**
 * Test cards understood by the FakeProcessor adapter. The card details never leave the browser; only
 * the processor token is sent, exactly as with a real tokenizing processor (Razorpay/Stripe later).
 */
export const TEST_CARDS = [
  { number: '4242 4242 4242 4242', token: 'fake_ok', result: 'Payment succeeds' },
  { number: '4000 0000 0000 0002', token: 'fake_decline', result: 'Card is declined' },
  { number: '4000 0000 0000 0341', token: 'fake_capture_fail', result: 'Authorizes, then capture fails' },
] as const;

export function testCardToken(cardNumber: string): string | null {
  const digits = cardNumber.replace(/\D/g, '');
  const hit = TEST_CARDS.find((c) => c.number.replace(/\s/g, '') === digits);
  return hit ? hit.token : null;
}

export function formatCardNumber(input: string): string {
  return input
    .replace(/\D/g, '')
    .slice(0, 19)
    .replace(/(\d{4})(?=\d)/g, '$1 ');
}

/** "MM/YY" in the future. */
export function expiryValid(mmYY: string, now: Date): boolean {
  const m = /^(\d{2})\s*\/\s*(\d{2})$/.exec(mmYY);
  if (!m) return false;
  const month = Number(m[1]);
  const year = 2000 + Number(m[2]);
  if (month < 1 || month > 12) return false;
  return year > now.getFullYear() || (year === now.getFullYear() && month >= now.getMonth() + 1);
}

/** Google Maps directions link for a point (opens the user's maps app on phones). */
export function directionsUrl(lat: number, lng: number): string {
  return `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`;
}

/** Initials for an avatar fallback. */
export function initials(name?: string | null): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}
