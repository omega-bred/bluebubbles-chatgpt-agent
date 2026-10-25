import { accessTokenClaims, DpopSession } from "./dpop-session";

export const ADMIN_ROLE = "bbagent-admin-role";
const authority = `${(import.meta.env.VITE_KEYCLOAK_URL || "https://keycloak.bre.land").replace(/\/$/, "")}/realms/${import.meta.env.VITE_KEYCLOAK_REALM || "bbagent"}`;
let session: DpopSession | undefined;
const listeners = new Set<() => void>();
let initPromise: Promise<boolean> | null = null;

function getSession() {
  // Storage denial must disable sign-in without preventing public pages from loading.
  if (!session) {
    session = new DpopSession(authority, import.meta.env.VITE_KEYCLOAK_CLIENT_ID || "bbagent-web");
    session.subscribe(() => listeners.forEach(listener => listener()));
  }
  return session;
}

export function initKeycloak() { return initPromise ??= Promise.resolve().then(() => getSession().init()); }
export async function login() { return getSession().login(); }
export async function register() { return getSession().login(true); }
export async function logout() { return getSession().logout(); }
export async function updateToken(minValidity = 45) { return getSession().updateToken(minValidity); }
export function isAuthenticated() { return !!session?.user; }
export function subscribeAuth(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
export async function getAuthenticationHeaders(url: string, method: string, nonce?: string) {
  return getSession().headers(url, method, nonce);
}
export function currentUserSubject(): string | null { return session?.user?.profile.sub ?? null; }
export function hasAdminRole() {
  if (!session?.user) return false;
  const claims = accessTokenClaims(session.user.access_token);
  return [claims.realm_access, ...Object.values(claims.resource_access ?? {})]
    .some(access => access?.roles?.includes(ADMIN_ROLE));
}
