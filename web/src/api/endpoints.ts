import { api, tokenStore } from './index';
import type { RawResponse } from './client';
import type {
  AccountSummary,
  AppNotification,
  AvailabilityRule,
  Blackout,
  Booking,
  BookingCase,
  EarningsSummary,
  Listing,
  ListingPhoto,
  ListingSummary,
  ListingTerms,
  ModerationItem,
  NotificationPreferences,
  Page,
  PayoutAccount,
  PaymentIntent,
  Payout,
  PhotoUploadSlot,
  Profile,
  PublicProfile,
  RatingSummary,
  Review,
  ReviewTarget,
  SearchResult,
  TokenResponse,
  Vehicle,
  VehicleType,
} from './types';

// ---------------------------------------------------------------- auth (identity)
export const auth = {
  register: (email: string, password: string, acceptedTermsVersion = '2024-01') =>
    api.post<AccountSummary>('/api/v1/auth/register', { email, password, acceptedTermsVersion }, { auth: false }),
  async login(email: string, password: string): Promise<TokenResponse> {
    const t = await api.post<TokenResponse>('/api/v1/auth/login', { email, password }, { auth: false });
    tokenStore.set({ accessToken: t.accessToken, refreshToken: t.refreshToken });
    return t;
  },
  async logout(): Promise<void> {
    const t = tokenStore.get();
    try {
      if (t) await api.post('/api/v1/auth/logout', { refreshToken: t.refreshToken });
    } catch {
      /* the local session ends either way */
    }
    tokenStore.set(null);
  },
  me: () => api.get<AccountSummary>('/api/v1/accounts/me'),
  requestEmailCode: () => api.post<void>('/api/v1/auth/email/verification-requests'),
  verifyEmail: (code: string) => api.post<void>('/api/v1/auth/email/verify', { code }),
  requestPhoneCode: (phone: string) => api.post<void>('/api/v1/auth/phone/verification-requests', { phone }),
  verifyPhone: (code: string) => api.post<void>('/api/v1/auth/phone/verify', { code }),
  forgot: (email: string) => api.post<void>('/api/v1/auth/password/forgot', { email }, { auth: false }),
  reset: (token: string, newPassword: string) =>
    api.post<void>('/api/v1/auth/password/reset', { token, newPassword }, { auth: false }),
  becomeHost: () => api.post<AccountSummary>('/api/v1/accounts/me/roles/host'),
  /** Forces a refresh-token rotation (used after becoming host so the JWT carries HOST). */
  refreshNow: () => api.refresh(),
};

// ---------------------------------------------------------------- profile
export const profile = {
  me: () => api.get<Profile>('/api/v1/profiles/me'),
  update: (body: { displayName: string; bio?: string | null; city?: string | null }) =>
    api.put<Profile>('/api/v1/profiles/me', body),
  publicOf: (id: string) => api.get<PublicProfile>(`/api/v1/profiles/${id}`),
  photoSlot: (contentType: string, contentLength: number) =>
    api.post<PhotoUploadSlot>('/api/v1/profiles/me/photo/upload-urls', { contentType, contentLength }),
  confirmPhoto: (uploadId: string) => api.post<Profile>(`/api/v1/profiles/me/photo/uploads/${uploadId}/confirm`),
  deletePhoto: () => api.delete<void>('/api/v1/profiles/me/photo'),
  vehicles: () => api.get<{ items: Vehicle[] }>('/api/v1/vehicles'),
  addVehicle: (b: {
    licensePlate: string;
    plateRegion: string;
    vehicleType: VehicleType;
    make?: string | null;
    model?: string | null;
    color?: string | null;
  }) => api.post<Vehicle>('/api/v1/vehicles', b),
  updateVehicle: (id: string, b: { vehicleType: VehicleType; make?: string | null; model?: string | null; color?: string | null }) =>
    api.put<Vehicle>(`/api/v1/vehicles/${id}`, b),
  removeVehicle: (id: string) => api.delete<void>(`/api/v1/vehicles/${id}`),
  makePrimary: (id: string) => api.post<Vehicle>(`/api/v1/vehicles/${id}/primary`),
  payoutAccounts: () => api.get<{ items: PayoutAccount[] }>('/api/v1/profiles/me/payout-accounts'),
  addPayoutAccount: (processorType: string, processorToken: string) =>
    api.post<PayoutAccount>('/api/v1/profiles/me/payout-accounts', { processorType, processorToken }),
  deletePayoutAccount: (id: string) => api.delete<void>(`/api/v1/profiles/me/payout-accounts/${id}`),
  prefs: () => api.get<NotificationPreferences>('/api/v1/notification-preferences'),
  setPrefs: (b: NotificationPreferences) => api.put<NotificationPreferences>('/api/v1/notification-preferences', b),
};

