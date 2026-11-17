// LOCAL DEVELOPMENT ONLY. Seeds the demo accounts and data described in RUNNING.md.
// Usage (repo root, Git Bash, stack running): node web/scripts/seed-demo.mjs
// Safe to re-run: existing accounts, listings and the pending request are reused, not duplicated.
// Auth calls go straight to Identity (:8081), like infra/scripts/smoke-test.sh, so the gateway's per-IP
// login/register limits are not used up. Codes are read from logs/notification.log; ADMIN is granted with
// web/scripts/make-admin.sh (docker exec into the local postgres container).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const ID = process.env.IDENTITY_URL ?? 'http://localhost:8081';
const GW = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const LOG = path.join(REPO, 'logs/notification.log');
const PW = 'Demo12345park';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(base, method, p, { token, body, headers = {}, ok = [200, 201, 202, 204] } = {}) {
  const h = { Accept: 'application/json', ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const r = await fetch(base + p, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  const data = text ? JSON.parse(text) : null;
  if (!ok.includes(r.status)) throw Object.assign(new Error(`${method} ${p} -> ${r.status} ${text.slice(0, 300)}`), { status: r.status, data, headers: r.headers });
  return { status: r.status, data, headers: r.headers };
}

const RE = /^(\S+)\s.*?DEV ONLY body: Use (\S+) to (verify your email|verify your phone)\./;
async function code(purpose, since) {
  for (let i = 0; i < 40; i++) {
    for (const l of fs.readFileSync(LOG, 'utf8').split(/\r?\n/).reverse()) {
      const m = RE.exec(l);
      if (m && m[3] === purpose && Date.parse(m[1]) >= since - 2000) return m[2];
    }
    await sleep(500);
  }
  throw new Error('no code ' + purpose);
}

function crc32(b) { let c, x = 0xffffffff; for (const v of b) { c = (x ^ v) & 255; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; x = (x >>> 8) ^ c; } return (x ^ 0xffffffff) >>> 0; }
function png(w, h, f) {
  const ch = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); };
  const hd = Buffer.alloc(13); hd.writeUInt32BE(w, 0); hd.writeUInt32BE(h, 4); hd[8] = 8; hd[9] = 2;
  const rows = []; for (let y = 0; y < h; y++) { const r = Buffer.alloc(1 + w * 3); for (let x = 0; x < w; x++) { const [a, b, c] = f(x, y); r[1 + x * 3] = a; r[2 + x * 3] = b; r[3 + x * 3] = c; } rows.push(r); }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), ch('IHDR', hd), ch('IDAT', zlib.deflateSync(Buffer.concat(rows))), ch('IEND', Buffer.alloc(0))]);
}
// simple "parking bay" pictures: asphalt with white bay lines
const bay = (hue) => png(480, 320, (x, y) => (x % 120 < 6 || y > 300 ? [235, 235, 235] : [60 + hue, 64 + (hue >> 1), 70]));
const face = png(200, 200, (x, y) => ((x - 100) ** 2 + (y - 90) ** 2 < 3600 ? [230, 190, 160] : [13, 138, 122]));

async function upload(slot, bytes) {
  const headers = {};
  for (const [k, v] of Object.entries(slot.requiredHeaders)) if (!['host', 'content-length'].includes(k.toLowerCase())) headers[k] = v;
  const r = await fetch(slot.uploadUrl, { method: 'PUT', headers, body: bytes });
  if (!r.ok) throw new Error('storage PUT ' + r.status);
}

async function account(email, name) {
  let since = Date.now();
  try {
    await call(ID, 'POST', '/api/v1/auth/register', { body: { email, password: PW, acceptedTermsVersion: '2024-01' } });
  } catch (e) {
    if (e.status !== 409) throw e;
    console.log(`  ${email} already exists, reusing`);
    since = 0;
  }
  let t = (await call(ID, 'POST', '/api/v1/auth/login', { body: { email, password: PW } })).data;
  const me = (await call(GW, 'GET', '/api/v1/accounts/me', { token: t.accessToken })).data;
  if (!me.emailVerified) {
    // Fresh account: Identity already emailed a code. Existing one: ask for a new code (waiting out the cooldown).
    let verified = false;
    for (let attempt = 0; attempt < 3 && !verified; attempt++) {
      let after = since;
      if (!since || attempt > 0) {
        for (;;) {
          after = Date.now();
          try { await call(ID, 'POST', '/api/v1/auth/email/verification-requests', { token: t.accessToken }); break; } catch (e) { if (e.status !== 429) throw e; await sleep(10000); }
        }
      }
      const c = await code('verify your email', after);
      try { await call(ID, 'POST', '/api/v1/auth/email/verify', { token: t.accessToken, body: { code: c } }); verified = true; } catch (e) { if (e.status !== 422) throw e; }
    }
    if (!verified) throw new Error('could not verify ' + email);
  }
  for (let i = 0; i < 20; i++) {
    try { await call(GW, 'PUT', '/api/v1/profiles/me', { token: t.accessToken, body: { displayName: name, city: 'Bengaluru' } }); break; } catch (e) { if (e.status !== 404) throw e; await sleep(500); }
  }
  console.log(`  ${email}: verified, name "${name}"`);
  return { email, t, id: me.id };
}
const refresh = async (a) => { a.t = (await call(ID, 'POST', '/api/v1/auth/refresh', { body: { refreshToken: a.t.refreshToken } })).data; };

