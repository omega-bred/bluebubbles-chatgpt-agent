import Keycloak from "keycloak-js";

export const ADMIN_ROLE = "bbagent-admin-role";

export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || "https://keycloak.bre.land",
  realm: import.meta.env.VITE_KEYCLOAK_REALM || "bbagent",
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || "bbagent-web",
});

let initPromise: Promise<boolean> | null = null;

export function initKeycloak(): Promise<boolean> {
  if (!initPromise) {
    initPromise = keycloak.init({
      onLoad: "check-sso",
      pkceMethod: "S256",
      checkLoginIframe: false,
    });
  }
  return initPromise;
}

export function login(): Promise<void> {
  return keycloak.login({ redirectUri: window.location.href });
}

export function register(): Promise<void> {
  return keycloak.register({ redirectUri: window.location.href });
}

export function logout(): Promise<void> {
  return keycloak.logout({ redirectUri: window.location.origin });
}

export async function getAccessToken(): Promise<string> {
  if (!keycloak.authenticated) {
    await login();
    throw new Error("Not authenticated");
  }
  await keycloak.updateToken(30);
  if (!keycloak.token) {
    throw new Error("Missing access token");
  }
  return keycloak.token;
}

export function hasRole(role: string): boolean {
  return tokenRoles().includes(role);
}

export function hasAdminRole(): boolean {
  return hasRole(ADMIN_ROLE);
}

function tokenRoles(): string[] {
  const parsed = keycloak.tokenParsed as Record<string, unknown> | undefined;
  if (!parsed) {
    return [];
  }
  const roles = new Set<string>();
  addRoles((parsed.realm_access as Record<string, unknown> | undefined)?.roles, roles);
  const resourceAccess = parsed.resource_access as Record<string, unknown> | undefined;
  if (resourceAccess) {
    Object.values(resourceAccess).forEach((value) => {
      addRoles((value as Record<string, unknown> | undefined)?.roles, roles);
    });
  }
  return Array.from(roles);
}

function addRoles(value: unknown, roles: Set<string>) {
  if (!Array.isArray(value)) {
    return;
  }
  value.forEach((role) => {
    if (typeof role === "string" && role.trim()) {
      roles.add(role.trim());
    }
  });
}
