import { IDBFactory } from "fake-indexeddb";
import { InMemoryWebStorage, User } from "oidc-client-ts";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DpopSession, ScopedDPoPStore } from "./dpop-session";

const authority = "https://issuer.example/realms/bbagent";
const clientId = "bbagent-web";
let sharedStorage: InMemoryWebStorage;
let navigated: URL;

function browser() {
  const events = new EventTarget();
  const url = new URL("https://bluechat.example/account/link?token=one-time-link");
  const location = Object.assign(url, {
    assign: (value: string) => { navigated = new URL(value); events.dispatchEvent(new Event("pageshow")); },
    replace: (value: string) => { navigated = new URL(value); events.dispatchEvent(new Event("pageshow")); },
  });
  const value = {
    location, crypto, isSecureContext: true, indexedDB: globalThis.indexedDB,
    localStorage: sharedStorage, sessionStorage: new InMemoryWebStorage(),
    addEventListener: events.addEventListener.bind(events),
    removeEventListener: events.removeEventListener.bind(events),
    dispatchEvent: events.dispatchEvent.bind(events),
    history: { replaceState: (_state: unknown, _title: string, path: string) => { location.href = new URL(path, url.origin).href; } },
  };
  Object.assign(value, { self: value });
  vi.stubGlobal("window", value);
  return value;
}

function session() {
  const auth = new DpopSession(authority, clientId);
  vi.spyOn(auth.manager.metadataService, "getMetadata").mockResolvedValue({
    issuer: authority,
    authorization_endpoint: `${authority}/protocol/openid-connect/auth`,
    token_endpoint: `${authority}/protocol/openid-connect/token`,
    end_session_endpoint: `${authority}/protocol/openid-connect/logout`,
  });
  return auth;
}

const encode = (value: unknown) => btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const jwt = (claims: object) => `${encode({ alg: "RS256", typ: "JWT" })}.${encode(claims)}.test-signature`;
const decode = (value: string) => JSON.parse(atob(value.replace(/-/g, "+").replace(/_/g, "/")));

async function signedIn(auth: DpopSession, expiresIn = 300) {
  const jkt = await auth.manager.generateDPoPJkt(auth.manager.settings.dpop!);
  const now = Math.floor(Date.now() / 1000);
  const profile = { sub: "user-1", iss: authority, aud: clientId, iat: now, exp: now + 3600 };
  const user = new User({
    access_token: jwt({ ...profile, cnf: { jkt } }),
    id_token: jwt(profile), refresh_token: "refresh-test", token_type: "DPoP",
    expires_at: now + expiresIn, profile,
  });
  await auth.manager.storeUser(user);
  return user;
}

