import { useCallback, useEffect, useRef, useState } from 'react';
import type { Page } from '../api/types';

/** Cursor pagination: loads the first page when `deps` change, `more()` appends the next one. */
export function usePaged<T>(fetchPage: (cursor?: string) => Promise<Page<T>>, deps: unknown[] = []) {
  const [items, setItems] = useState<T[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;

  useEffect(() => {
    let alive = true;
    setLoading(true);
    fetchRef
      .current()
      .then((p) => {
        if (!alive) return;
        setItems(p.items);
        setNext(p.nextCursor ?? null);
        setError(null);
      })
      .catch((e) => alive && setError(e))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const more = useCallback(async () => {
    if (!next) return;
    setLoading(true);
    try {
      const p = await fetchRef.current(next);
      setItems((cur) => [...cur, ...p.items]);
      setNext(p.nextCursor ?? null);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [next]);

  return { items, next, error, loading, more, reload: () => setTick((t) => t + 1) };
}
