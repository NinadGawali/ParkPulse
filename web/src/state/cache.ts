import { useEffect, useState, useSyncExternalStore } from 'react';
import { listings, profile } from '../api/endpoints';
import type { Listing, PublicProfile } from '../api/types';

/**
 * Small per-tab caches for lookups that lists need once per row (a booking only carries the listing ID
 * and the other party's account ID). Failures resolve to null (e.g. an archived listing is 404 for a driver).
 */
const listingCache = new Map<string, Promise<Listing | null>>();
const profileCache = new Map<string, Promise<PublicProfile | null>>();

export function getListingBrief(id: string): Promise<Listing | null> {
  let p = listingCache.get(id);
  if (!p) {
    p = listings
      .get(id)
      .then((r) => r.data)
      .catch(() => null);
    listingCache.set(id, p);
  }
  return p;
}
let version = 0;
const listeners = new Set<() => void>();
function bump() {
  version++;
  listeners.forEach((l) => l());
}
function useVersion(): number {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => version,
  );
}

export function forgetListing(id: string) {
  listingCache.delete(id);
  bump();
}
/** Call after the user changed their own profile so headers and cards show the new name/photo. */
export function forgetProfile(id: string) {
  profileCache.delete(id);
  bump();
}

export function getPublicProfile(id: string): Promise<PublicProfile | null> {
  let p = profileCache.get(id);
  if (!p) {
    p = profile.publicOf(id).catch(() => null);
    profileCache.set(id, p);
  }
  return p;
}

function useCached<T>(id: string | null | undefined, get: (id: string) => Promise<T | null>): T | null | undefined {
  // undefined = loading, null = unavailable
  const [value, setValue] = useState<T | null | undefined>(undefined);
  const v = useVersion();
  useEffect(() => {
    if (!id) {
      setValue(null);
      return;
    }
    let alive = true;
    void get(id).then((v) => alive && setValue(v));
    return () => {
      alive = false;
    };
  }, [id, get, v]);
  return value;
}

export const useListingBrief = (id: string | null | undefined) => useCached(id, getListingBrief);
export const usePublicProfile = (id: string | null | undefined) => useCached(id, getPublicProfile);

/** Photo to show for a listing: the first READY one. */
export function coverPhoto(l: Pick<Listing, 'photos'> | null | undefined): string | null {
  return l?.photos.find((p) => p.status === 'READY' && p.url)?.url ?? null;
}
