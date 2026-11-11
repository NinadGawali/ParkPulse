import type { Problem, TokenResponse } from './types';

export interface Tokens {
  accessToken: string;
  refreshToken: string;
}
export interface TokenStore {
  get(): Tokens | null;
  set(tokens: Tokens | null): void;
}

export interface DebugEntry {
  id: number;
  at: string;
  method: string;
  url: string;
  requestHeaders: Record<string, string>;
  requestBody?: unknown;
  status: number; // 0 = network error
  responseHeaders: Record<string, string>;
  responseBody?: unknown;
  ms: number;
  note?: string;
}

/** An error response from the API (or a network failure, status 0), always in RFC 7807 shape. */
export class ApiError extends Error {
  readonly problem: Problem;
  constructor(problem: Problem) {
    super(`${problem.status} ${problem.title}${problem.detail ? `: ${problem.detail}` : ''}`);
    this.name = 'ApiError';
    this.problem = problem;
  }
  get status(): number {
    return this.problem.status;
  }
  /** The slug after https://parksmart.dev/problems/ (or the whole type). */
  get slug(): string {
    return this.problem.type.replace(/^.*\/problems\//, '');
  }
}

export interface RequestOptions {
  query?: Record<string, string | number | boolean | null | undefined>;
  body?: unknown;
  /** Send the bearer token (default true) and refresh it transparently on 401. */
  auth?: boolean;
  /** true = generate a fresh Idempotency-Key, string = use this one. */
  idempotency?: boolean | string;
  headers?: Record<string, string>;
}
export interface RawResponse<T> {
  data: T;
  status: number;
  headers: Headers;
  idempotencyKey?: string;
}

export interface ClientOptions {
  tokens: TokenStore;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  onDebug?: (entry: DebugEntry) => void;
  /** Called when the session cannot be refreshed any more (the user must log in again). */
  onAuthLost?: () => void;
  /** Called after a successful refresh so the UI can re-read the roles in the new token. */
  onTokens?: (tokens: Tokens) => void;
  now?: () => number;
}

const IDEMPOTENCY_PATTERN = /^[A-Za-z0-9_-]{8,100}$/;

/** `ps-<uuid>`: matches the contract pattern ^[A-Za-z0-9_-]{8,100}$. */
export function newIdempotencyKey(): string {
  const id =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;
  return `ps-${id}`;
}
export function isValidIdempotencyKey(k: string): boolean {
  return IDEMPOTENCY_PATTERN.test(k);
}

export interface Claims {
  sub: string;
  roles: string[];
  exp: number;
  [k: string]: unknown;
}
export function decodeJwt(token: string): Claims | null {
  try {
    const part = token.split('.')[1];
    if (!part) return null;
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='));
    const c = JSON.parse(json) as Claims;
    return { ...c, roles: Array.isArray(c.roles) ? c.roles : [] };
  } catch {
    return null;
  }
}

/** Turn any failed response into a Problem (RFC 7807 when the server sent one). */
export function parseProblem(status: number, contentType: string | null, text: string): Problem {
  let parsed: unknown;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = undefined;
    }
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const p = parsed as Record<string, unknown>;
    const looksLikeProblem = (contentType ?? '').includes('problem+json') || 'title' in p || 'type' in p;
    if (looksLikeProblem) {
      return {
        ...p,
        type: typeof p.type === 'string' ? p.type : 'about:blank',
        title: typeof p.title === 'string' ? p.title : defaultTitle(status),
        status: typeof p.status === 'number' ? p.status : status,
      } as Problem;
    }
  }
  return {
    type: 'about:blank',
    title: defaultTitle(status),
    status,
    detail: text ? text.slice(0, 500) : undefined,
  };
}
function defaultTitle(status: number): string {
  const titles: Record<number, string> = {
    0: 'Network error',
    400: 'Bad Request',
    401: 'Unauthorized',
    403: 'Forbidden',
    404: 'Not Found',
    409: 'Conflict',
    412: 'Precondition Failed',
    422: 'Unprocessable Entity',
    429: 'Too Many Requests',
    500: 'Internal Server Error',
    502: 'Bad Gateway',
    503: 'Service Unavailable',
  };
  return titles[status] ?? `HTTP ${status}`;
}

const SECRET_FIELDS = new Set(['password', 'newPassword', 'refreshToken', 'accessToken', 'processorToken']);
export function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([k, v]) => [
        k,
        SECRET_FIELDS.has(k) && typeof v === 'string' ? `<${k} hidden, ${v.length} chars>` : redact(v),
      ]),
    );
  }
  return value;
}

export class ApiClient {
  private readonly tokens: TokenStore;
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;
  private readonly onDebug?: (entry: DebugEntry) => void;
  private readonly onAuthLost?: () => void;
  private readonly onTokens?: (t: Tokens) => void;
  private readonly now: () => number;
  private refreshing: Promise<boolean> | null = null;
  private seq = 0;

  constructor(opts: ClientOptions) {
    this.tokens = opts.tokens;
    this.baseUrl = opts.baseUrl ?? '';
    this.fetchImpl = opts.fetchImpl ?? ((...a) => fetch(...a));
    this.onDebug = opts.onDebug;
    this.onAuthLost = opts.onAuthLost;
    this.onTokens = opts.onTokens;
    this.now = opts.now ?? (() => Date.now());
  }

