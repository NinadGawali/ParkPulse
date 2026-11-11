// Types mirror docs/openapi/*.yaml. Money is a decimal string.
export type Role = 'DRIVER' | 'HOST' | 'ADMIN';
export type VehicleType = 'MOTORCYCLE' | 'HATCHBACK' | 'SEDAN' | 'SUV' | 'VAN' | 'TRUCK';
export const VEHICLE_TYPES: VehicleType[] = ['MOTORCYCLE', 'HATCHBACK', 'SEDAN', 'SUV', 'VAN', 'TRUCK'];
export type CancellationPolicy = 'FLEXIBLE' | 'MODERATE' | 'STRICT';
export const POLICIES: CancellationPolicy[] = ['FLEXIBLE', 'MODERATE', 'STRICT'];
export const AMENITIES = [
  'COVERED',
  'GATED',
  'CCTV',
  'EV_CHARGER',
  'SECURITY_GUARD',
  'LIGHTING',
  'WHEELCHAIR_ACCESSIBLE',
  'TWENTY_FOUR_SEVEN_ACCESS',
] as const;
export type Amenity = (typeof AMENITIES)[number];
export const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;
export type Day = (typeof DAYS)[number];

export interface FieldError {
  field: string;
  message: string;
}
/** RFC 7807 problem as returned by every service. */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  correlationId?: string;
  errors?: FieldError[];
  [extra: string]: unknown;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}
export interface AccountSummary {
  id: string;
  email: string;
  phone?: string | null;
  emailVerified: boolean;
  phoneVerified: boolean;
  status: 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED' | 'DELETED';
  roles: Role[];
  createdAt: string;
}

export interface Profile {
  accountId: string;
  displayName?: string | null;
  bio?: string | null;
  city?: string | null;
  photoUrl?: string | null;
  hasPhoto: boolean;
  ratingAvg?: string | null;
  ratingCount: number;
  updatedAt: string;
}
export interface PublicProfile {
  accountId: string;
  displayName?: string | null;
  photoUrl?: string | null;
  ratingAvg?: string | null;
  ratingCount: number;
}
export interface PhotoUploadSlot {
  uploadId?: string;
  photoId?: string;
  uploadUrl: string;
  method: 'PUT';
  requiredHeaders: Record<string, string>;
  expiresAt: string;
}
export interface Vehicle {
  id: string;
  licensePlate: string;
  plateRegion: string;
  vehicleType: VehicleType;
  make?: string | null;
  model?: string | null;
  color?: string | null;
  primary: boolean;
  createdAt: string;
}
export type ProcessorType = 'STRIPE_CONNECT' | 'RAZORPAY_ROUTE' | 'FAKE';
export interface PayoutAccount {
  id: string;
  processorType: ProcessorType;
  tokenHint: string;
  verified: boolean;
  createdAt: string;
}
export type Channel = 'EMAIL' | 'SMS' | 'PUSH' | 'IN_APP';
export interface NotificationPreferences {
  preferences: { channel: Channel; enabled: boolean }[];
}