async function listing(host, terms, img, submit = true) {
  const mine = (await call(GW, 'GET', '/api/v1/hosts/me/listings?limit=50', { token: host.t.accessToken })).data.items;
  const existing = mine.find((x) => x.title === terms.title && x.status !== 'ARCHIVED');
  if (existing) {
    console.log(`  listing "${terms.title}" exists (${existing.status})`);
    return { id: existing.id, status: existing.status };
  }
  const l = (await call(GW, 'POST', '/api/v1/listings', { token: host.t.accessToken, body: terms })).data;
  const slot = (await call(GW, 'POST', `/api/v1/listings/${l.id}/photos/upload-urls`, { token: host.t.accessToken, body: { contentType: 'image/png', contentLength: img.length } })).data;
  await upload(slot, img);
  await call(GW, 'POST', `/api/v1/listings/${l.id}/photos/${slot.photoId}/confirm`, { token: host.t.accessToken });
  const days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
  await call(GW, 'PUT', `/api/v1/listings/${l.id}/availability`, { token: host.t.accessToken, body: { rules: days.map((d) => ({ dayOfWeek: d, startTime: '00:00', endTime: '24:00' })) } });
  if (submit) {
    for (let i = 0; i < 30; i++) {
      try { await call(GW, 'POST', `/api/v1/listings/${l.id}/submit`, { token: host.t.accessToken }); break; } catch (e) { if (e.status !== 422 || i === 29) throw e; await sleep(1000); }
    }
  }
  console.log(`  listing "${terms.title}" ${submit ? 'submitted' : 'draft'}`);
  return { id: l.id, status: submit ? 'PENDING_REVIEW' : 'DRAFT' };
}

const base = { description: '', timeZone: 'Asia/Kolkata', currency: 'INR', bufferMinutes: 0 };

console.log('== accounts');
const driver = await account('driver@parksmart.dev', 'Dev Driver');
const host = await account('host@parksmart.dev', 'Harini Host');
const admin = await account('admin@parksmart.dev', 'Ada Admin');
const driver2 = await account('driver2@parksmart.dev', 'Second Driver');

console.log('== host setup');
const meH = (await call(GW, 'GET', '/api/v1/accounts/me', { token: host.t.accessToken })).data;
if (!meH.roles.includes('HOST')) await call(GW, 'POST', '/api/v1/accounts/me/roles/host', { token: host.t.accessToken });
await refresh(host);
const prof = (await call(GW, 'GET', '/api/v1/profiles/me', { token: host.t.accessToken })).data;
if (!prof.hasPhoto) {
  const s = (await call(GW, 'POST', '/api/v1/profiles/me/photo/upload-urls', { token: host.t.accessToken, body: { contentType: 'image/png', contentLength: face.length } })).data;
  await upload(s, face);
  await call(GW, 'POST', `/api/v1/profiles/me/photo/uploads/${s.uploadId}/confirm`, { token: host.t.accessToken });
}
if (!meH.phoneVerified) {
  const since = Date.now();
  await call(ID, 'POST', '/api/v1/auth/phone/verification-requests', { token: host.t.accessToken, body: { phone: '+919800000' + String(Date.now()).slice(-3) } });
  await call(ID, 'POST', '/api/v1/auth/phone/verify', { token: host.t.accessToken, body: { code: await code('verify your phone', since) } });
}
const payouts = (await call(GW, 'GET', '/api/v1/profiles/me/payout-accounts', { token: host.t.accessToken })).data;
if (payouts.items.length === 0) await call(GW, 'POST', '/api/v1/profiles/me/payout-accounts', { token: host.t.accessToken, body: { processorType: 'FAKE', processorToken: 'demo_host_payout' } });
console.log('  HOST role, photo, verified phone, payout account');