  async request<T = unknown>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
    return (await this.raw<T>(method, path, opts)).data;
  }
  get<T = unknown>(path: string, opts: RequestOptions = {}) {
    return this.request<T>('GET', path, opts);
  }
  post<T = unknown>(path: string, body?: unknown, opts: RequestOptions = {}) {
    return this.request<T>('POST', path, { ...opts, body });
  }
  put<T = unknown>(path: string, body?: unknown, opts: RequestOptions = {}) {
    return this.request<T>('PUT', path, { ...opts, body });
  }
  patch<T = unknown>(path: string, body: unknown, opts: RequestOptions = {}) {
    return this.request<T>('PATCH', path, { ...opts, body });
  }
  delete<T = unknown>(path: string, opts: RequestOptions = {}) {
    return this.request<T>('DELETE', path, opts);
  }

  async raw<T = unknown>(method: string, path: string, opts: RequestOptions = {}): Promise<RawResponse<T>> {
    const auth = opts.auth !== false;
    let key: string | undefined;
    if (typeof opts.idempotency === 'string') key = opts.idempotency;
    else if (opts.idempotency) key = newIdempotencyKey(); // generated once: a 401-retry reuses it

    if (auth) await this.refreshIfExpiring();
    let res = await this.once<T>(method, path, opts, auth, key);
    if (res.status === 401 && auth && this.tokens.get()?.refreshToken) {
      if (await this.refresh()) {
        res = await this.once<T>(method, path, opts, auth, key, 'retried after token refresh');
      }
    }
    if (res.error) throw res.error;
    return { data: res.data as T, status: res.status, headers: res.headers, idempotencyKey: key };
  }

  /** Rotates the refresh token. Concurrent callers share one in-flight refresh. */
  refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = this.doRefresh().finally(() => {
        this.refreshing = null;
      });
    }
    return this.refreshing;
  }

  private async doRefresh(): Promise<boolean> {
    const current = this.tokens.get();
    if (!current) return false;
    const res = await this.once<TokenResponse>(
      'POST',
      '/api/v1/auth/refresh',
      { body: { refreshToken: current.refreshToken } },
      false,
      undefined,
      'refresh-token rotation',
    );
    if (!res.error && res.data) {
      const next = { accessToken: res.data.accessToken, refreshToken: res.data.refreshToken };
      this.tokens.set(next);
      this.onTokens?.(next);
      return true;
    }
    // 4xx = the refresh token is dead (expired, revoked, reuse detected). Network/5xx: keep it.
    if (res.status >= 400 && res.status < 500 && res.status !== 429) {
      this.tokens.set(null);
      this.onAuthLost?.();
    }
    return false;
  }

  private async refreshIfExpiring(): Promise<void> {
    const t = this.tokens.get();
    if (!t) return;
    const claims = decodeJwt(t.accessToken);
    if (claims && claims.exp * 1000 - this.now() < 15_000) {
      await this.refresh();
    }
  }

  private async once<T>(
    method: string,
    path: string,
    opts: RequestOptions,
    auth: boolean,
    idempotencyKey?: string,
    note?: string,
  ): Promise<{ status: number; data?: T; headers: Headers; error?: ApiError }> {
    const headers: Record<string, string> = { Accept: 'application/json, application/problem+json', ...opts.headers };
    const token = auth ? this.tokens.get()?.accessToken : undefined;
    if (token) headers.Authorization = `Bearer ${token}`;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    let body: string | undefined;
    if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }
    const url = this.baseUrl + path + buildQuery(opts.query);
    const started = this.now();
    const entry: DebugEntry = {
      id: ++this.seq,
      at: new Date(started).toISOString(),
      method,
      url,
      requestHeaders: redactHeaders(headers),
      requestBody: opts.body === undefined ? undefined : redact(opts.body),
      status: 0,
      responseHeaders: {},
      ms: 0,
      note,
    };
    let response: Response;
    try {
      response = await this.fetchImpl(url, { method, headers, body });
    } catch (e) {
      const problem: Problem = {
        type: 'about:blank',
        title: 'Network error',
        status: 0,
        detail: `${e instanceof Error ? e.message : String(e)}. Is the gateway (:8080) running?`,
      };
      entry.ms = this.now() - started;
      entry.responseBody = problem;
      this.onDebug?.(entry);
      return { status: 0, headers: new Headers(), error: new ApiError(problem) };
    }
    const text = await response.text();
    const contentType = response.headers.get('content-type');
    entry.ms = this.now() - started;
    entry.status = response.status;
    entry.responseHeaders = pickHeaders(response.headers);
    let parsed: unknown;
    if (text) {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = text.slice(0, 2000);
      }
    }
    entry.responseBody = redact(parsed);
    this.onDebug?.(entry);
    if (!response.ok) {
      return { status: response.status, headers: response.headers, error: new ApiError(parseProblem(response.status, contentType, text)) };
    }
    return { status: response.status, headers: response.headers, data: parsed as T };
  }
}

function buildQuery(q: RequestOptions['query']): string {
  if (!q) return '';
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(q)) {
    if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}
function redactHeaders(h: Record<string, string>): Record<string, string> {
  const out = { ...h };
  if (out.Authorization) out.Authorization = `${out.Authorization.slice(0, 16)}... (hidden)`;
  return out;
}
const SHOWN_RESPONSE_HEADERS = ['content-type', 'etag', 'location', 'retry-after', 'x-correlation-id', 'x-ratelimit-remaining'];
function pickHeaders(h: Headers): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of SHOWN_RESPONSE_HEADERS) {
    const v = h.get(k);
    if (v) out[k] = v;
  }
  return out;
}
