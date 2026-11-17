#!/usr/bin/env node
// End-to-end check of the console's assumptions THROUGH THE VITE DEV PROXY (default http://localhost:5173),
// against the live local stack: register -> verify -> host -> listing -> approve -> search -> book -> pay
// -> notifications -> admin case/refund. With --complete it also waits for the check-in window and runs
// check-in -> check-out -> both reviews (takes up to ~17 minutes: Booking allows check-in 15 min before start).
//
// Start the dev server first, ideally `npm run dev:direct-auth` (the gateway limits login/register per IP).
// Needs Git Bash + docker (for web/scripts/make-admin.sh). Local development only.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = process.env.CONSOLE_URL ?? 'http://localhost:5173';
const COMPLETE = process.argv.includes('--complete');
const here = path.dirname(fileURLToPath(import.meta.url));
const run = Date.now();
const PASSWORD = `E2e-Test-${run}1`;
let pass = 0;
let fail = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ok = (m) => (pass++, console.log(`  PASS  ${m}`));
const bad = (m) => (fail++, console.log(`  FAIL  ${m}`));
const step = (m) => console.log(`\n== ${m}`);

async function call(method, url, { token, body, headers = {}, raw } = {}) {
  const h = { Accept: 'application/json', ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const res = await fetch(raw ? url : BASE + url, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  return { status: res.status, json, text, headers: res.headers };
}
function expect(label, r, ...codes) {
  if (codes.includes(r.status)) {
    ok(`${label} (${r.status})`);
    return true;
  }
  bad(`${label}: got ${r.status}, wanted ${codes.join('/')}: ${r.text.slice(0, 300)}`);
  return false;
}
async function devCodes() {
  const r = await call('GET', '/__dev/codes');
  return r.json?.items ?? [];
}
async function newCode(purpose, before) {
  for (let i = 0; i < 30; i++) {
    const items = await devCodes();
    const fresh = items.filter((c) => c.purpose === purpose && !before.has(c.at + c.code));
    if (fresh.length) return fresh[0].code;
    await sleep(1000);
  }
  throw new Error(`no ${purpose} code appeared in the notification log`);
}
async function seen() {
  return new Set((await devCodes()).map((c) => c.at + c.code));
}

async function account(label) {
  const email = `${label}-${run}@example.com`;
  const before = await seen();
  let r = await call('POST', '/api/v1/auth/register', { body: { email, password: PASSWORD, acceptedTermsVersion: '2024-01' } });
  if (!expect(`register ${label}`, r, 201)) throw new Error('register failed (429? use npm run dev:direct-auth)');
  r = await call('POST', '/api/v1/auth/login', { body: { email, password: PASSWORD } });
  expect(`login ${label}`, r, 200);
  let tokens = r.json;
  const code = await newCode('email', before);
  r = await call('POST', '/api/v1/auth/email/verify', { token: tokens.accessToken, body: { code } });
  expect(`verify email ${label}`, r, 204);
  r = await call('GET', '/api/v1/accounts/me', { token: tokens.accessToken });
  return { email, tokens, id: r.json.id, label };
}
async function relogin(a) {
  const r = await call('POST', '/api/v1/auth/login', { body: { email: a.email, password: PASSWORD } });
  a.tokens = r.json;
}
// A tiny valid PNG (1x1).
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
async function uploadVia(slotRes, label) {
  const slot = slotRes.json;
  const u = new URL(slot.uploadUrl);
  const viaProxy = `/_storage${u.pathname}${u.search}`; // what the console does with localhost:9000 URLs
  const headers = {};
  for (const [k, v] of Object.entries(slot.requiredHeaders ?? {})) if (!['content-length', 'host'].includes(k.toLowerCase())) headers[k] = v;
  const res = await fetch(BASE + viaProxy, { method: 'PUT', headers, body: PNG });
  if (res.ok) ok(`PUT ${label} through /_storage proxy (${res.status}); required headers: ${JSON.stringify(slot.requiredHeaders)}`);
  else bad(`PUT ${label}: ${res.status} ${(await res.text()).slice(0, 200)}`);
}

const all7 = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((d) => ({ dayOfWeek: d, startTime: '00:00', endTime: '24:00' }));

async function main() {
  step('0. Proxy reachability');
  let r = await call('GET', '/api/v1/search/listings?lat=12.97&lng=77.59&radiusKm=1');
  expect('GET /api through the proxy (search, anonymous)', r, 200);
  r = await call('GET', '/.well-known/jwks.json');
  expect('GET /.well-known/jwks.json through the proxy', r, 200);
  r = await call('GET', '/internal/v1/listings/00000000-0000-0000-0000-000000000000/booking-quote');
  console.log(`  info  /internal is not proxied by the console (dev server answers ${r.status})`);

  step('1. Accounts');
  const host = await account('host');
  const driver = await account('driver');
  const adminA = await account('admin');
  const grant = spawnSync('bash', [path.join(here, 'make-admin.sh'), adminA.email], { encoding: 'utf8' });
  grant.status === 0 ? ok(`make-admin.sh: ${grant.stdout.trim().split('\n')[0]}`) : bad(`make-admin.sh failed: ${grant.stderr}`);
  await relogin(adminA);

  step('2. Host setup: role, phone, profile, photo');
  r = await call('POST', '/api/v1/accounts/me/roles/host', { token: host.tokens.accessToken });
  expect('become host', r, 200);
  const rr = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: host.tokens.refreshToken } });
  expect('refresh-token rotation', rr, 200);
  host.tokens = rr.json;
  const reuse = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: 'x'.repeat(40) } });
  expect('bogus refresh token is rejected with a problem', reuse, 401);
  const claims = JSON.parse(Buffer.from(host.tokens.accessToken.split('.')[1], 'base64url').toString());
  claims.roles.includes('HOST') ? ok(`new JWT carries roles ${claims.roles}`) : bad(`JWT roles ${claims.roles}`);
  let before = await seen();
  r = await call('POST', '/api/v1/auth/phone/verification-requests', { token: host.tokens.accessToken, body: { phone: `+9198${String(run).slice(-8)}` } });
  expect('request phone code', r, 202);
  const pcode = await newCode('phone', before);
  r = await call('POST', '/api/v1/auth/phone/verify', { token: host.tokens.accessToken, body: { code: pcode } });
  expect('verify phone', r, 204);
  r = await call('PUT', '/api/v1/profiles/me', { token: host.tokens.accessToken, body: { displayName: 'E2E Host', city: 'Bengaluru' } });
  expect('profile update', r, 200);
  r = await call('POST', '/api/v1/profiles/me/photo/upload-urls', { token: host.tokens.accessToken, body: { contentType: 'image/png', contentLength: PNG.length } });
  if (expect('profile photo slot', r, 201)) {
    await uploadVia(r, 'profile photo');
    const c = await call('POST', `/api/v1/profiles/me/photo/uploads/${r.json.uploadId}/confirm`, { token: host.tokens.accessToken });
    expect('confirm profile photo', c, 200);
  }
  r = await call('POST', '/api/v1/profiles/me/payout-accounts', { token: host.tokens.accessToken, body: { processorType: 'FAKE', processorToken: `fake_payout_${run}` } });
  expect('add FAKE payout account', r, 201);
  r = await call('GET', '/api/v1/notification-preferences', { token: host.tokens.accessToken });
  expect('notification preferences', r, 200);
  r = await call('PUT', '/api/v1/notification-preferences', { token: host.tokens.accessToken, body: { preferences: [{ channel: 'IN_APP', enabled: false }] } });
  expect('disabling IN_APP is rejected (problem display case)', r, 422);
  console.log(`  info  problem: ${r.text.slice(0, 200)}`);

  step('3. Listing: create, photo, availability, submit');
  const terms = {
    title: `E2E garage ${run}`, description: 'e2e', address: '1 MG Road, Bengaluru', location: { lat: 12.9716, lng: 77.5946 },
    timeZone: 'Asia/Kolkata', pricePerHour: '50.00', currency: 'INR', maxVehicleType: 'SUV', amenities: ['COVERED', 'CCTV'],
    instantBook: true, cancellationPolicy: 'MODERATE', bufferMinutes: 0,
  };
  r = await call('POST', '/api/v1/listings', { token: host.tokens.accessToken, body: terms });
  expect('create listing', r, 201);
  const listing = r.json;
  const etag = r.headers.get('etag');
  etag ? ok(`ETag header visible through the proxy: ${etag}`) : bad('no ETag header on create');
  r = await call('PATCH', `/api/v1/listings/${listing.id}`, { token: host.tokens.accessToken, body: { description: 'edited' }, headers: { 'If-Match': etag } });
  expect('PATCH with If-Match', r, 200);
  r = await call('PATCH', `/api/v1/listings/${listing.id}`, { token: host.tokens.accessToken, body: { description: 'stale' }, headers: { 'If-Match': etag } });
  expect('PATCH with a stale ETag', r, 412);
  r = await call('PUT', `/api/v1/listings/${listing.id}/availability`, { token: host.tokens.accessToken, body: { rules: all7 } });
  expect('availability 24/7', r, 200);
  r = await call('POST', `/api/v1/listings/${listing.id}/photos/upload-urls`, { token: host.tokens.accessToken, body: { contentType: 'image/png', contentLength: PNG.length } });
  if (expect('listing photo slot', r, 201)) {
    await uploadVia(r, 'listing photo');
    const c = await call('POST', `/api/v1/listings/${listing.id}/photos/${r.json.photoId}/confirm`, { token: host.tokens.accessToken });
    expect('confirm listing photo', c, 200);
  }
  const bo = await call('POST', `/api/v1/listings/${listing.id}/blackouts`, {
    token: host.tokens.accessToken, body: { start: new Date(Date.now() + 40 * 86400e3).toISOString(), end: new Date(Date.now() + 41 * 86400e3).toISOString(), reason: 'e2e' },
  });
  if (expect('add blackout', bo, 201)) expect('delete blackout', await call('DELETE', `/api/v1/listings/${listing.id}/blackouts/${bo.json.id}`, { token: host.tokens.accessToken }), 204);
  let submitted;
  for (let i = 0; i < 15; i++) {
    submitted = await call('POST', `/api/v1/listings/${listing.id}/submit`, { token: host.tokens.accessToken });
    if (submitted.status === 200) break;
    await sleep(2000);
  }
  expect('submit listing (retried while eligibility projection arrives)', submitted, 200);

  step('4. Admin moderation through the BFF');
  r = await call('GET', '/admin/v1/moderation/listings', { token: adminA.tokens.accessToken });
  expect('moderation queue', r, 200) && (r.text.includes(listing.id) ? ok('queue contains the listing') : bad('listing missing from queue'));
  expect('queue forbidden for a driver', await call('GET', '/admin/v1/moderation/listings', { token: driver.tokens.accessToken }), 403);
  r = await call('POST', `/admin/v1/moderation/listings/${listing.id}/approve`, { token: adminA.tokens.accessToken, body: {} });
  expect('approve listing', r, 200);

  step('5. Search');
  let found = null;
  for (let i = 0; i < 25 && !found; i++) {
    r = await call('GET', '/api/v1/search/listings?lat=12.9716&lng=77.5946&radiusKm=5&sort=price&amenities=COVERED&instantBook=true');
    found = r.json?.items?.find((x) => x.listingId === listing.id);
    if (!found) await sleep(2000);
  }
  found ? ok(`search finds the listing (distanceKm ${found.distanceKm}, thumbnail ${found.thumbnailUrl ? 'yes' : 'no'})`) : bad('listing not in search');
  const w = soonest();
  r = await call('GET', `/api/v1/search/listings?lat=12.9716&lng=77.5946&radiusKm=5&startTime=${w.start}&endTime=${w.end}`);
  expect('search with a time window', r, 200) && console.log(`  info  window result count ${r.json.items.length}, estimatedTotal ${r.json.items[0]?.estimatedTotal}`);

  step('6. Driver: vehicle, book, idempotency, pay');
  r = await call('POST', '/api/v1/vehicles', { token: driver.tokens.accessToken, body: { licensePlate: `KA 01 E${String(run).slice(-4)}`, plateRegion: 'IN-KA', vehicleType: 'SEDAN', make: 'Test' } });
  expect('add vehicle', r, 201);
  const vehicleId = r.json?.id;
  const idem = `e2e-${run}-a`;
  const bookBody = { listingId: listing.id, vehicleId, startTime: w.start, endTime: w.end };
  r = await call('POST', '/api/v1/bookings', { token: driver.tokens.accessToken, body: bookBody, headers: { 'Idempotency-Key': idem } });
  expect('create booking (fast lane window)', r, 202);
  const booking = r.json;
  const replay = await call('POST', '/api/v1/bookings', { token: driver.tokens.accessToken, body: bookBody, headers: { 'Idempotency-Key': idem } });
  expect('replay with the same key', replay, 200) && (replay.json.id === booking.id ? ok('replay returns the same booking') : bad('replay created another booking'));
  expect('same key, different body', await call('POST', '/api/v1/bookings', { token: driver.tokens.accessToken, body: { ...bookBody, endTime: new Date(Date.parse(w.end) + 900000).toISOString() }, headers: { 'Idempotency-Key': idem } }), 422);
  expect('missing Idempotency-Key', await call('POST', '/api/v1/bookings', { token: driver.tokens.accessToken, body: bookBody }), 400);
  expect('overlap by another key', await call('POST', '/api/v1/bookings', { token: driver.tokens.accessToken, body: bookBody, headers: { 'Idempotency-Key': idem + 'b' } }), 409);
  let intent;
  for (let i = 0; i < 20; i++) {
    intent = await call('GET', `/api/v1/payments/intents?bookingId=${booking.id}`, { token: driver.tokens.accessToken });
    if (intent.status === 200) break;
    await sleep(1000);
  }
  expect('payment intent appears (async)', intent, 200);
  r = await call('POST', `/api/v1/payments/intents/${intent.json.id}/authorize`, { token: driver.tokens.accessToken, body: { paymentMethodToken: 'fake_ok' }, headers: { 'Idempotency-Key': `pay-${run}-1` } });
  expect('authorize with fake_ok', r, 200) && (r.json.status === 'AUTHORIZED' ? ok('intent AUTHORIZED') : bad(`intent ${r.json.status}`));
  let b = booking;
  for (let i = 0; i < 20; i++) {
    b = (await call('GET', `/api/v1/bookings/${booking.id}`, { token: driver.tokens.accessToken })).json;
    if (b.status === 'CONFIRMED') break;
    await sleep(1000);
  }
  b.status === 'CONFIRMED' ? ok('booking CONFIRMED (status polling works)') : bad(`booking ${b.status}`);
  expect('host sees it in role=host list', await call('GET', '/api/v1/bookings?role=host&when=upcoming', { token: host.tokens.accessToken }), 200);
  expect('check-in is refused before the window', await call('POST', `/api/v1/bookings/${booking.id}/check-in`, { token: driver.tokens.accessToken }), 409, 200);

  step('7. Declined card and second booking cancel (refund tier)');
  const far = { start: new Date(Date.now() + 3 * 86400e3), end: new Date(Date.now() + 3 * 86400e3 + 2 * 3600e3) };
  far.start.setUTCMinutes(0, 0, 0);
  far.end = new Date(far.start.getTime() + 2 * 3600e3);
  r = await call('POST', '/api/v1/bookings', { token: driver.tokens.accessToken, body: { listingId: listing.id, startTime: far.start.toISOString(), endTime: far.end.toISOString() }, headers: { 'Idempotency-Key': `e2e-${run}-far` } });
  expect('create 3-days-ahead booking', r, 202);
  const far1 = r.json;
  for (let i = 0; i < 20; i++) {
    intent = await call('GET', `/api/v1/payments/intents?bookingId=${far1.id}`, { token: driver.tokens.accessToken });
    if (intent.status === 200) break;
    await sleep(1000);
  }
  r = await call('POST', `/api/v1/payments/intents/${intent.json.id}/authorize`, { token: driver.tokens.accessToken, body: { paymentMethodToken: 'fake_ok' }, headers: { 'Idempotency-Key': `pay-${run}-2` } });
  expect('authorize far booking', r, 200);
  await sleep(4000);
  r = await call('POST', `/api/v1/bookings/${far1.id}/cancel`, { token: driver.tokens.accessToken, body: { reason: 'e2e cancel' } });
  expect('cancel 3 days ahead', r, 200) && console.log(`  info  status ${r.json.status}, refundAmount ${r.json.refundAmount} of ${r.json.totalAmount} (MODERATE >= 24h = 100%)`);
  r = await call('GET', '/api/v1/hosts/me/earnings-summary', { token: host.tokens.accessToken });
  expect('host earnings summary', r, 200) && console.log(`  info  ${r.text}`);
  expect('host payouts list', await call('GET', '/api/v1/hosts/me/payouts', { token: host.tokens.accessToken }), 200);

  const dd = await call('POST', '/api/v1/bookings', { token: driver.tokens.accessToken, body: { listingId: listing.id, startTime: new Date(far.start.getTime() + 86400e3).toISOString(), endTime: new Date(far.end.getTime() + 86400e3).toISOString() }, headers: { 'Idempotency-Key': `e2e-${run}-decl` } });
  if (expect('create booking for decline test', dd, 202)) {
    let it2;
    for (let i = 0; i < 20; i++) {
      it2 = await call('GET', `/api/v1/payments/intents?bookingId=${dd.json.id}`, { token: driver.tokens.accessToken });
      if (it2.status === 200) break;
      await sleep(1000);
    }
    r = await call('POST', `/api/v1/payments/intents/${it2.json.id}/authorize`, { token: driver.tokens.accessToken, body: { paymentMethodToken: 'fake_decline' }, headers: { 'Idempotency-Key': `pay-${run}-3` } });
    expect('authorize with fake_decline', r, 200) && console.log(`  info  intent ${r.json.status} failureCode ${r.json.failureCode}`);
    await sleep(4000);
    const bd = await call('GET', `/api/v1/bookings/${dd.json.id}`, { token: driver.tokens.accessToken });
    console.log(`  info  booking after decline: ${bd.json.status}`);
  }

  step('8. Notifications, admin case and refund');
  r = await call('GET', '/api/v1/notifications', { token: driver.tokens.accessToken });
  expect('driver feed', r, 200) && console.log(`  info  ${r.json.items.length} items, first: ${r.json.items[0]?.title}`);
  r = await call('GET', '/api/v1/notifications/unread-count', { token: driver.tokens.accessToken });
  expect('unread count', r, 200) && console.log(`  info  unread ${r.json.unread}`);
  if (r.json.unread > 0) {
    const first = (await call('GET', '/api/v1/notifications', { token: driver.tokens.accessToken })).json.items[0];
    expect('mark one read', await call('POST', `/api/v1/notifications/${first.id}/read`, { token: driver.tokens.accessToken }), 204);
    expect('mark all read', await call('POST', '/api/v1/notifications/read-all', { token: driver.tokens.accessToken }), 200);
  }
  r = await call('GET', `/admin/v1/bookings/${booking.id}`, { token: adminA.tokens.accessToken });
  expect('admin booking case', r, 200);
  r = await call('POST', `/admin/v1/bookings/${booking.id}/refunds`, { token: adminA.tokens.accessToken, body: { amount: '10.00', reason: 'e2e goodwill' }, headers: { 'Idempotency-Key': `rf-${run}` } });
  expect('admin refund via BFF', r, 200) && console.log(`  info  ${r.text}`);
  expect('admin refund replay (same key)', await call('POST', `/admin/v1/bookings/${booking.id}/refunds`, { token: adminA.tokens.accessToken, body: { amount: '10.00', reason: 'e2e goodwill' }, headers: { 'Idempotency-Key': `rf-${run}` } }), 200);
  expect('refund forbidden for the driver', await call('POST', `/admin/v1/bookings/${booking.id}/refunds`, { token: driver.tokens.accessToken, body: { amount: '1.00', reason: 'no' }, headers: { 'Idempotency-Key': `rf2-${run}` } }), 403);

  step('9. Forgot / reset password');
  before = await seen();
  expect('forgot password (always 202)', await call('POST', '/api/v1/auth/password/forgot', { body: { email: driver.email } }), 202);
  const token = await newCode('password reset', before);
  r = await call('POST', '/api/v1/auth/password/reset', { body: { token, newPassword: PASSWORD + 'x' } });
  expect('reset password with the logged token', r, 204);
  r = await call('POST', '/api/v1/auth/login', { body: { email: driver.email, password: PASSWORD + 'x' } });
  expect('login with the new password', r, 200);
  driver.tokens = r.json;
  PASSWORDS.driver = PASSWORD + 'x';

  step('10. Reviews endpoints (no completed booking yet)');
  expect('public listing reviews', await call('GET', `/api/v1/reviews?targetType=LISTING&targetId=${listing.id}`), 200);
  expect('public summary', await call('GET', `/api/v1/reviews/summary?targetType=LISTING&targetId=${listing.id}`), 200);
  expect('driver reviews need a token', await call('GET', `/api/v1/reviews?targetType=DRIVER&targetId=${driver.id}`), 401);
  expect('driver reviews with a token', await call('GET', `/api/v1/reviews?targetType=DRIVER&targetId=${driver.id}`, { token: host.tokens.accessToken }), 200);

  if (COMPLETE) {
    step('11. Completion path (waits for the check-in window)');
    const opens = Date.parse(booking.startTime) - 15 * 60_000;
    console.log(`  info  check-in opens ${new Date(opens).toISOString()}; waiting ${Math.max(0, Math.round((opens - Date.now()) / 1000))} s`);
    while (Date.now() < opens + 2000) await sleep(5000);
    // Access tokens live 15 minutes: rotate via the refresh tokens (what the console does transparently).
    for (const who of [driver, host, adminA]) {
      const t = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: who.tokens.refreshToken } });
      expect(`refresh ${who.label} after the wait`, t, 200) && (who.tokens = t.json);
    }
    r = await call('POST', `/api/v1/bookings/${booking.id}/check-in`, { token: driver.tokens.accessToken });
    expect('check-in', r, 200);
    r = await call('POST', `/api/v1/bookings/${booking.id}/check-out`, { token: driver.tokens.accessToken });
    expect('check-out -> COMPLETED immediately', r, 200) && (r.json.status === 'COMPLETED' ? ok('booking COMPLETED right after check-out') : bad(`status ${r.json.status}`));
    let rev;
    for (let i = 0; i < 20; i++) {
      rev = await call('POST', '/api/v1/reviews', { token: driver.tokens.accessToken, body: { bookingId: booking.id, rating: 5, comment: 'Great spot' } });
      if (rev.status !== 404) break;
      await sleep(2000);
    }
    expect('driver reviews the listing (retried while BookingCompleted propagates)', rev, 201);
    expect('host reviews the driver', await call('POST', '/api/v1/reviews', { token: host.tokens.accessToken, body: { bookingId: booking.id, rating: 4, comment: 'Polite' } }), 201);
    expect('duplicate review is 409', await call('POST', '/api/v1/reviews', { token: driver.tokens.accessToken, body: { bookingId: booking.id, rating: 5 } }), 409);
    expect('stranger cannot review', await call('POST', '/api/v1/reviews', { token: adminA.tokens.accessToken, body: { bookingId: booking.id, rating: 1 } }), 403);
    r = await call('GET', `/api/v1/reviews?targetType=LISTING&targetId=${listing.id}`);
    expect('listing reviews list', r, 200) && console.log(`  info  ${r.json.items.length} review(s)`);
    await sleep(1000);
    r = await call('GET', `/api/v1/reviews/summary?targetType=LISTING&targetId=${listing.id}`);
    console.log(`  info  summary ${r.text}`);
    const hide = await call('POST', `/admin/v1/moderation/reviews/${rev.json.id}/hide`, { token: adminA.tokens.accessToken, body: { reason: 'e2e hide' } });
    expect('admin hides the review', hide, 200);
    r = await call('GET', `/api/v1/reviews/summary?targetType=LISTING&targetId=${listing.id}`);
    console.log(`  info  summary after hide ${r.text}`);
  } else {
    console.log('\n  SKIP  completion/review path (run with --complete; waits up to ~17 min for the check-in window)');
  }

  console.log(`\nResult: ${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
}
const PASSWORDS = {};
function soonest() {
  const q = 15 * 60_000;
  const start = Math.ceil((Date.now() + 17 * 60_000) / q) * q;
  return { start: new Date(start).toISOString(), end: new Date(start + 30 * 60_000).toISOString() };
}
main().catch((e) => {
  console.error('ABORTED:', e);
  process.exit(2);
});
