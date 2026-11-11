import type { CancellationPolicy } from './api/types';

const MIN = 60_000;
const QUARTER = 15 * MIN;

/**
 * The soonest booking window Booking accepts: start on a 15-minute boundary at least 15 minutes
 * from now (plus a safety margin for request latency), 30 minutes long (the minimum duration).
 * Check-in opens 15 minutes before start, so the wait before check-in is at most ~15 minutes.
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
    explanation = hours >= 1 ? 'FLEXIBLE, 1 hour or more before start: 100%' : 'FLEXIBLE, less than 1 hour before start: 0%';
  } else if (policy === 'MODERATE') {
    percent = hours >= 24 ? 100 : hours >= 1 ? 50 : 0;
    explanation = hours >= 24 ? 'MODERATE, 24 hours or more before start: 100%' : hours >= 1 ? 'MODERATE, 1 to 24 hours before start: 50%' : 'MODERATE, less than 1 hour before start: 0%';
  } else {
    percent = hours >= 24 ? 50 : 0;
    explanation = hours >= 24 ? 'STRICT, 24 hours or more before start: 50%' : 'STRICT, less than 24 hours before start: 0%';
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
  const m = Math.floor(s / 60);
  return `${m}m ${String(s % 60).padStart(2, '0')}s`;
}
