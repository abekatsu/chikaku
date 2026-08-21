import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * 本番では Worker が同じオリジンで静的ファイルと API の両方を配るため、
 * クライアントは常に相対パス `/api/v1/...` を叩く (ADR-2)。
 *
 * 開発時だけ、その API をローカルの `wrangler dev` に転送する。
 * Cloudflare Access は本番でしか間に入らないので、
 * `scripts/dev.mjs` が用意した検証可能な JWT をここで付け足す。
 * （付けないと Worker 側の Access 検証で 401 になる）
 */
export default defineConfig(() => {
  const devJwt = process.env["CHIKAKU_DEV_JWT"];
  const workerOrigin = process.env["CHIKAKU_WORKER_ORIGIN"] ?? "http://127.0.0.1:8787";

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target: workerOrigin,
          changeOrigin: true,
          configure(proxy) {
            proxy.on("proxyReq", (proxyReq) => {
              if (devJwt) proxyReq.setHeader("Cf-Access-Jwt-Assertion", devJwt);
            });
          },
        },
      },
    },
    build: {
      // wrangler.jsonc の assets.directory がここを指している。
      outDir: "dist",
      sourcemap: true,
    },
  };
});
