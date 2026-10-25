import { IndexedDbDPoPStore, UserManager, WebStorageStateStore, type DPoPState, type User } from "oidc-client-ts";

// oidc-client-ts keys its default store by client_id alone. Isolate issuers as well.
export class ScopedDPoPStore {
  private readonly store = new IndexedDbDPoPStore();
  constructor(private readonly scope: string) {}
  set(key: string, value: DPoPState) { return this.store.set(this.scope + key, value); }
  get(key: string) { return this.store.get(this.scope + key); }
  remove(key: string) { return this.store.remove(this.scope + key); }
  async getAllKeys() {
    return (await this.store.getAllKeys()).filter(key => key.startsWith(this.scope))
      .map(key => key.slice(this.scope.length));
  }
}

export class DpopSession {
  readonly manager: UserManager;
  readonly store: ScopedDPoPStore;
  private readonly namespace: string;
  private readonly userKey: string;
  private current: User | null = null;
  private listeners = new Set<() => void>();

  constructor(authority: string, clientId: string) {
    this.namespace = `bluechat.oidc:${authority}:${clientId}:`;
    this.userKey = `user:${authority}:${clientId}`;
    this.store = new ScopedDPoPStore(this.namespace);
    this.manager = new UserManager({
      authority, client_id: clientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
      response_type: "code", response_mode: "query", scope: "openid profile email",
      // Tokens stay in this tab's session storage; non-extractable private CryptoKeys
      // stay in IndexedDB. There is deliberately no in-memory/bearer fallback.
      userStore: new WebStorageStateStore({ store: window.sessionStorage, prefix: this.namespace + "users." }),
      stateStore: new WebStorageStateStore({ store: window.sessionStorage, prefix: this.namespace + "states." }),
      dpop: { bind_authorization_code: true, store: this.store },
      automaticSilentRenew: false, monitorSession: false, loadUserInfo: false,
    });
    window.addEventListener("storage", event => {
      if (event.key === this.namespace + "logout") {
        void this.lock(async () => { await this.syncLogout(); });
      }
    });
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  get user() { return this.current; }

  async init(): Promise<boolean> {
    return this.lock(async () => {
      await this.syncLogout();
      await this.manager.clearStaleState();
      if (window.location.pathname === "/auth/callback") {
        let user: User;
        try {
          user = await this.manager.signinRedirectCallback(window.location.href);
          await this.checkBinding(user);
        } catch (error) {
          await this.clearTab();
          throw error;
        }
        this.setUser(user);
        const returnTo = (user.state as { returnTo?: string } | undefined)?.returnTo;
        window.history.replaceState({}, "", returnTo?.startsWith("/") && !returnTo.startsWith("//")
          ? returnTo : "/account");
        window.dispatchEvent(new PopStateEvent("popstate"));
      } else {
        const user = await this.manager.getUser();
        if (user) {
          try {
            await this.checkBinding(user);
            this.setUser(user);
            if ((user.expires_in ?? 0) < 45) await this.refresh();
          } catch {
            await this.clearTab();
          }
        }
      }
      return !!this.current;
    });
  }

  async login(register = false): Promise<void> {
    return this.lock(async () => {
      await this.syncLogout();
      await this.manager.signinRedirect({
        state: { returnTo: window.location.pathname === "/auth/callback" ? "/account"
          : window.location.pathname + window.location.search + window.location.hash },
        ...(register ? { prompt: "create" } : {}),
      });
    });
  }

  async logout(): Promise<void> {
    return this.lock(async () => {
      const user = await this.manager.getUser();
      // Clear locally even when the identity provider cannot be reached. removeUser also
      // deletes this issuer/client's proof key. Other tabs clear their own tokens.
      await this.manager.removeUser();
      await this.clearTab();
      const generation = crypto.randomUUID();
      window.localStorage.setItem(this.namespace + "logout", generation);
      window.sessionStorage.setItem(this.namespace + "logout", generation);
      this.setUser(null);
      await this.manager.signoutRedirect({ id_token_hint: user?.id_token });
    });
  }

  async updateToken(minValidity = 45): Promise<void> {
    return this.lock(async () => {
      await this.syncLogout();
      const user = await this.manager.getUser();
      if (!user) { this.setUser(null); return; }
      try {
        await this.checkBinding(user);
        this.setUser(user);
        if ((user.expires_in ?? 0) < minValidity) await this.refresh();
      } catch (error) {
        await this.clearTab();
        throw error;
      }
    });
  }

  async headers(url: string, method: string, nonce?: string): Promise<Record<string, string>> {
    return this.lock(async () => {
      await this.syncLogout();
      let user = await this.manager.getUser();
      if (!user) throw new Error("Sign in to continue");
      try {
        await this.checkBinding(user);
        if ((user.expires_in ?? 0) < 30) user = await this.refresh();
        this.setUser(user);
        const target = new URL(url);
        target.search = "";
        target.hash = "";
        const proof = await this.manager.dpopProof(target.href, user, method.toUpperCase(), nonce);
        if (!proof) throw new Error("DPoP proof key is unavailable. Sign in again.");
        return { Authorization: `DPoP ${user.access_token}`, DPoP: proof };
      } catch (error) {
        await this.clearTab();
        throw error;
      }
    });
  }

  private async refresh(): Promise<User> {
    const user = await this.manager.signinSilent(); // Library signs refresh, retries AS nonce once.
    if (!user) throw new Error("Session expired. Sign in again.");
    await this.checkBinding(user);
    this.setUser(user);
    return user;
  }

  private async checkBinding(user: User) {
    if (user.token_type.toLowerCase() !== "dpop" || !(await this.store.get(this.manager.settings.client_id))) {
      throw new Error("A DPoP session is required. Sign in again.");
    }
    const claims = accessTokenClaims(user.access_token);
    const jkt = await this.manager.generateDPoPJkt(this.manager.settings.dpop!);
    if (!jkt || claims.cnf?.jkt !== jkt) throw new Error("Session proof key changed. Sign in again.");
  }

  private async syncLogout() {
    const generation = window.localStorage.getItem(this.namespace + "logout") || "";
    const previous = window.sessionStorage.getItem(this.namespace + "logout") || "";
    if (generation !== previous) {
      // A different tab may already have logged in with a new key: remove only tab tokens.
      await this.clearTab();
      window.sessionStorage.setItem(this.namespace + "logout", generation);
    }
  }

  private async clearTab() {
    await this.manager.settings.userStore.remove(this.userKey);
    // Includes pending PKCE verifiers/authorization state in this tab only.
    for (const key of await this.manager.settings.stateStore.getAllKeys()) {
      await this.manager.settings.stateStore.remove(key);
    }
    this.setUser(null);
  }

  private setUser(user: User | null) {
    this.current = user;
    this.listeners.forEach(listener => listener());
  }

  private lock<T>(action: () => Promise<T>): Promise<T> {
    if (!window.isSecureContext || !navigator.locks || !window.indexedDB) {
      return Promise.reject(new Error("Secure browser storage is required to sign in."));
    }
    // Serialize key creation/deletion and refresh across tabs.
    return navigator.locks.request(this.namespace, action);
  }
}

export function accessTokenClaims(token: string): {
  sub?: string; cnf?: { jkt?: string }; realm_access?: { roles?: string[] };
  resource_access?: Record<string, { roles?: string[] }>;
} {
  // Local UI/binding checks only. The API verifies signature, issuer and expiry.
  return JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
}
