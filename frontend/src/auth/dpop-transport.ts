import axios, { type InternalAxiosRequestConfig } from "axios";

type RetryConfig = InternalAxiosRequestConfig & { bluechatNonceRetried?: boolean };

export function createDpopTransport(
  basePath: string,
  origin: string,
  headers: (url: string, method: string, nonce?: string) => Promise<Record<string, string>>,
  onUnauthorized: () => void,
) {
  const client = axios.create();
  const nonces = new Map<string, string>();
  const apiOrigin = new URL(basePath || origin, origin).origin;
  client.interceptors.request.use(async config => {
    const url = new URL(client.getUri(config), origin);
    if (url.origin !== apiOrigin || !url.pathname.startsWith("/api/v1/")) {
      throw new Error("Refusing to send account credentials outside the configured API");
    }
    const auth = await headers(url.href, config.method || "GET", nonces.get(url.origin));
    Object.entries(auth).forEach(([name, value]) => config.headers.set(name, value));
    return config;
  });
  client.interceptors.response.use(response => {
    const nonce = response.headers["dpop-nonce"];
    if (nonce) nonces.set(apiOrigin, nonce);
    return response;
  }, async error => {
    const config = error.config as RetryConfig | undefined;
    const nonce = error.response?.headers?.["dpop-nonce"];
    const challenge = error.response?.headers?.["www-authenticate"] || "";
    if (error.response?.status === 401 && config && nonce && !config.bluechatNonceRetried
      && /\bDPoP\b/i.test(challenge) && /error="use_dpop_nonce"/i.test(challenge)) {
      nonces.set(apiOrigin, nonce);
      config.bluechatNonceRetried = true;
      return client.request(config); // Interceptor generates a new proof/jti, never reuses one.
    }
    if (error.response?.status === 401) onUnauthorized();
    return Promise.reject(error);
  });
  return client;
}
