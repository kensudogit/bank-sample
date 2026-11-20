import { useCallback, useEffect, useReducer } from "react";

/**
 * Minimal stale-while-revalidate cache for GET requests (in memory only, never persisted).
 *
 * - Pages show the last known data immediately on navigation and refresh it in the background.
 * - Concurrent requests for the same key share one in-flight request.
 * - Mutations call `invalidate` so balances shown on screen are re-fetched right away.
 * - Data is re-checked when the tab becomes visible again.
 */

/** Data younger than this is shown without re-fetching. */
const FRESH_MS = 5_000;

type Fetcher<T> = () => Promise<T>;

interface Entry<T> {
  data?: T;
  error?: unknown;
  fetchedAt: number;
  promise?: Promise<T>;
  fetcher?: Fetcher<T>;
  listeners: Set<() => void>;
}

const cache = new Map<string, Entry<unknown>>();

function entry<T>(key: string): Entry<T> {
  let e = cache.get(key) as Entry<T> | undefined;
  if (!e) {
    e = { fetchedAt: 0, listeners: new Set() };
    cache.set(key, e as Entry<unknown>);
  }
  return e;
}

function notify(e: Entry<unknown>) {
  e.listeners.forEach((l) => l());
}

function isFresh(e: Entry<unknown>) {
  return e.data !== undefined && Date.now() - e.fetchedAt < FRESH_MS;
}

/** Starts (or joins) a request for `key`. A request superseded by `invalidate` never overwrites newer data. */
function revalidate<T>(key: string): Promise<T> | undefined {
  const e = entry<T>(key);
  if (e.promise) return e.promise;
  if (!e.fetcher) return undefined;
  const p: Promise<T> = e.fetcher().then(
    (data) => {
      if (e.promise === p) {
        e.data = data;
        e.error = undefined;
        e.fetchedAt = Date.now();
      }
      return data;
    },
    (err: unknown) => {
      if (e.promise === p) e.error = err;
      throw err;
    },
  );
  e.promise = p;
  notify(e as Entry<unknown>);
  p.catch(() => undefined).finally(() => {
    if (e.promise === p) e.promise = undefined;
    notify(e as Entry<unknown>);
  });
  return p;
}

/** Warms the cache (e.g. on link hover) so the next page renders without waiting. */
export function prefetch<T>(key: string, fetcher: Fetcher<T>): void {
  const e = entry<T>(key);
  e.fetcher ??= fetcher;
  if (!isFresh(e as Entry<unknown>)) revalidate<T>(key)?.catch(() => undefined);
}

/**
 * Marks keys as stale. Keys currently on screen are re-fetched immediately; others on next use.
 * An in-flight request for the key is superseded, since it may have read data from before the change.
 */
export function invalidate(...keys: string[]): void {
  // "prefix*" matches every cached key starting with prefix ("*" alone matches everything)
  const matched = new Set<string>();
  for (const key of keys) {
    if (key.endsWith("*")) {
      const prefix = key.slice(0, -1);
      cache.forEach((_, k) => k.startsWith(prefix) && matched.add(k));
    } else if (cache.has(key)) {
      matched.add(key);
    }
  }
  matched.forEach((key) => {
    const e = cache.get(key)!;
    e.fetchedAt = 0;
    e.promise = undefined;
    if (e.listeners.size > 0) revalidate(key)?.catch(() => undefined);
  });
}

export interface QueryState<T> {
  data: T | undefined;
  error: unknown;
  /** true while a (background) request is running */
  loading: boolean;
  reload: () => void;
}

export function useQuery<T>(key: string, fetcher: Fetcher<T>): QueryState<T> {
  const [, rerender] = useReducer((n: number) => n + 1, 0);
  const e = entry<T>(key);
  // The fetcher is a pure function of the key; keep the first one registered for this key.
  e.fetcher ??= fetcher;

  useEffect(() => {
    const current = entry<T>(key);
    current.listeners.add(rerender);
    if (!isFresh(current as Entry<unknown>)) revalidate<T>(key)?.catch(() => undefined);
    rerender(); // key changed: show what the cache has for the new key
    return () => {
      current.listeners.delete(rerender);
    };
  }, [key]);

  const reload = useCallback(() => {
    revalidate<T>(key)?.catch(() => undefined);
  }, [key]);

  return { data: e.data, error: e.error, loading: e.promise !== undefined, reload };
}

// Refresh what is on screen when the user comes back to the tab (balances may have changed elsewhere).
if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible") return;
    cache.forEach((e, key) => {
      if (e.listeners.size > 0 && !isFresh(e)) revalidate(key)?.catch(() => undefined);
    });
  });
}
