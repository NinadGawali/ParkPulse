import { ApiClient, type Tokens, type TokenStore } from './client';
import { pushDebug } from '../state/debug';

const KEY = 'parksmart.tokens';

/** Tokens live in localStorage: acceptable for a local-only test console, never for production. */
export const tokenStore: TokenStore & { subscribe(l: () => void): () => void } = {
  get(): Tokens | null {
    try {
      const raw = localStorage.getItem(KEY);
      return raw ? (JSON.parse(raw) as Tokens) : null;
    } catch {
      return null;
    }
  },
  set(t) {
    try {
      if (t) localStorage.setItem(KEY, JSON.stringify(t));
      else localStorage.removeItem(KEY);
    } catch {
      /* storage unavailable */
    }
    listeners.forEach((l) => l());
  },
  subscribe(l) {
    listeners.add(l);
    return () => listeners.delete(l);
  },
};
const listeners = new Set<() => void>();

export const api = new ApiClient({
  tokens: tokenStore,
  onDebug: pushDebug,
});
