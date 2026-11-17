import type { IconName } from './components/Icon';
import type { Amenity, BookingStatus, CancellationPolicy, ListingStatus, VehicleType } from './api/types';

export const VEHICLE_LABEL: Record<VehicleType, string> = {
  MOTORCYCLE: 'Motorcycle',
  HATCHBACK: 'Hatchback',
  SEDAN: 'Sedan',
  SUV: 'SUV',
  VAN: 'Van',
  TRUCK: 'Truck',
};
/** A listing that admits a size also admits every smaller one. */
export const VEHICLE_FITS: Record<VehicleType, string> = {
  MOTORCYCLE: 'Two-wheelers only',
  HATCHBACK: 'Up to hatchbacks',
  SEDAN: 'Up to sedans',
  SUV: 'Up to SUVs',
  VAN: 'Up to vans',
  TRUCK: 'Any vehicle, incl. trucks',
};

export const AMENITY_META: Record<Amenity, { label: string; icon: IconName }> = {
  COVERED: { label: 'Covered', icon: 'umbrella' },
  GATED: { label: 'Gated', icon: 'gate' },
  CCTV: { label: 'CCTV', icon: 'cctv' },
  EV_CHARGER: { label: 'EV charger', icon: 'plug' },
  SECURITY_GUARD: { label: 'Security guard', icon: 'guard' },
  LIGHTING: { label: 'Well lit', icon: 'bulb' },
  WHEELCHAIR_ACCESSIBLE: { label: 'Wheelchair accessible', icon: 'wheelchair' },
  TWENTY_FOUR_SEVEN_ACCESS: { label: '24/7 access', icon: 'sun' },
};
export function amenityLabel(a: string): string {
  return (AMENITY_META as Record<string, { label: string }>)[a]?.label ?? a.replace(/_/g, ' ').toLowerCase();
}

export const POLICY_META: Record<CancellationPolicy, { label: string; short: string; long: string }> = {
  FLEXIBLE: {
    label: 'Flexible',
    short: 'Full refund up to 1 hour before arrival',
    long: 'Full refund if you cancel at least 1 hour before arrival. No refund after that.',
  },
  MODERATE: {
    label: 'Moderate',
    short: 'Full refund up to 24 hours before',
    long: 'Full refund if you cancel at least 24 hours before arrival, 50% if at least 1 hour before, no refund after that.',
  },
  STRICT: {
    label: 'Strict',
    short: '50% refund up to 24 hours before',
    long: '50% refund if you cancel at least 24 hours before arrival. No refund after that.',
  },
};

type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'brand' | 'muted';

export const BOOKING_STATUS: Record<BookingStatus, { label: string; tone: Tone }> = {
  PENDING_PAYMENT: { label: 'Awaiting payment', tone: 'warn' },
  AWAITING_HOST_APPROVAL: { label: 'Awaiting host approval', tone: 'warn' },
  CONFIRMED: { label: 'Confirmed', tone: 'ok' },
  CHECKED_IN: { label: 'Parked now', tone: 'info' },
  COMPLETED: { label: 'Completed', tone: 'brand' },
  CANCELLED_BY_DRIVER: { label: 'Cancelled', tone: 'muted' },
  CANCELLED_BY_HOST: { label: 'Cancelled by host', tone: 'bad' },
  DECLINED_BY_HOST: { label: 'Declined by host', tone: 'bad' },
  NO_SHOW: { label: 'No-show', tone: 'bad' },
  EXPIRED: { label: 'Expired', tone: 'muted' },
  PAYMENT_FAILED: { label: 'Payment failed', tone: 'bad' },
};

export const LISTING_STATUS: Record<ListingStatus, { label: string; tone: Tone }> = {
  DRAFT: { label: 'Draft', tone: 'muted' },
  PENDING_REVIEW: { label: 'In review', tone: 'warn' },
  PUBLISHED: { label: 'Live', tone: 'ok' },
  PAUSED: { label: 'Paused', tone: 'muted' },
  ARCHIVED: { label: 'Archived', tone: 'muted' },
  REJECTED: { label: 'Changes needed', tone: 'bad' },
};

export const PAYMENT_STATUS: Record<string, { label: string; tone: Tone }> = {
  CREATED: { label: 'Not paid', tone: 'warn' },
  AUTHORIZED: { label: 'Authorized', tone: 'info' },
  CAPTURED: { label: 'Paid', tone: 'ok' },
  FAILED: { label: 'Failed', tone: 'bad' },
  VOIDED: { label: 'Released', tone: 'muted' },
  REFUNDED: { label: 'Refunded', tone: 'info' },
  PARTIALLY_REFUNDED: { label: 'Partly refunded', tone: 'info' },
  PENDING: { label: 'Pending', tone: 'warn' },
  PAID: { label: 'Paid', tone: 'ok' },
  ON_HOLD: { label: 'On hold', tone: 'warn' },
};

export const DAY_LABEL: Record<string, string> = {
  MONDAY: 'Monday',
  TUESDAY: 'Tuesday',
  WEDNESDAY: 'Wednesday',
  THURSDAY: 'Thursday',
  FRIDAY: 'Friday',
  SATURDAY: 'Saturday',
  SUNDAY: 'Sunday',
};

/** Search presets (no third-party geocoder: users pick a city, use their location or move the map). */
export const CITIES = [
  { name: 'Bengaluru', lat: 12.9716, lng: 77.5946 },
  { name: 'Mumbai', lat: 19.076, lng: 72.8777 },
  { name: 'Delhi', lat: 28.6139, lng: 77.209 },
  { name: 'Hyderabad', lat: 17.385, lng: 78.4867 },
  { name: 'Chennai', lat: 13.0827, lng: 80.2707 },
  { name: 'Pune', lat: 18.5204, lng: 73.8567 },
  { name: 'Kolkata', lat: 22.5726, lng: 88.3639 },
] as const;

export const TIME_ZONES = ['Asia/Kolkata', 'Asia/Dubai', 'Asia/Singapore', 'Europe/London', 'UTC'];