// ---------------------------------------------------------------- storage upload (presigned PUT)
/**
 * Presigned URLs point at object storage on localhost:9000, which sends no CORS headers for the dev
 * origin. In dev, the Vite proxy forwards /_storage/* to it (changeOrigin keeps the signed Host).
 */
export function storageUrl(uploadUrl: string): string {
  try {
    const u = new URL(uploadUrl);
    if (['localhost', '127.0.0.1'].includes(u.hostname) && u.port === '9000') {
      return `/_storage${u.pathname}${u.search}`;
    }
  } catch {
    /* fall through */
  }
  return uploadUrl;
}
export async function putToStorage(slot: PhotoUploadSlot, file: File): Promise<{ url: string; status: number }> {
  const url = storageUrl(slot.uploadUrl);
  const headers: Record<string, string> = {};
  for (const [k, v] of Object.entries(slot.requiredHeaders)) {
    if (!['content-length', 'host'].includes(k.toLowerCase())) headers[k] = v;
  }
  const res = await fetch(url, { method: 'PUT', headers, body: file });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Object storage PUT failed: HTTP ${res.status} ${text.slice(0, 300)}`);
  }
  return { url, status: res.status };
}

// ---------------------------------------------------------------- listings
export const listings = {
  create: (b: ListingTerms) => api.raw<Listing>('POST', '/api/v1/listings', { body: b }),
  get: (id: string, authed = true) => api.raw<Listing>('GET', `/api/v1/listings/${id}`, { auth: authed }),
  /** If-Match carries the ETag from the last GET (opaque; it can jump by 2 per save). */
  update: (id: string, etag: string, b: Partial<ListingTerms>): Promise<RawResponse<Listing>> =>
    api.raw<Listing>('PATCH', `/api/v1/listings/${id}`, { body: b, headers: { 'If-Match': etag } }),
  archive: (id: string) => api.delete<void>(`/api/v1/listings/${id}`),
  mine: (status?: string, cursor?: string) =>
    api.get<Page<ListingSummary>>('/api/v1/hosts/me/listings', { query: { status, cursor, limit: 50 } }),
  submit: (id: string) => api.post<Listing>(`/api/v1/listings/${id}/submit`),
  pause: (id: string) => api.post<Listing>(`/api/v1/listings/${id}/pause`),
  resume: (id: string) => api.post<Listing>(`/api/v1/listings/${id}/resume`),
  photoSlot: (id: string, contentType: string, contentLength: number) =>
    api.post<PhotoUploadSlot>(`/api/v1/listings/${id}/photos/upload-urls`, { contentType, contentLength }),
  confirmPhoto: (id: string, photoId: string) => api.post<ListingPhoto>(`/api/v1/listings/${id}/photos/${photoId}/confirm`),
  deletePhoto: (id: string, photoId: string) => api.delete<void>(`/api/v1/listings/${id}/photos/${photoId}`),
  setAvailability: (id: string, rules: AvailabilityRule[]) =>
    api.put<Listing>(`/api/v1/listings/${id}/availability`, { rules }),
  addBlackout: (id: string, b: { start: string; end: string; reason?: string }) =>
    api.post<Blackout>(`/api/v1/listings/${id}/blackouts`, b),
  deleteBlackout: (id: string, blackoutId: string) => api.delete<void>(`/api/v1/listings/${id}/blackouts/${blackoutId}`),
};

// ---------------------------------------------------------------- search
export interface SearchParams {
  lat: number;
  lng: number;
  radiusKm?: number;
  startTime?: string;
  endTime?: string;
  vehicleType?: string;
  maxPricePerHour?: string;
  amenities?: string;
  instantBook?: boolean;
  sort?: 'distance' | 'price';
  cursor?: string;
  limit?: number;
}
export const search = {
  listings: (p: SearchParams) =>
    api.get<Page<SearchResult>>('/api/v1/search/listings', {
      auth: false,
      query: { ...p } as Record<string, string | number | boolean | undefined>,
    }),
  one: (id: string) => api.get<SearchResult>(`/api/v1/search/listings/${id}`, { auth: false }),
};

// ---------------------------------------------------------------- bookings and payments
export const bookings = {
  /** The Idempotency-Key is chosen by the caller so a double-click or retry replays, not duplicates. */
  create: (b: { listingId: string; vehicleId?: string | null; startTime: string; endTime: string }, key: string) =>
    api.raw<Booking>('POST', '/api/v1/bookings', { body: b, idempotency: key }),
  list: (role: 'driver' | 'host', when: 'upcoming' | 'past' | 'all' = 'all', status?: string, cursor?: string) =>
    api.get<Page<Booking>>('/api/v1/bookings', { query: { role, when, status, cursor, limit: 50 } }),
  get: (id: string) => api.get<Booking>(`/api/v1/bookings/${id}`),
  cancel: (id: string, reason: string) => api.post<Booking>(`/api/v1/bookings/${id}/cancel`, { reason }),
  approve: (id: string) => api.post<Booking>(`/api/v1/bookings/${id}/approve`),
  decline: (id: string, reason: string) => api.post<Booking>(`/api/v1/bookings/${id}/decline`, { reason }),
  checkIn: (id: string) => api.post<Booking>(`/api/v1/bookings/${id}/check-in`),
  checkOut: (id: string) => api.post<Booking>(`/api/v1/bookings/${id}/check-out`),
};
export const payments = {
  intentFor: (bookingId: string) => api.get<PaymentIntent>('/api/v1/payments/intents', { query: { bookingId } }),
  authorize: (intentId: string, paymentMethodToken: string, key: string) =>
    api.raw<PaymentIntent>('POST', `/api/v1/payments/intents/${intentId}/authorize`, {
      body: { paymentMethodToken },
      idempotency: key,
    }),
  payouts: (cursor?: string) => api.get<Page<Payout>>('/api/v1/hosts/me/payouts', { query: { cursor, limit: 50 } }),
  earnings: () => api.get<EarningsSummary>('/api/v1/hosts/me/earnings-summary'),
};

// ---------------------------------------------------------------- reviews
export const reviews = {
  submit: (bookingId: string, rating: number, comment?: string) =>
    api.post<Review>('/api/v1/reviews', { bookingId, rating, comment: comment || null }),
  list: (targetType: ReviewTarget, targetId: string, cursor?: string, authed = true) =>
    api.get<Page<Review>>('/api/v1/reviews', { query: { targetType, targetId, cursor, limit: 20 }, auth: authed }),
  summary: (targetType: ReviewTarget, targetId: string, authed = true) =>
    api.get<RatingSummary>('/api/v1/reviews/summary', { query: { targetType, targetId }, auth: authed }),
};

// ---------------------------------------------------------------- notifications
export const notifications = {
  list: (cursor?: string) => api.get<Page<AppNotification>>('/api/v1/notifications', { query: { cursor, limit: 30 } }),
  unread: () => api.get<{ unread: number }>('/api/v1/notifications/unread-count'),
  read: (id: string) => api.post<void>(`/api/v1/notifications/${id}/read`),
  readAll: () => api.post<{ marked: number }>('/api/v1/notifications/read-all'),
};

// ---------------------------------------------------------------- admin
export const admin = {
  queue: (cursor?: string) => api.get<Page<ModerationItem>>('/admin/v1/moderation/listings', { query: { cursor, limit: 20 } }),
  approve: (id: string, reason?: string) =>
    api.post<{ listingId: string; status: string }>(`/admin/v1/moderation/listings/${id}/approve`, reason ? { reason } : {}),
  reject: (id: string, reason: string) =>
    api.post<{ listingId: string; status: string; rejectionReason?: string }>(`/admin/v1/moderation/listings/${id}/reject`, { reason }),
  hideReview: (id: string, reason: string) =>
    api.post<{ reviewId: string; hidden: boolean }>(`/admin/v1/moderation/reviews/${id}/hide`, { reason }),
  bookingCase: (id: string) => api.get<BookingCase>(`/admin/v1/bookings/${id}`),
  refund: (bookingId: string, amount: string, reason: string, key: string) =>
    api.raw<NonNullable<BookingCase['payment']>>('POST', `/admin/v1/bookings/${bookingId}/refunds`, {
      body: { amount, reason },
      idempotency: key,
    }),
};

// ---------------------------------------------------------------- dev helper (Vite middleware, local only)
export interface DevCode {
  at: string;
  channel: string;
  to: string;
  purpose: string;
  code: string;
}
export async function fetchDevCodes(): Promise<{ available: boolean; file: string; items: DevCode[]; error?: string }> {
  const res = await fetch('/__dev/codes', { cache: 'no-store' });
  if (!res.ok) return { available: false, file: 'logs/notification.log', items: [], error: `HTTP ${res.status}` };
  try {
    return await res.json();
  } catch {
    return { available: false, file: 'logs/notification.log', items: [], error: 'not served (production build?)' };
  }
}
