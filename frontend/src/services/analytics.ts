type AnalyticsValue = string | number | boolean | null | undefined;
export type AnalyticsData = Record<string, AnalyticsValue>;

type UmamiTracker = {
  track: (eventName?: string | object, data?: Record<string, unknown>) => void;
  identify?: (idOrData: string | Record<string, unknown>, data?: Record<string, unknown>) => void;
};

declare global {
  interface Window {
    umami?: UmamiTracker;
  }
}

const maxEventNameLength = 50;
const maxStringLength = 500;
const retryDelayMs = 1_000;

export function trackEvent(name: string, data: AnalyticsData = {}) {
  const eventName = truncate(name, maxEventNameLength);
  const eventData = cleanData(data);
  withUmami((umami) => umami.track(eventName, eventData));
}

export function trackPageView(path: string, data: AnalyticsData = {}) {
  trackEvent("web_page_view", {
    path: safePath(path),
    ...data,
  });
}

export async function identifyAuthenticatedSession(
  subject: string | null | undefined,
  data: AnalyticsData = {},
) {
  const cleanSubject = typeof subject === "string" ? subject.trim() : "";
  if (!cleanSubject) {
    return;
  }
  const id = await distinctId(`keycloak:${cleanSubject}`);
  withUmami((umami) => {
    if (typeof umami.identify === "function") {
      umami.identify({ id, ...cleanData(data) });
    }
  });
}

function withUmami(callback: (umami: UmamiTracker) => void) {
  try {
    if (window.umami) {
      callback(window.umami);
      return;
    }
    window.setTimeout(() => {
      if (window.umami) {
        callback(window.umami);
      }
    }, retryDelayMs);
  } catch {
    // Analytics must not affect the product flow.
  }
}

function cleanData(data: AnalyticsData): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(data)
      .filter(([, value]) => value !== undefined && value !== null)
      .map(([key, value]) => [key, cleanValue(value)]),
  );
}

function cleanValue(value: AnalyticsValue): string | number | boolean {
  if (typeof value === "string") {
    return truncate(value, maxStringLength);
  }
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : 0;
  }
  return Boolean(value);
}

function safePath(path: string): string {
  try {
    return new URL(path, window.location.origin).pathname;
  } catch {
    return window.location.pathname;
  }
}

function truncate(value: string, maxLength: number): string {
  return value.length > maxLength ? value.slice(0, maxLength) : value;
}

async function distinctId(value: string): Promise<string> {
  if (window.crypto?.subtle) {
    const bytes = await window.crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
    return Array.from(new Uint8Array(bytes))
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("")
      .slice(0, 50);
  }
  return fallbackHash(value);
}

function fallbackHash(value: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return `fallback-${(hash >>> 0).toString(16)}`;
}
