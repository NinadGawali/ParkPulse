import { describe, expect, it, vi } from 'vitest';
import { ApiClient, decodeJwt, isValidIdempotencyKey, newIdempotencyKey, parseProblem, type DebugEntry, type Tokens } from '../api/client';

function jwt(exp: number, roles: string[] = ['DRIVER']): string {
  const enc = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${enc({ alg: 'none' })}.${enc({ sub: 'acc-1', roles, exp })}.sig`;
}
const nowMs = 1_800_000_000_000;
const farFuture = nowMs / 1000 + 3600;

function memoryStore(initial: Tokens | null) {
  let t = initial;
  return {
    get: () => t,
    set: (n: Tokens | null) => {
      t = n;
    },
  };
}
function res(status: number, body?: unknown, headers: Record<string, string> = {}): Response {
  const isProblem = status >= 400;
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'content-type': isProblem ? 'application/problem+json' : 'application/json', ...headers },
  });
}
const asFetch = (f: unknown) => f as typeof fetch;
const REFRESH_OK = {
  accessToken: 'fresh.access.token',
  refreshToken: 'new-refresh-token-xxxxxxxx',
  tokenType: 'Bearer',
  expiresIn: 900,
};

describe('problem parsing', () => {
  it('keeps RFC 7807 fields including field errors', () => {
    const p = parseProblem(
      400,
      'application/problem+json',
      JSON.stringify({
        type: 'https://parksmart.dev/problems/validation-failed',
        title: 'Validation failed',
        status: 400,
        detail: 'bad',
        correlationId: 'c-1',
        errors: [{ field: 'email', message: 'must be valid' }],
      }),
    );
    expect(p.type).toBe('https://parksmart.dev/problems/validation-failed');
    expect(p.errors?.[0]).toEqual({ field: 'email', message: 'must be valid' });
    expect(p.correlationId).toBe('c-1');
  });

  it('synthesizes a problem from plain text or empty bodies', () => {
    expect(parseProblem(502, 'text/html', '<html>Bad gateway</html>')).toMatchObject({ status: 502, title: 'Bad Gateway', type: 'about:blank' });
    expect(parseProblem(404, null, '')).toMatchObject({ status: 404, title: 'Not Found' });
  });
});

describe('idempotency keys', () => {
  it('generates keys that satisfy the contract pattern and are unique', () => {
    const a = newIdempotencyKey();
    expect(isValidIdempotencyKey(a)).toBe(true);
    expect(a).not.toBe(newIdempotencyKey());
    expect(isValidIdempotencyKey('short')).toBe(false);
    expect(isValidIdempotencyKey('has space in it')).toBe(false);
  });

  it('sends the header on request and lets the caller choose the key', async () => {
    const fetchImpl = vi.fn(async (_url: string, _init?: RequestInit) => res(202, { id: 'b1' }));
    const c = new ApiClient({
      tokens: memoryStore({ accessToken: jwt(farFuture), refreshToken: 'r'.repeat(30) }),
      fetchImpl: asFetch(fetchImpl),
      now: () => nowMs,
    });
    await c.raw('POST', '/api/v1/bookings', { body: { a: 1 }, idempotency: 'my-fixed-key-1' });
    const gen = await c.raw('POST', '/api/v1/bookings', { body: { a: 1 }, idempotency: true });
    const h0 = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>;
    const h1 = fetchImpl.mock.calls[1][1]?.headers as Record<string, string>;
    expect(h0['Idempotency-Key']).toBe('my-fixed-key-1');
    expect(isValidIdempotencyKey(h1['Idempotency-Key'])).toBe(true);
    expect(gen.idempotencyKey).toBe(h1['Idempotency-Key']);
  });

  it('reuses the same key when a 401 triggers a token refresh and retry', async () => {
    const store = memoryStore({ accessToken: jwt(farFuture), refreshToken: 'old-refresh-token-xxxxxxxx' });
    const seen: string[] = [];
    const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
      const h = (init?.headers ?? {}) as Record<string, string>;
      if (url === '/api/v1/auth/refresh') return res(200, REFRESH_OK);
      seen.push(h['Idempotency-Key']);
      return seen.length === 1 ? res(401, { type: 'x', title: 'Unauthorized', status: 401 }) : res(202, { id: 'b' });
    });
    const c = new ApiClient({ tokens: store, fetchImpl: asFetch(fetchImpl), now: () => nowMs });
    const r = await c.raw('POST', '/api/v1/bookings', { body: {}, idempotency: true });
    expect(r.status).toBe(202);
    expect(seen).toHaveLength(2);
    expect(seen[0]).toBe(seen[1]);
  });
});

describe('token refresh', () => {
  it('refreshes once on 401, rotates the stored refresh token and retries with the new bearer', async () => {
    const store = memoryStore({ accessToken: 'stale.token.x', refreshToken: 'old-refresh-token-xxxxxxxx' });
    const onTokens = vi.fn();
    const auths: (string | undefined)[] = [];
    const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/auth/refresh') {
        expect(JSON.parse(init?.body as string)).toEqual({ refreshToken: 'old-refresh-token-xxxxxxxx' });
        return res(200, REFRESH_OK);
      }
      auths.push((init?.headers as Record<string, string>).Authorization);
      return auths.length === 1 ? res(401, { type: 'about:blank', title: 'Unauthorized', status: 401 }) : res(200, { ok: true });
    });
    const c = new ApiClient({ tokens: store, fetchImpl: asFetch(fetchImpl), onTokens, now: () => nowMs });
    await expect(c.get('/api/v1/accounts/me')).resolves.toEqual({ ok: true });
    expect(auths).toEqual(['Bearer stale.token.x', 'Bearer fresh.access.token']);
    expect(store.get()).toEqual({ accessToken: 'fresh.access.token', refreshToken: 'new-refresh-token-xxxxxxxx' });
    expect(onTokens).toHaveBeenCalledTimes(1);
  });

  it('shares one refresh between concurrent 401s (rotation would otherwise trip reuse detection)', async () => {
    const store = memoryStore({ accessToken: 'stale', refreshToken: 'old-refresh-token-xxxxxxxx' });
    let refreshes = 0;
    const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/auth/refresh') {
        refreshes++;
        await new Promise((r) => setTimeout(r, 10));
        return res(200, { ...REFRESH_OK, accessToken: 'fresh' });
      }
      const h = init?.headers as Record<string, string>;
      return h.Authorization === 'Bearer fresh' ? res(200, { ok: 1 }) : res(401, { type: 'x', title: 'Unauthorized', status: 401 });
    });
    const c = new ApiClient({ tokens: store, fetchImpl: asFetch(fetchImpl), now: () => nowMs });
    await Promise.all([c.get('/a'), c.get('/b'), c.get('/c')]);
    expect(refreshes).toBe(1);
  });

  it('refreshes proactively when the access token is about to expire', async () => {
    const store = memoryStore({ accessToken: jwt(nowMs / 1000 + 5), refreshToken: 'old-refresh-token-xxxxxxxx' });
    const urls: string[] = [];
    const fetchImpl = vi.fn(async (url: string) => {
      urls.push(url);
      return url === '/api/v1/auth/refresh' ? res(200, { ...REFRESH_OK, accessToken: jwt(farFuture) }) : res(200, {});
    });
    const c = new ApiClient({ tokens: store, fetchImpl: asFetch(fetchImpl), now: () => nowMs });
    await c.get('/api/v1/accounts/me');
    expect(urls).toEqual(['/api/v1/auth/refresh', '/api/v1/accounts/me']);
  });

  it('logs the user out when the refresh token is rejected and surfaces the original 401', async () => {
    const store = memoryStore({ accessToken: 'stale', refreshToken: 'old-refresh-token-xxxxxxxx' });
    const onAuthLost = vi.fn();
    const fetchImpl = vi.fn(async () => res(401, { type: 'https://parksmart.dev/problems/unauthorized', title: 'Unauthorized', status: 401 }));
    const c = new ApiClient({ tokens: store, fetchImpl: asFetch(fetchImpl), onAuthLost, now: () => nowMs });
    await expect(c.get('/api/v1/accounts/me')).rejects.toMatchObject({ status: 401 });
    expect(store.get()).toBeNull();
    expect(onAuthLost).toHaveBeenCalledTimes(1);
  });

  it('does not send a bearer or refresh for auth:false calls', async () => {
    const store = memoryStore({ accessToken: 'tok', refreshToken: 'old-refresh-token-xxxxxxxx' });
    const fetchImpl = vi.fn(async (_url: string, _init?: RequestInit) => res(401, { type: 'x', title: 'Unauthorized', status: 401 }));
    const c = new ApiClient({ tokens: store, fetchImpl: asFetch(fetchImpl), now: () => nowMs });
    await expect(c.post('/api/v1/auth/login', {}, { auth: false })).rejects.toMatchObject({ status: 401 });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const headers = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
    expect(store.get()).not.toBeNull();
  });
});

describe('errors and debug log', () => {
  it('turns a network failure into a status-0 problem and records it', async () => {
    const entries: DebugEntry[] = [];
    const c = new ApiClient({
      tokens: memoryStore(null),
      fetchImpl: asFetch(async () => Promise.reject(new TypeError('Failed to fetch'))),
      onDebug: (e) => entries.push(e),
    });
    await expect(c.get('/api/v1/search/listings', { auth: false })).rejects.toMatchObject({ status: 0 });
    expect(entries[0].status).toBe(0);
  });

  it('records requests with secrets redacted and returns ETag headers via raw()', async () => {
    const entries: DebugEntry[] = [];
    const fetchImpl = vi.fn(async () => res(200, { accessToken: 'secret-access', refreshToken: 'secret-refresh', tokenType: 'Bearer', expiresIn: 1 }, { etag: '"7"' }));
    const store = memoryStore({ accessToken: 'abcdefghijklmnopqrstuvwxyz', refreshToken: 'r' });
    const c = new ApiClient({ tokens: store, fetchImpl: asFetch(fetchImpl), onDebug: (e) => entries.push(e), now: () => nowMs });
    const out = await c.raw('POST', '/api/v1/auth/login', { body: { email: 'a@b.c', password: 'hunter2hunter2' }, auth: false });
    expect(out.headers.get('etag')).toBe('"7"');
    const json = JSON.stringify(entries[0]);
    expect(json).not.toContain('hunter2hunter2');
    expect(json).not.toContain('secret-access');
    expect(json).not.toContain('secret-refresh');
    await c.get('/x', { query: { a: 1, b: undefined, c: 'z' } });
    expect(entries[1].url).toBe('/x?a=1&c=z');
    expect(entries[1].requestHeaders.Authorization).not.toContain('uvwxyz');
  });

  it('decodes JWT claims', () => {
    expect(decodeJwt(jwt(123, ['HOST', 'DRIVER']))).toMatchObject({ sub: 'acc-1', roles: ['HOST', 'DRIVER'], exp: 123 });
    expect(decodeJwt('garbage')).toBeNull();
  });
});
