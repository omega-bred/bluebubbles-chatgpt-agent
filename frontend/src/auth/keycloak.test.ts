import { afterEach, expect, it, vi } from "vitest";

afterEach(() => vi.unstubAllGlobals());

it("keeps public pages loadable when the browser denies session storage", async () => {
  vi.resetModules();
  vi.stubGlobal("window", {
    location: { origin: "https://bluechat.example" },
    get sessionStorage() { throw new Error("Site storage is blocked"); },
  });
  const auth = await import("./keycloak");
  const unsubscribe = auth.subscribeAuth(() => {});
  expect(auth.isAuthenticated()).toBe(false);
  expect(auth.currentUserSubject()).toBeNull();
  await expect(auth.initKeycloak()).rejects.toThrow("Site storage is blocked");
  expect(auth.isAuthenticated()).toBe(false);
  unsubscribe();
});
