import { beforeEach, describe, expect, it } from 'vitest';
import { tokenStore } from '../api';

describe('session token store', () => {
  beforeEach(() => {
    localStorage.clear();
    tokenStore.set(null);
  });

  it('keeps the access token in memory only and persists just the refresh token', () => {
    tokenStore.set({ accessToken: 'access.jwt.value', refreshToken: 'refresh-token-1234567890' });
    expect(tokenStore.get()).toEqual({ accessToken: 'access.jwt.value', refreshToken: 'refresh-token-1234567890' });
    const stored = JSON.stringify(Object.fromEntries(Object.entries(localStorage)));
    expect(stored).toContain('refresh-token-1234567890');
    expect(stored).not.toContain('access.jwt.value');
  });

  it('migrates the old console format, dropping the stored access token', () => {
    localStorage.setItem('parksmart.tokens', JSON.stringify({ accessToken: 'old.access', refreshToken: 'old-refresh-xxxxxxxxxx' }));
    tokenStore.set(null);
    localStorage.setItem('parksmart.tokens', JSON.stringify({ accessToken: 'old.access', refreshToken: 'old-refresh-xxxxxxxxxx' }));
    expect(tokenStore.get()).toEqual({ accessToken: '', refreshToken: 'old-refresh-xxxxxxxxxx' });
    expect(localStorage.getItem('parksmart.tokens')).toBeNull();
  });

  it('logging out clears everything', () => {
    tokenStore.set({ accessToken: 'a.b.c', refreshToken: 'refresh-token-1234567890' });
    tokenStore.set(null);
    expect(tokenStore.get()).toBeNull();
  });
});
