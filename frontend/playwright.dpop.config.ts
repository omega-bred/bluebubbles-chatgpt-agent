import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e", testMatch: "dpop.spec.ts", workers: 1, retries: 0, timeout: 90_000,
  reporter: "list",
  // No traces, screenshots or storage-state files containing tokens/test credentials.
  use: { baseURL: "http://localhost:15174", headless: true,
    launchOptions: process.env.BLUECHAT_DPOP_BROWSER_PATH
      ? { executablePath: process.env.BLUECHAT_DPOP_BROWSER_PATH } : {} },
  webServer: {
    command: "npm run dev -- --host localhost --port 15174 --strictPort",
    url: "http://localhost:15174", reuseExistingServer: false,
    env: {
      VITE_KEYCLOAK_URL: process.env.BLUECHAT_DPOP_E2E_ISSUER!.split("/realms/")[0],
      VITE_KEYCLOAK_REALM: "bluechat-dpop-test", VITE_KEYCLOAK_CLIENT_ID: "bbagent-web",
      VITE_API_URL: process.env.VITE_API_URL!,
    },
  },
});
