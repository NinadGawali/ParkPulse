import { createContext, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react';
import { decodeJwt, type Tokens } from '../api/client';
import { tokenStore } from '../api';
import { auth } from '../api/endpoints';
import type { AccountSummary, Role } from '../api/types';

interface AuthState {
  loggedIn: boolean;
  accountId: string | null;
  roles: Role[];
  account: AccountSummary | null;
  hasRole(r: Role): boolean;
  reloadAccount(): Promise<AccountSummary | null>;
  login(email: string, password: string): Promise<void>;
  logout(): Promise<void>;
  accessToken: string | null;
}

const Ctx = createContext<AuthState | null>(null);

let cachedRaw: string | null = null;
let cachedTokens: Tokens | null = null;
function snapshot(): Tokens | null {
  const t = tokenStore.get();
  const raw = t ? t.accessToken + t.refreshToken : null;
  if (raw !== cachedRaw) {
    cachedRaw = raw;
    cachedTokens = t;
  }
  return cachedTokens;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const tokens = useSyncExternalStore(tokenStore.subscribe, snapshot);
  const claims = useMemo(() => (tokens ? decodeJwt(tokens.accessToken) : null), [tokens]);
  const [account, setAccount] = useState<AccountSummary | null>(null);

  const reloadAccount = useCallback(async () => {
    if (!tokenStore.get()) {
      setAccount(null);
      return null;
    }
    try {
      const a = await auth.me();
      setAccount(a);
      return a;
    } catch {
      return null;
    }
  }, []);

  const subject = claims?.sub ?? null;
  useEffect(() => {
    if (subject) void reloadAccount();
    else setAccount(null);
  }, [subject, reloadAccount]);

  const value = useMemo<AuthState>(() => {
    const roles = (claims?.roles ?? []).filter((r): r is Role => ['DRIVER', 'HOST', 'ADMIN'].includes(r));
    return {
      loggedIn: !!tokens,
      accountId: claims?.sub ?? null,
      roles,
      account,
      accessToken: tokens?.accessToken ?? null,
      hasRole: (r) => roles.includes(r),
      reloadAccount,
      login: async (email, password) => {
        await auth.login(email, password);
      },
      logout: async () => {
        await auth.logout();
        setAccount(null);
      },
    };
  }, [tokens, claims, account, reloadAccount]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth outside AuthProvider');
  return v;
}