beforeEach(() => {
  sharedStorage = new InMemoryWebStorage();
  vi.stubGlobal("indexedDB", new IDBFactory());
  vi.stubGlobal("PopStateEvent", Event);
  let queue = Promise.resolve();
  vi.stubGlobal("navigator", { locks: { request: (_name: string, action: () => Promise<unknown>) => {
    const result = queue.then(action);
    queue = result.then(() => undefined, () => undefined);
    return result;
  } } });
  browser();
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("browser DPoP lifecycle", () => {
  it("uses non-extractable keys, verifies real ES256 proofs and persists keys across reloads", async () => {
    const auth = session();
    const user = await signedIn(auth);
    expect(await auth.init()).toBe(true);
    const headers = await auth.headers("https://bluechat.example/api/v1/test?x=1#fragment", "post");
    expect(headers.Authorization).toBe(`DPoP ${user.access_token}`);
    const [encodedHeader, encodedClaims, signature] = headers.DPoP.split(".");
    const header = decode(encodedHeader);
    const claims = decode(encodedClaims);
    expect(header.typ).toBe("dpop+jwt");
    expect(header.alg).toBe("ES256");
    expect(header.jwk.d).toBeUndefined();
    expect(claims).toMatchObject({ htm: "POST", htu: "https://bluechat.example/api/v1/test" });
    const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(user.access_token));
    expect(claims.ath).toBe(btoa(String.fromCharCode(...new Uint8Array(hash))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""));
    const publicKey = await crypto.subtle.importKey("jwk", header.jwk, { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"]);
    expect(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey,
      Uint8Array.from(atob(signature.replace(/-/g, "+").replace(/_/g, "/")), char => char.charCodeAt(0)), new TextEncoder().encode(`${encodedHeader}.${encodedClaims}`))).toBe(true);
    const state = await auth.store.get(clientId);
    expect(state.keys.privateKey.extractable).toBe(false);
    await expect(crypto.subtle.exportKey("jwk", state.keys.privateKey)).rejects.toThrow();
    const reloaded = session();
    expect(await reloaded.init()).toBe(true);
    const fresh = await reloaded.headers("https://bluechat.example/api/v1/test", "POST");
    expect(decode(fresh.DPoP.split(".")[0]).jwk).toEqual(header.jwk);
    expect(decode(fresh.DPoP.split(".")[1]).jti).not.toBe(claims.jti);
  });

  it("binds authorization codes to the proof key, uses PKCE and preserves the account-link URL", async () => {
    const auth = session();
    await auth.login();
    const jkt = await auth.manager.generateDPoPJkt(auth.manager.settings.dpop!);
    expect(navigated.searchParams.get("dpop_jkt")).toBe(jkt);
    expect(navigated.searchParams.get("code_challenge_method")).toBe("S256");
    expect(navigated.searchParams.get("code_challenge")).toBeTruthy();
    expect(navigated.searchParams.get("redirect_uri")).toBe("https://bluechat.example/auth/callback");
    const state = navigated.searchParams.get("state");
    const user = await signedIn(auth);
    const fetcher = vi.fn(async (_url: string, options: RequestInit) => {
      expect(new URLSearchParams(options.body as string).get("grant_type")).toBe("authorization_code");
      const proof = decode((options.headers as Record<string, string>).DPoP.split(".")[1]);
      expect(proof).toMatchObject({ htm: "POST", htu: `${authority}/protocol/openid-connect/token` });
      expect(proof.ath).toBeUndefined();
      return new Response(JSON.stringify({ access_token: user.access_token, id_token: user.id_token,
        refresh_token: "refresh-next", expires_in: 300, token_type: "DPoP" }),
      { headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetcher);
    window.location.href = `https://bluechat.example/auth/callback?code=test-code&state=${state}`;
    expect(await auth.init()).toBe(true);
    expect(window.location.pathname + window.location.search).toBe("/account/link?token=one-time-link");
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("opens the Keycloak registration flow with prompt=create", async () => {
    await session().login(true);
    expect(navigated.searchParams.get("prompt")).toBe("create");
    expect(navigated.searchParams.get("dpop_jkt")).toBeTruthy();
  });

  it("signs refresh requests with the same key and handles an authorization-server nonce once", async () => {
    const auth = session();
    const user = await signedIn(auth, 10);
    const proofs: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_url: string, options: RequestInit) => {
      expect(new URLSearchParams(options.body as string).get("grant_type")).toBe("refresh_token");
      proofs.push((options.headers as Record<string, string>).DPoP);
      if (proofs.length === 1) return new Response(JSON.stringify({ error: "use_dpop_nonce" }),
        { status: 400, headers: { "Content-Type": "application/json", "DPoP-Nonce": "issuer-nonce" } });
      return new Response(JSON.stringify({ access_token: user.access_token, id_token: user.id_token,
        refresh_token: "refresh-next", expires_in: 300, token_type: "DPoP" }),
      { headers: { "Content-Type": "application/json" } });
    }));
    const result = await auth.headers("https://bluechat.example/api/v1/test", "GET");
    expect(proofs).toHaveLength(2);
    expect(decode(proofs[1].split(".")[1]).nonce).toBe("issuer-nonce");
    expect(decode(proofs[1].split(".")[1]).jti).not.toBe(decode(proofs[0].split(".")[1]).jti);
    expect(decode(result.DPoP.split(".")[0]).jwk).toEqual(decode(proofs[1].split(".")[0]).jwk);
    expect(decode(result.DPoP.split(".")[1]).nonce).toBeUndefined(); // AS nonce is not an API nonce.
  });

  it("shares one proof key across simultaneous tab logins and isolates issuers", async () => {
    const first = session();
    const second = session();
    await Promise.all([first.login(), second.login()]);
    expect(await first.manager.generateDPoPJkt(first.manager.settings.dpop!))
      .toBe(await second.manager.generateDPoPJkt(second.manager.settings.dpop!));
    const other = new ScopedDPoPStore("another-issuer:");
    expect(await other.get(clientId)).toBeUndefined();
  });

  it("cleans up keys and PKCE state at logout and invalidates another suspended tab", async () => {
    const firstWindow = window;
    const first = session();
    const user = await signedIn(first);
    const oldKey = await first.manager.generateDPoPJkt(first.manager.settings.dpop!);
    const secondWindow = browser();
    const second = session();
    await second.manager.storeUser(user);
    await second.manager.settings.stateStore.set("pending-pkce", "verifier");
    await second.logout();
    expect(await second.store.get(clientId)).toBeUndefined();
    expect(await second.manager.getUser()).toBeNull();
    expect(await second.manager.settings.stateStore.get("pending-pkce")).toBeFalsy();
    await second.login(); // New login before the suspended tab resumes must keep its new key.
    const newKey = await second.manager.generateDPoPJkt(second.manager.settings.dpop!);
    expect(newKey).not.toBe(oldKey);
    vi.stubGlobal("window", firstWindow);
    await expect(first.headers("https://bluechat.example/api/v1/test", "GET")).rejects.toThrow("Sign in");
    expect(await first.manager.getUser()).toBeNull();
    vi.stubGlobal("window", secondWindow);
    expect(await second.manager.generateDPoPJkt(second.manager.settings.dpop!)).toBe(newKey);
  });

  it.each(["missing", "changed", "bearer"])("requires reauthentication for a %s key/session", async mode => {
    const auth = session();
    const user = await signedIn(auth);
    if (mode === "bearer") { user.token_type = "Bearer"; await auth.manager.storeUser(user); }
    else {
      await auth.store.remove(clientId);
      if (mode === "changed") await auth.manager.generateDPoPJkt(auth.manager.settings.dpop!);
    }
    await expect(auth.headers("https://bluechat.example/api/v1/test", "GET")).rejects.toThrow();
    expect(await auth.manager.getUser()).toBeNull();
  });

  it("fails closed when persistent key storage or a secure context is unavailable", async () => {
    const auth = session();
    vi.spyOn(auth.store, "get").mockRejectedValue(new Error("storage denied"));
    await expect(auth.login()).rejects.toThrow("storage denied");
    Object.assign(window, { isSecureContext: false });
    await expect(auth.login()).rejects.toThrow("Secure browser storage");
  });
});
