import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `npm run dev` serves the web app on :5173 and passes the API to a server on :8080.
export default defineConfig({
  plugins: [react()],
  build: { outDir: "dist", sourcemap: false, chunkSizeWarningLimit: 900 },
  server: {
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: false },
    },
  },
});
