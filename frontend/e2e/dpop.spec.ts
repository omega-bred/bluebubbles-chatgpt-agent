import { test, expect, type Page } from "@playwright/test";

const endpoint = "/api/v1/websiteAccount/get.websiteAccount";
const claims = (jwt: string) => JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString());
async function account(page: Page) {
  return page.evaluate(async () => {
    const { websiteAccountApi } = await import("/src/services/api-client.ts");
    return websiteAccountApi.get();
  });
}

test("real Keycloak code binding, API proof validation, refresh, reload, tabs and logout", async ({ page, context }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Log in", exact: true }).click();
  await page.waitForURL(/protocol\/openid-connect\/auth/);
  const authorization = new URL(page.url());
  expect(authorization.searchParams.get("dpop_jkt")).toBeTruthy();
  expect(authorization.searchParams.get("code_challenge_method")).toBe("S256");
  await page.locator("#username").fill("dpop-test");
  await page.locator("#password").fill(process.env.BLUECHAT_DPOP_E2E_PASSWORD!);
  await page.locator("#kc-login").click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  let requestPromise = page.waitForRequest(request => request.url().endsWith(endpoint));
  expect((await account(page)).account.subject).toBeTruthy();
  const original = await requestPromise;
  const headers = await original.allHeaders();
  expect(headers.authorization).toMatch(/^DPoP /);
  expect(headers.dpop).toBeTruthy();
  expect(claims(headers.authorization.slice(5)).cnf.jkt).toBe(authorization.searchParams.get("dpop_jkt"));

  // The resource verifier must reject bound-as-bearer and an identical proof replay.
  const bearer = await page.request.get(original.url(), { headers: { Authorization: `Bearer ${headers.authorization.slice(5)}` } });
  expect(bearer.status()).toBe(401);
  const missing = await page.request.get(original.url(), { headers: { Authorization: headers.authorization } });
  expect(missing.status()).toBe(401);
  const replay = await page.request.get(original.url(), { headers: { Authorization: headers.authorization, DPoP: headers.dpop } });
  expect(replay.status()).toBe(401);

  await page.reload();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  requestPromise = page.waitForRequest(request => request.url().endsWith(endpoint));
  await account(page);
  const afterReload = await (await requestPromise).allHeaders();
  expect(claims(afterReload.authorization.slice(5)).cnf.jkt).toBe(authorization.searchParams.get("dpop_jkt"));
  expect(afterReload.dpop).not.toBe(headers.dpop);

  const refreshToken = await page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(key => key.includes("user:") && key.includes("bbagent-web"))!;
    return JSON.parse(sessionStorage.getItem(key)!).refresh_token as string;
  });
  const tokenEndpoint = `${process.env.BLUECHAT_DPOP_E2E_ISSUER}/protocol/openid-connect/token`;
  const missingRefresh = await page.request.post(tokenEndpoint, { form: {
    grant_type: "refresh_token", client_id: "bbagent-web", refresh_token: refreshToken,
  } });
  expect(missingRefresh.status()).toBe(400);
  const wrongRefreshProof = await page.evaluate(async target => {
    const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
    const encode = (value: string) => btoa(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    const header = encode(JSON.stringify({ alg: "ES256", typ: "dpop+jwt", jwk: await crypto.subtle.exportKey("jwk", keys.publicKey) }));
    const body = encode(JSON.stringify({ jti: crypto.randomUUID(), iat: Math.floor(Date.now() / 1000), htm: "POST", htu: target }));
    const input = `${header}.${body}`;
    const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, keys.privateKey, new TextEncoder().encode(input));
    return `${input}.${encode(String.fromCharCode(...new Uint8Array(signature)))}`;
  }, tokenEndpoint);
  const wrongRefresh = await page.request.post(tokenEndpoint, { form: {
    grant_type: "refresh_token", client_id: "bbagent-web", refresh_token: refreshToken,
  }, headers: { DPoP: wrongRefreshProof } });
  expect(wrongRefresh.status()).toBe(400);

  const refreshRequest = page.waitForRequest(request => request.postData()?.includes("grant_type=refresh_token") ?? false);
  await page.evaluate(async () => { const auth = await import("/src/auth/keycloak.ts"); await auth.updateToken(3600); });
  const refreshHeaders = await (await refreshRequest).allHeaders();
  expect(refreshHeaders.dpop).toBeTruthy();
  expect(claims(refreshHeaders.dpop).htm).toBe("POST");
  expect(claims(refreshHeaders.dpop).ath).toBeUndefined();
  expect((await account(page)).account.subject).toBeTruthy();

  const second = await context.newPage();
  await second.goto("/");
  await second.getByRole("button", { name: "Log in", exact: true }).click();
  await expect(second.getByRole("button", { name: "Sign out" })).toBeVisible(); // Existing SSO cookie.
  const secondRequest = second.waitForRequest(request => request.url().endsWith(endpoint));
  await account(second);
  const secondHeaders = await (await secondRequest).allHeaders();
  expect(claims(secondHeaders.authorization.slice(5)).cnf.jkt).toBe(authorization.searchParams.get("dpop_jkt"));

  await second.getByRole("button", { name: "Sign out" }).click();
  await expect(second.getByRole("button", { name: "Log in", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Log in", exact: true })).toBeVisible();
  const keys = await second.evaluate(async (issuer) => {
    const { ScopedDPoPStore } = await import("/src/auth/dpop-session.ts");
    return new ScopedDPoPStore(`bluechat.oidc:${issuer}:bbagent-web:`).getAllKeys();
  }, process.env.BLUECHAT_DPOP_E2E_ISSUER);
  expect(keys).toHaveLength(0);
});
