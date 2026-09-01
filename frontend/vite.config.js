import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Port 5173 is deliberate: it is one of the two origins the backend's CorsConfig allows.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },
});