console.log('== admin');
const g = spawnSync('bash', [path.join(REPO, 'web/scripts/make-admin.sh'), admin.email], { encoding: 'utf8', cwd: REPO });
if (g.status !== 0) throw new Error('make-admin: ' + g.stderr);
await refresh(admin);

console.log('== listings');
const instant = await listing(host, { ...base, title: 'Covered garage by Indiranagar metro', description: 'Gated basement bay, 2 minutes from Indiranagar metro. Show the booking to the guard; bay 14.', address: '100 Feet Road, Indiranagar, Bengaluru 560038', location: { lat: 12.9719, lng: 77.6412 }, pricePerHour: '60.00', maxVehicleType: 'SUV', amenities: ['COVERED', 'GATED', 'CCTV', 'TWENTY_FOUR_SEVEN_ACCESS'], instantBook: true, cancellationPolicy: 'FLEXIBLE' }, bay(0));
const request = await listing(host, { ...base, title: 'Driveway near Cubbon Park (host approves)', description: 'Open driveway in a quiet lane. Fits one car.', address: '14 Museum Road, Bengaluru 560001', location: { lat: 12.9729, lng: 77.6017 }, pricePerHour: '40.00', maxVehicleType: 'SEDAN', amenities: ['LIGHTING'], instantBook: false, cancellationPolicy: 'MODERATE' }, bay(40));
const evspot = await listing(host, { ...base, title: 'EV charging bay in Koramangala', description: 'Private bay with a 7 kW charger.', address: '80 Feet Road, Koramangala, Bengaluru 560034', location: { lat: 12.9352, lng: 77.6245 }, pricePerHour: '90.00', maxVehicleType: 'SUV', amenities: ['EV_CHARGER', 'COVERED', 'CCTV'], instantBook: true, cancellationPolicy: 'STRICT' }, bay(80));
for (const l of [instant, request]) {
  if (l.status === 'PENDING_REVIEW') await call(GW, 'POST', `/admin/v1/moderation/listings/${l.id}/approve`, { token: admin.t.accessToken, body: {} });
}
void evspot;
console.log('  garage and driveway are live; the EV bay stays in the admin review queue');

console.log('== driver data');
const veh = (await call(GW, 'GET', '/api/v1/vehicles', { token: driver.t.accessToken })).data;
if (veh.items.length === 0) await call(GW, 'POST', '/api/v1/vehicles', { token: driver.t.accessToken, body: { licensePlate: 'KA01AB1234', plateRegion: 'IN-KA', vehicleType: 'SEDAN', make: 'Maruti', model: 'Ciaz', color: 'White' } });
await sleep(3000);
// A request-to-book booking tomorrow 10:00-12:00 IST, paid -> waits for the host.
const open = (await call(GW, 'GET', '/api/v1/bookings?role=driver&when=upcoming&limit=50', { token: driver2.t.accessToken })).data.items.filter(
  (b) => b.listingId === request.id && ['PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL', 'CONFIRMED'].includes(b.status),
);
if (open.length > 0) {
  console.log('  driver2 already has a booking on the driveway (' + open[0].status + ')');
  console.log('\nSEED OK');
  process.exit(0);
}
const tomorrow = new Date(Date.now() + 24 * 3600e3);
const ymd = tomorrow.toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
const start = new Date(`${ymd}T10:00:00+05:30`).toISOString();
const end = new Date(`${ymd}T12:00:00+05:30`).toISOString();
let bk;
for (let i = 0; i < 20; i++) {
  try {
    bk = (await call(GW, 'POST', '/api/v1/bookings', { token: driver2.t.accessToken, body: { listingId: request.id, startTime: start, endTime: end }, headers: { 'Idempotency-Key': 'ps-' + crypto.randomUUID() } })).data;
    break;
  } catch (e) { if (i === 19) throw e; await sleep(1000); }
}
let intent;
for (let i = 0; i < 30 && !intent; i++) { try { intent = (await call(GW, 'GET', `/api/v1/payments/intents?bookingId=${bk.id}`, { token: driver2.t.accessToken })).data; } catch { await sleep(500); } }
await call(GW, 'POST', `/api/v1/payments/intents/${intent.id}/authorize`, { token: driver2.t.accessToken, body: { paymentMethodToken: 'fake_ok' }, headers: { 'Idempotency-Key': 'ps-' + crypto.randomUUID() } });
console.log('  driver2 requested + paid a booking on the driveway: waiting for host approval');
console.log('\nSEED OK');
