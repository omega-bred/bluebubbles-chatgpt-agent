import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from "axios";
import { describe, expect, it, vi } from "vitest";
import { createDpopTransport } from "./dpop-transport";

const url = "https://bluechat.example/api/v1/websiteAccount/get.websiteAccount";
const origin = "https://bluechat.example";

function challenge(config: InternalAxiosRequestConfig, nonce = "resource-nonce") {
  return new AxiosError("nonce required", "ERR_BAD_REQUEST", config, undefined, {
    data: {}, status: 401, statusText: "Unauthorized", config,
    headers: new AxiosHeaders({ "dpop-nonce": nonce, "www-authenticate": 'DPoP error="use_dpop_nonce"' }),
  });
}

describe("generated-client DPoP transport", () => {
  it("signs each final URL and method and retries a nonce challenge with a fresh proof once", async () => {
    const signer = vi.fn(async (_url, _method, _nonce) => ({ Authorization: "DPoP token", DPoP: `proof-${signer.mock.calls.length}` }));
    const unauthorized = vi.fn();
    const client = createDpopTransport("", origin, signer, unauthorized);
    let attempts = 0;
    client.defaults.adapter = (async config => {
      attempts++;
      expect(config.headers.Authorization).toBe("DPoP token");
      expect(config.headers.DPoP).toBe(`proof-${attempts}`);
      if (attempts === 1) throw challenge(config);
      return { config, status: 200, statusText: "OK", data: { ok: true }, headers: {} };
    }) as AxiosAdapter;
    expect((await client.get(url, { params: { page: 2 } })).data).toEqual({ ok: true });
    expect(signer.mock.calls[0]).toEqual([`${url}?page=2`, "get", undefined]);
    expect(signer.mock.calls[1]).toEqual([`${url}?page=2`, "get", "resource-nonce"]);
    expect(unauthorized).not.toHaveBeenCalled();
  });

  it("bounds nonce retries and sends ordinary failures to reauthentication", async () => {
    const unauthorized = vi.fn();
    const signer = vi.fn(async () => ({ Authorization: "DPoP token", DPoP: crypto.randomUUID() }));
    const client = createDpopTransport("", origin, signer, unauthorized);
    client.defaults.adapter = async config => { throw challenge(config); };
    await expect(client.get(url)).rejects.toBeInstanceOf(AxiosError);
    expect(signer).toHaveBeenCalledTimes(2);
    expect(unauthorized).toHaveBeenCalledOnce();
  });

  it("never signs a request to another host or a non-API path", async () => {
    const signer = vi.fn();
    const client = createDpopTransport("", origin, signer, vi.fn());
    await expect(client.get("https://attacker.example/api/v1/test")).rejects.toThrow("Refusing");
    await expect(client.get("/untrusted/path")).rejects.toThrow("Refusing");
    expect(signer).not.toHaveBeenCalled();
  });

});
