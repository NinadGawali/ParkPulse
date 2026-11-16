import { ApiClient, type Tokens, type TokenStore } from './client';
import { pushDebug } from '../state/debug';

const KEY = 'parksmart.session';
const LEGACY_KEY = 'parksmart.tokens';

/**
 * The access token lives only in memory (gone on reload, never in storage). The refresh token is kept in
 * localStorage so a reload or a new tab can start a session with one refresh call. Hardening item for
 * phase 11: move the refresh token to an httpOnly SameSite cookie set by Identity.
 *
 * `get()` returns `accessToken: ''` while only the refresh token is known; the auth bootstrap (and the
 * client on the first request) refreshes before calling the API.
 */
let accessToken = '';
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

function readRefresh(): string | null {
  try {
    const legacy = localStorage.getItem(LEGACY_KEY);
    if (legacy) {
      // Old console stored both tokens; keep only the refresh token.
      localStorage.removeItem(LEGACY_KEY);
      const t = JSON.parse(legacy) as Tokens;
      if (t.refreshToken) localStorage.setItem(KEY, t.refreshToken);
    }
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export const tokenStore: TokenStore & { subscribe(l: () => void): () => void } = {
  get(): Tokens | null {
    const refreshToken = readRefresh();
    return refreshToken ? { accessToken, refreshToken } : null;
  },
  set(t) {
    accessToken = t?.accessToken ?? '';
    try {
      if (t) localStorage.setItem(KEY, t.refreshToken);
      else localStorage.removeItem(KEY);
    } catch {
      /* storage unavailable: the session lasts for this tab only */
    }
    notify();
  },
  subscribe(l) {
    listeners.add(l);
    return () => listeners.delete(l);
  },
};

// Logging out (or a revoked session) in another tab ends this one too.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === KEY && !e.newValue) {
      accessToken = '';
      notify();
    }
  });
}

export const api = new ApiClient({
  tokens: tokenStore,
  // The request/response log only exists on the dev server (debug panel).
  onDebug: import.meta.env.DEV ? pushDebug : undefined,
});