export type ListingStatus = 'DRAFT' | 'PENDING_REVIEW' | 'PUBLISHED' | 'PAUSED' | 'ARCHIVED' | 'REJECTED';
export interface GeoPoint {
  lat: number;
  lng: number;
}
export interface ListingPhoto {
  id: string;
  url: string | null;
  sortOrder: number;
  status: 'PENDING_UPLOAD' | 'READY';
}
export interface AvailabilityRule {
  dayOfWeek: Day;
  startTime: string;
  endTime: string;
}
export interface Blackout {
  id: string;
  start: string;
  end: string;
  reason?: string | null;
}
export interface ListingTerms {
  title: string;
  description?: string;
  address: string;
  location: GeoPoint;
  timeZone: string;
  pricePerHour: string;
  currency: 'INR';
  maxVehicleType: VehicleType;
  amenities: Amenity[];
  instantBook: boolean;
  cancellationPolicy: CancellationPolicy;
  bufferMinutes: number;
}
export interface Listing extends ListingTerms {
  id: string;
  hostAccountId: string;
  status: ListingStatus;
  photos: ListingPhoto[];
  availabilityRules: AvailabilityRule[];
  blackouts: Blackout[];
  rejectionReason?: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface ListingSummary {
  id: string;
  title: string;
  status: ListingStatus;
  thumbnailUrl?: string | null;
  pricePerHour: string;
  currency: string;
}
export interface Page<T> {
  items: T[];
  nextCursor?: string | null;
}

export interface SearchResult {
  listingId: string;
  title: string;
  address: string;
  location: GeoPoint;
  distanceKm?: number | null;
  timeZone: string;
  pricePerHour: string;
  currency: string;
  estimatedTotal?: string | null;
  maxVehicleType: VehicleType;
  amenities: string[];
  instantBook: boolean;
  cancellationPolicy: CancellationPolicy;
  bufferMinutes: number;
  thumbnailUrl?: string | null;
  ratingAvg?: string | null;
  ratingCount: number;
  lastUpdatedFromEventAt: string;
}

export type BookingStatus =
  | 'PENDING_PAYMENT'
  | 'AWAITING_HOST_APPROVAL'
  | 'CONFIRMED'
  | 'CHECKED_IN'
  | 'COMPLETED'
  | 'CANCELLED_BY_DRIVER'
  | 'CANCELLED_BY_HOST'
  | 'DECLINED_BY_HOST'
  | 'NO_SHOW'
  | 'EXPIRED'
  | 'PAYMENT_FAILED';
export interface Booking {
  id: string;
  listingId: string;
  driverAccountId: string;
  hostAccountId: string;
  vehicleId?: string | null;
  status: BookingStatus;
  startTime: string;
  endTime: string;
  pricePerHour: string;
  totalAmount: string;
  currency: string;
  instantBook: boolean;
  cancellationPolicy: CancellationPolicy;
  bufferMinutes: number;
  paymentDueBy?: string | null;
  hostDecisionDueBy?: string | null;
  refundAmount?: string | null;
  createdAt: string;
  updatedAt: string;
}

export type PaymentStatus = 'CREATED' | 'AUTHORIZED' | 'CAPTURED' | 'FAILED' | 'VOIDED' | 'REFUNDED' | 'PARTIALLY_REFUNDED';
export interface PaymentIntent {
  id: string;
  bookingId: string;
  amount: string;
  currency: string;
  status: PaymentStatus;
  failureCode?: string | null;
  refundedAmount: string;
  createdAt: string;
  updatedAt: string;
}
export interface Payout {
  id: string;
  amount: string;
  currency: string;
  periodStart: string;
  periodEnd: string;
  status: 'PENDING' | 'PAID' | 'FAILED' | 'ON_HOLD';
  createdAt: string;
}
export interface EarningsSummary {
  currency: string;
  totalEarned: string;
  totalPaidOut: string;
  pendingPayout: string;
}

export type ReviewTarget = 'LISTING' | 'DRIVER';
export interface Review {
  id: string;
  bookingId: string;
  direction: 'DRIVER_TO_LISTING' | 'HOST_TO_DRIVER';
  targetType: ReviewTarget;
  targetId: string;
  rating: number;
  comment?: string | null;
  createdAt: string;
}
export interface RatingSummary {
  targetType: ReviewTarget;
  targetId: string;
  avgRating?: string | null;
  reviewCount: number;
}

export interface AppNotification {
  id: string;
  type: string;
  title: string;
  body: string;
  createdAt: string;
  readAt?: string | null;
}

export interface PartySummary {
  accountId: string;
  displayName?: string | null;
  photoUrl?: string | null;
  ratingAvg?: string | null;
  ratingCount: number;
}
export interface ModerationItem {
  listingId: string;
  title: string;
  status: ListingStatus;
  pricePerHour: string;
  currency: string;
  thumbnailUrl?: string | null;
  hostAccountId?: string | null;
  address?: string | null;
  photoCount?: number | null;
  availabilityRuleCount?: number | null;
  lastChangedAt?: string | null;
  host?: PartySummary | null;
  partial: boolean;
}
export interface BookingCase {
  booking: {
    bookingId: string;
    listingId: string;
    driverAccountId: string;
    hostAccountId: string;
    status: string;
    startTime: string;
    endTime: string;
    pricePerHour?: string | null;
    totalAmount: string;
    currency: string;
    cancellationPolicy: CancellationPolicy;
    refundAmount?: string | null;
    createdAt: string;
    updatedAt: string;
  };
  payment?: {
    intentId: string;
    bookingId: string;
    amount: string;
    currency: string;
    status: PaymentStatus;
    failureCode?: string | null;
    refundedAmount: string;
    updatedAt: string;
  } | null;
  listing?: { listingId: string; title: string; status: ListingStatus; address?: string | null } | null;
  driver?: PartySummary | null;
  host?: PartySummary | null;
  partial: boolean;
}
