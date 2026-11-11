import { useSyncExternalStore } from 'react';
import type { DebugEntry } from '../api/client';

const MAX = 40;
let entries: DebugEntry[] = [];
const listeners = new Set<() => void>();

export function pushDebug(entry: DebugEntry): void {
  entries = [entry, ...entries].slice(0, MAX);
  listeners.forEach((l) => l());
}
export function clearDebug(): void {
  entries = [];
  listeners.forEach((l) => l());
}
function subscribe(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}
export function useDebugEntries(): DebugEntry[] {
  return useSyncExternalStore(subscribe, () => entries);
}
