/**
 * Cloudflare Access の代役。
 *
 * 本番では Access が JWT を付けて Worker に転送するが、ローカルには
 * Access が居ない。そこで自前の RSA 鍵で JWKS を配るサーバーを立て、
 * Worker のチームドメインをそこへ向ける (`server/wrangler.test.jsonc`)。
 *
 * **検証を迂回させるのではなく、検証できる本物の JWT を用意する**のが要点。
 * これにより署名検証・aud・iss・exp の経路は本番と同じものが動く。
 *
 * e2e テストとローカル開発の両方から使う。
 */
import { createServer } from "node:http";
import { generateKeyPair, exportJWK, SignJWT } from "jose";

export const DEFAULT_AUDIENCE = "test-aud-tag";

/**
 * @param {{ port: number, audience?: string }} options
 */
export async function startAccessStub({ port, audience = DEFAULT_AUDIENCE }) {
  const { publicKey, privateKey } = await generateKeyPair("RS256", { extractable: true });
  const jwk = await exportJWK(publicKey);
  jwk.kid = "test-kid";
  jwk.alg = "RS256";
  jwk.use = "sig";
  const jwks = { keys: [jwk] };
  const issuer = `http://127.0.0.1:${port}`;

  const server = createServer((req, res) => {
    if (req.url === "/cdn-cgi/access/certs") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(jwks));
    } else {
      res.writeHead(404).end();
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", resolve);
  });

  /**
   * Access が発行するものと同じ形の JWT を作る。
   * 既定値から外した引数は、拒否されるべき JWT を組み立てるために使う。
   */
  const mintJwt = ({
    email = "child-a@example.com",
    aud = audience,
    iss = issuer,
    expiresIn = "1h",
    extra = {},
    kid = "test-kid",
  } = {}) =>
    new SignJWT({ email, type: "app", ...extra })
      .setProtectedHeader({ alg: "RS256", kid })
      .setIssuer(iss)
      .setAudience(aud)
      .setIssuedAt()
      .setExpirationTime(expiresIn)
      .sign(privateKey);

  return {
    issuer,
    audience,
    jwks,
    mintJwt,
    close: () => server.close(),
  };
}
