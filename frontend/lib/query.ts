import { useCallback, useEffect, useReducer, useRef } from "react";

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
/** On returning to the tab, data older than this is re-fetched. */
const FOCUS_REFRESH_MS = 30_000;

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
/** Incremented by clearCache, so placeholders kept by mounted components are dropped too. */
let generation = 0;

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

/** Drops every cached response (logout / user switch: never show one user's data to the next). */
export function clearCache(): void {
  generation++;
  cache.forEach((e) => {
    e.data = undefined;
    e.error = undefined;
    e.fetchedAt = 0;
    e.promise = undefined;
  });
  cache.forEach((e) => notify(e));
}

export interface QueryState<T> {
  data: T | undefined;
  error: unknown;
  /** true while a (background) request is running */
  loading: boolean;
  /** true when `data` is the previous key's result, shown while the new key loads (keepPrevious) */
  placeholder: boolean;
  reload: () => void;
}

export interface QueryOptions {
  /**
   * Paged / filtered lists: when the key changes (next page, new filter), keep showing the previous result
   * until the new one arrives instead of flashing the loading skeleton. The value is a scope (e.g. the
   * account number): data is only carried over within the same scope, never to another account's list.
   */
  keepPreviousWithin?: string;
}

export function useQuery<T>(key: string, fetcher: Fetcher<T>, options: QueryOptions = {}): QueryState<T> {
  const [, rerender] = useReducer((n: number) => n + 1, 0);
  const e = entry<T>(key);
  const scope = options.keepPreviousWithin;
  const previous = useRef<{ data: T; generation: number; scope?: string } | undefined>(undefined);
  if (e.data !== undefined) previous.current = { data: e.data, generation, scope };
  // After clearCache (logout / user switch) the previous user's list must not be shown as a placeholder
  if (previous.current && previous.current.generation !== generation) previous.current = undefined;
  // Never keep showing old data next to an error for the new key, or for another scope
  const placeholder = scope !== undefined && e.data === undefined && e.error === undefined
    && previous.current !== undefined && previous.current.scope === scope;
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

  return {
    data: placeholder ? previous.current?.data : e.data,
    error: e.error,
    loading: e.promise !== undefined || placeholder,
    placeholder,
    reload,
  };
}

// Refresh what is on screen when the user comes back to the tab (balances may have changed elsewhere).
if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible") return;
    cache.forEach((e, key) => {
      // Tab switches can be frequent: only refresh data that is clearly old
      if (e.listeners.size > 0 && Date.now() - e.fetchedAt > FOCUS_REFRESH_MS) revalidate(key)?.catch(() => undefined);
    });
  });
}
