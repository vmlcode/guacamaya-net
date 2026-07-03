import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import tsconfigPaths from "vite-tsconfig-paths";

// Plain client-side SPA build — no SSR/Nitro. This page has no server
// loaders or data-fetching, so a static build is all it needs; the compiled
// output is served by the backend's Fastify process (see backend/src/index.ts).
export default defineConfig({
  plugins: [react(), tailwindcss(), tsconfigPaths()],
  server: {
    port: 5173,
    proxy: {
      "/waitlist": "http://localhost:3000",
    },
  },
});
