import { defineConfig } from "vite";
import solid from "vite-plugin-solid";

export default defineConfig(({ command }) => ({
  base:
    command === "serve"
      ? "/"
      : (process.env.EACL_DATAHIKE_DEMO_BASE_PATH ?? "/datahike/"),
  plugins: [solid()],
  server: {
    port: 5173,
    proxy: {
      "/api": process.env.EACL_DATAHIKE_DEMO_API_ORIGIN ?? "http://127.0.0.1:8088",
    },
  },
  build: {
    outDir: "../server/resources/public",
    emptyOutDir: true,
    sourcemap: false,
  },
}));
