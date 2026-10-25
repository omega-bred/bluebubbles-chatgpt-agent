import axios, { AxiosError, type InternalAxiosRequestConfig } from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { signer, login } = vi.hoisted(() => ({
  signer: vi.fn(async (_url: string, _method: string, _nonce?: string) => ({ Authorization: "DPoP token", DPoP: crypto.randomUUID() })),
  login: vi.fn(),
}));
vi.mock("../auth/keycloak", () => ({ getAuthenticationHeaders: signer, login }));
vi.mock("./analytics", () => ({ trackEvent: vi.fn() }));

const requests: InternalAxiosRequestConfig[] = [];
let previousAdapter = axios.defaults.adapter;

beforeEach(() => {
  vi.stubGlobal("window", { location: { origin: "https://bluechat.example" } });
  requests.length = 0;
  signer.mockClear(); login.mockClear();
  previousAdapter = axios.defaults.adapter;
  axios.defaults.adapter = async config => {
    requests.push(config);
    return { config, data: {}, status: 200, statusText: "OK", headers: {} };
  };
  vi.resetModules();
});
afterEach(() => { axios.defaults.adapter = previousAdapter; vi.unstubAllGlobals(); });

describe("actual generated API consumers", () => {
  it("signs account, admin, billing and authenticated contact calls using their final methods and paths", async () => {
    const api = await import("./api-client");
    await api.websiteAccountApi.get();
    await api.adminApi.getStatistics("2026-10-01", "2026-10-24");
    await api.subscriptionApi.get();
    await api.contactApi.createMessage({ message: "test" } as never, true);
    expect(signer).toHaveBeenCalledTimes(4);
    for (const request of requests) {
      expect(request.headers.Authorization).toBe("DPoP token");
      expect(request.headers.DPoP).toBeTruthy();
    }
    expect(new Set(requests.map(request => request.headers.DPoP)).size).toBe(4);
    expect(signer.mock.calls[0][0]).toContain("/api/v1/websiteAccount/get.websiteAccount");
    expect(signer.mock.calls[3][1]).toBe("post");
  });

  it("leaves real public, App Clip and conversation settings calls on their existing authentication paths", async () => {
    const api = await import("./api-client");
    await api.contactApi.getConfig();
    await api.textingApi.getNumber();
    await api.appClipApi.createSession("one-time-link");
    await api.appClipApi.getSession("clip-session");
    await api.conversationSettingsApi.get("conversation-session");
    expect(signer).not.toHaveBeenCalled();
    requests.forEach(request => {
      expect(request.headers.Authorization).toBeUndefined();
      expect(request.headers.DPoP).toBeUndefined();
    });
    expect(requests[3].headers["X-App-Clip-Session"]).toBe("clip-session");
    expect(requests[4].headers["X-App-Clip-Session"]).toBe("conversation-session");
    expect(login).not.toHaveBeenCalled();
  });

  it("does not start a website login when a scoped App Clip session expires", async () => {
    axios.defaults.adapter = async config => {
      throw new AxiosError("expired", "ERR_BAD_REQUEST", config, undefined,
        { config, data: {}, status: 401, statusText: "Unauthorized", headers: {} });
    };
    const api = await import("./api-client");
    await expect(api.appClipApi.getSession("expired-session")).rejects.toBeInstanceOf(AxiosError);
    expect(login).not.toHaveBeenCalled();
    expect(signer).not.toHaveBeenCalled();
  });
});
