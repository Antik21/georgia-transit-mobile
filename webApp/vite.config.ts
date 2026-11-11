import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "TRANSIT_");
  const bffOrigin = env.TRANSIT_BFF_ORIGIN ?? "https://antik21-georgia-transit-bff.onrender.com";

  return {
    base: "./",
    resolve: {
      alias: {
        "@georgia-transit/shared": fileURLToPath(new URL(
          "../build/js/packages/georgia-transit-shared/kotlin/georgia-transit-shared.mjs", import.meta.url,
        )),
      },
    },
    server: {
      fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
      proxy: {
        "/v1/": { target: bffOrigin, changeOrigin: true },
      },
    },
  };
});
