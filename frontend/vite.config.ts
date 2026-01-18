import react from "@vitejs/plugin-react";
import {defineConfig} from "vite";

import vitePluginInjectDataLocator from "./plugins/vite-plugin-inject-data-locator";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), vitePluginInjectDataLocator()],
  server: {
    // this makes Vite respect the incoming Host/Origin
    origin: 'http://localhost:8080',
    hmr: {
      protocol: 'ws',
      host: 'localhost',
      port: 5174, // match your proxy
    },
    cors: true, // just in case
  },
  // server: {
  //   allowedHosts: true,
  // },

});
