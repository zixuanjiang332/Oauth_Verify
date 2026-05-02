const http = require("node:http");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { URL, URLSearchParams } = require("node:url");

loadDotEnv(path.join(__dirname, ".env"));

const config = {
  host: process.env.MSVERIFY_HOST || "127.0.0.1",
  port: Number(process.env.MSVERIFY_PORT || "8080"),
  serverId: process.env.MSVERIFY_SERVER_ID || "survival-1",
  apiKey: process.env.MSVERIFY_API_KEY || "local-test-api-key-change-me",
  publicBaseUrl: process.env.MSVERIFY_PUBLIC_BASE_URL || "",
  clientId: process.env.MSVERIFY_CLIENT_ID || "449be4a2-70a7-4b6b-86b3-ff1f926398f3",
  clientSecret: process.env.MSVERIFY_CLIENT_SECRET || "",
  redirectUri: process.env.MSVERIFY_REDIRECT_URI || "https://www.chaos-smp.cn/verify",
  sharedSecret: process.env.MSVERIFY_SHARED_SECRET || "local-test-change-this-secret-before-production-0123456789",
  authorizationEndpoint:
    process.env.MSVERIFY_AUTH_ENDPOINT || "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
  tokenEndpoint:
    process.env.MSVERIFY_TOKEN_ENDPOINT || "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
  scope: process.env.MSVERIFY_SCOPE || "XboxLive.signin openid email profile",
  acceptXboxOnly: process.env.MSVERIFY_ACCEPT_XBOX_ONLY === "true",
};

const pendingStates = new Map();
const completions = [];
let nextCompletionId = 1;

function base64Url(input) {
  return Buffer.from(input)
    .toString("base64")
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function randomBase64Url(bytes = 32) {
  return base64Url(crypto.randomBytes(bytes));
}

function sha256Base64Url(value) {
  return base64Url(crypto.createHash("sha256").update(value).digest());
}

function hmacBase64Url(value, secret = config.sharedSecret) {
  return base64Url(crypto.createHmac("sha256", secret).update(value, "utf8").digest());
}

function constantTimeEqual(left, right) {
  const leftBuffer = Buffer.from(left, "utf8");
  const rightBuffer = Buffer.from(right, "utf8");
  if (leftBuffer.length !== rightBuffer.length) {
    return false;
  }
  return crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

function publicBaseUrl(req) {
  if (config.publicBaseUrl) {
    return config.publicBaseUrl.replace(/\/+$/, "");
  }
  return `http://${req.headers.host || `${config.host}:${config.port}`}`;
}

function verifyApiKey(req) {
  const supplied = req.headers["x-api-key"] || "";
  return constantTimeEqual(String(config.apiKey), String(supplied));
}

function parseChallengeToken(token) {
  const parts = String(token || "").split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) {
    throw new Error("验证参数格式不正确。");
  }

  const payloadJson = Buffer.from(parts[0], "base64url").toString("utf8");
  const expectedSignature = hmacBase64Url(payloadJson);
  if (!constantTimeEqual(expectedSignature, parts[1])) {
    throw new Error("验证签名无效。");
  }

  const payload = JSON.parse(payloadJson);
  if (payload.v !== 1) {
    throw new Error("验证版本不受支持。");
  }
  if (payload.serverId !== config.serverId) {
    throw new Error(`服务器 ID 不匹配：${payload.serverId}`);
  }
  if (!payload.challengeId || !payload.minecraftUuid || !payload.expiresAt) {
    throw new Error("验证参数缺少必要字段。");
  }
  if (Number(payload.expiresAt) < Date.now()) {
    throw new Error("验证链接已过期，请回到服务器重新获取链接。");
  }
  return payload;
}

function canonicalUuid(value) {
  const compact = String(value || "").replaceAll("-", "").toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(compact)) {
    throw new Error(`UUID 格式不正确：${value}`);
  }
  return `${compact.slice(0, 8)}-${compact.slice(8, 12)}-${compact.slice(12, 16)}-${compact.slice(16, 20)}-${compact.slice(20)}`;
}

function sendHtml(res, status, title, body) {
  res.writeHead(status, {
    "content-type": "text/html; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(`<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <title>${escapeHtml(title)}</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 48px; line-height: 1.5; color: #18202a; }
    main { max-width: 720px; }
    code { background: #eef2f7; padding: 2px 5px; border-radius: 4px; }
  </style>
</head>
<body><main><h1>${escapeHtml(title)}</h1>${body}</main></body>
</html>`);
}

function sendJson(res, status, value) {
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(JSON.stringify(value));
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function decodeJwtPayload(token) {
  const parts = String(token || "").split(".");
  if (parts.length < 2 || !parts[1]) {
    return {};
  }
  try {
    return JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
  } catch {
    return {};
  }
}

function extractAccountEmail(microsoftToken) {
  const claims = decodeJwtPayload(microsoftToken.id_token);
  const email = claims.email || claims.preferred_username || claims.upn;
  if (typeof email === "string" && email.includes("@")) {
    return email;
  }
  return "";
}

async function readJsonResponse(response, label) {
  const text = await response.text();
  let body = {};
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`${label} 返回了非 JSON 响应 HTTP ${response.status}: ${text.slice(0, 300)}`);
  }
  if (!response.ok) {
    throw new Error(`${label} 失败 HTTP ${response.status}: ${JSON.stringify(body).slice(0, 500)}`);
  }
  return body;
}

async function exchangeCodeForMicrosoftToken(code, codeVerifier) {
  const body = new URLSearchParams({
    client_id: config.clientId,
    grant_type: "authorization_code",
    code,
    redirect_uri: config.redirectUri,
    scope: config.scope,
  });
  if (codeVerifier) {
    body.set("code_verifier", codeVerifier);
  }
  if (config.clientSecret) {
    body.set("client_secret", config.clientSecret);
  }

  const response = await fetch(config.tokenEndpoint, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
  });
  return readJsonResponse(response, "Microsoft 令牌交换");
}

async function authenticateXboxLive(microsoftAccessToken) {
  const response = await fetch("https://user.auth.xboxlive.com/user/authenticate", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
    },
    body: JSON.stringify({
      Properties: {
        AuthMethod: "RPS",
        SiteName: "user.auth.xboxlive.com",
        RpsTicket: `d=${microsoftAccessToken}`,
      },
      RelyingParty: "http://auth.xboxlive.com",
      TokenType: "JWT",
    }),
  });
  return readJsonResponse(response, "Xbox Live 认证");
}

async function authorizeXsts(xboxToken) {
  const response = await fetch("https://xsts.auth.xboxlive.com/xsts/authorize", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
    },
    body: JSON.stringify({
      Properties: {
        SandboxId: "RETAIL",
        UserTokens: [xboxToken],
      },
      RelyingParty: "rp://api.minecraftservices.com/",
      TokenType: "JWT",
    }),
  });
  return readJsonResponse(response, "XSTS 授权");
}

async function loginMinecraft(uhs, xstsToken) {
  const response = await fetch("https://api.minecraftservices.com/authentication/login_with_xbox", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
    },
    body: JSON.stringify({
      identityToken: `XBL3.0 x=${uhs};${xstsToken}`,
    }),
  });
  return readJsonResponse(response, "Minecraft 登录");
}

async function fetchMinecraftProfile(minecraftAccessToken) {
  const response = await fetch("https://api.minecraftservices.com/minecraft/profile", {
    headers: {
      accept: "application/json",
      authorization: `Bearer ${minecraftAccessToken}`,
    },
  });
  return readJsonResponse(response, "Minecraft 档案查询");
}

async function verifyMinecraftOwnership(code, codeVerifier, expectedUuid, expectedName) {
  const microsoftToken = await exchangeCodeForMicrosoftToken(code, codeVerifier);
  const email = extractAccountEmail(microsoftToken);
  const xboxAuth = await authenticateXboxLive(microsoftToken.access_token);
  const xsts = await authorizeXsts(xboxAuth.Token);
  const uhs = xsts.DisplayClaims?.xui?.[0]?.uhs;
  if (!uhs) {
    throw new Error("XSTS 响应中没有用户标识。");
  }

  if (config.acceptXboxOnly) {
    return {
      minecraftUuid: canonicalUuid(expectedUuid),
      minecraftName: expectedName || "未知玩家",
      xuid: uhs,
      email,
      verifiedAt: Date.now(),
    };
  }

  const minecraftLogin = await loginMinecraft(uhs, xsts.Token);
  const profile = await fetchMinecraftProfile(minecraftLogin.access_token);
  const actualUuid = canonicalUuid(profile.id);
  if (actualUuid !== canonicalUuid(expectedUuid)) {
    throw new Error(`Minecraft UUID 不匹配。服务器玩家 UUID 是 ${expectedUuid}，Microsoft 账号返回的是 ${actualUuid}。`);
  }

  return {
    minecraftUuid: actualUuid,
    minecraftName: profile.name,
    xuid: uhs,
    email,
    verifiedAt: Date.now(),
  };
}

function handleGenerate(req, res, url) {
  if (!verifyApiKey(req)) {
    return sendJson(res, 401, { error: "invalid_api_key" });
  }

  const serverId = url.searchParams.get("serverId") || "";
  const minecraftUuid = url.searchParams.get("minecraftUuid") || "";
  const minecraftName = url.searchParams.get("minecraftName") || "";
  if (serverId !== config.serverId) {
    return sendJson(res, 400, { error: "invalid_server_id" });
  }
  let uuid;
  try {
    uuid = canonicalUuid(minecraftUuid);
  } catch (error) {
    return sendJson(res, 400, { error: "invalid_uuid", message: error.message });
  }
  if (!minecraftName.trim()) {
    return sendJson(res, 400, { error: "missing_name" });
  }

  const token = randomBase64Url(32);
  const now = Date.now();
  const expiresAt = now + 5 * 60_000;
  const link = `${publicBaseUrl(req)}/verify?token=${encodeURIComponent(token)}`;
  pendingStates.set(token, {
    token,
    apiGenerated: true,
    codeVerifier: "",
    challengeToken: "",
    payload: {
      v: 2,
      serverId,
      challengeId: token,
      minecraftUuid: uuid,
      minecraftName,
      issuedAt: now,
      expiresAt,
    },
    createdAt: now,
    completedAt: 0,
    status: "pending",
    verified: null,
    error: "",
  });
  return sendJson(res, 200, { token, link, expiresAt });
}

function handleGet(req, res, url) {
  if (!verifyApiKey(req)) {
    return sendJson(res, 401, { error: "invalid_api_key" });
  }

  const token = url.searchParams.get("token") || "";
  const pending = pendingStates.get(token);
  if (!pending) {
    return sendJson(res, 404, { status: "not_found", token, message: "token 不存在或已过期" });
  }
  if (Number(pending.payload.expiresAt) < Date.now() && pending.status !== "verified") {
    pending.status = "expired";
    pending.error = "验证 token 已过期";
    return sendJson(res, 200, { status: "expired", token, message: pending.error });
  }
  if (pending.status === "verified" && pending.verified) {
    return sendJson(res, 200, {
      status: "verified",
      token,
      email: pending.verified.email || "",
      uuid: pending.verified.minecraftUuid,
      minecraftUuid: pending.verified.minecraftUuid,
      minecraftName: pending.verified.minecraftName,
      xuid: pending.verified.xuid,
      verifiedAt: pending.verified.verifiedAt,
    });
  }
  if (pending.status === "error" || pending.status === "expired" || pending.status === "denied") {
    return sendJson(res, 200, { status: pending.status, token, message: pending.error || "验证失败" });
  }
  return sendJson(res, 200, { status: "pending", token });
}

function handleVerifyPage(req, res, url) {
  if (url.searchParams.has("code") || url.searchParams.has("error")) {
    handleCallback(req, res, url).catch((error) =>
      sendHtml(res, 500, "验证失败", `<p>${escapeHtml(error.message)}</p>`)
    );
    return;
  }

  const token = url.searchParams.get("token") || "";
  const pending = pendingStates.get(token);
  if (!pending || !pending.apiGenerated) {
    return sendHtml(res, 404, "验证链接无效", "<p>这个验证链接不存在或已经过期，请回到服务器重新获取。</p>");
  }
  if (Number(pending.payload.expiresAt) < Date.now()) {
    pending.status = "expired";
    return sendHtml(res, 400, "验证链接已过期", "<p>请回到服务器重新获取验证链接。</p>");
  }
  if (pending.status === "verified" && pending.verified) {
    return sendHtml(
      res,
      200,
      "验证已完成",
      `<p>Minecraft 玩家：<strong>${escapeHtml(pending.verified.minecraftName)}</strong></p>
       <p>Microsoft 邮箱：<strong>${escapeHtml(pending.verified.email || "微软未返回邮箱")}</strong></p>
       <p>现在可以回到服务器。</p>`
    );
  }

  const usePublicClientPkce = !config.clientSecret;
  if (usePublicClientPkce && !pending.codeVerifier) {
    pending.codeVerifier = randomBase64Url(64);
    pending.codeChallenge = sha256Base64Url(pending.codeVerifier);
  }

  const authorizationUrl = new URL(config.authorizationEndpoint);
  authorizationUrl.searchParams.set("client_id", config.clientId);
  authorizationUrl.searchParams.set("response_type", "code");
  authorizationUrl.searchParams.set("response_mode", "query");
  authorizationUrl.searchParams.set("redirect_uri", config.redirectUri);
  authorizationUrl.searchParams.set("scope", config.scope);
  authorizationUrl.searchParams.set("state", token);
  authorizationUrl.searchParams.set("prompt", "select_account");
  if (usePublicClientPkce) {
    authorizationUrl.searchParams.set("code_challenge", pending.codeChallenge);
    authorizationUrl.searchParams.set("code_challenge_method", "S256");
  }

  res.writeHead(302, { location: authorizationUrl.toString(), "cache-control": "no-store" });
  res.end();
}

function handleStart(req, res, url) {
  const challengeToken = url.searchParams.get("challenge");
  if (!challengeToken) {
    return sendHtml(res, 400, "缺少验证参数", "<p>这个请求没有携带服务器生成的验证参数。</p>");
  }

  let payload;
  try {
    payload = parseChallengeToken(challengeToken);
  } catch (error) {
    return sendHtml(res, 400, "验证链接无效", `<p>${escapeHtml(error.message)}</p>`);
  }

  const usePublicClientPkce = !config.clientSecret;
  const state = usePublicClientPkce ? randomBase64Url(24) : challengeToken;
  let codeVerifier = "";
  let codeChallenge = "";
  if (usePublicClientPkce) {
    codeVerifier = randomBase64Url(64);
    codeChallenge = sha256Base64Url(codeVerifier);
    pendingStates.set(state, {
      codeVerifier,
      challengeToken,
      payload,
      createdAt: Date.now(),
    });
  }

  const authorizationUrl = new URL(config.authorizationEndpoint);
  authorizationUrl.searchParams.set("client_id", config.clientId);
  authorizationUrl.searchParams.set("response_type", "code");
  authorizationUrl.searchParams.set("response_mode", "query");
  authorizationUrl.searchParams.set("redirect_uri", config.redirectUri);
  authorizationUrl.searchParams.set("scope", config.scope);
  authorizationUrl.searchParams.set("state", state);
  authorizationUrl.searchParams.set("prompt", "select_account");
  if (usePublicClientPkce) {
    authorizationUrl.searchParams.set("code_challenge", codeChallenge);
    authorizationUrl.searchParams.set("code_challenge_method", "S256");
  }

  res.writeHead(302, { location: authorizationUrl.toString(), "cache-control": "no-store" });
  res.end();
}

async function handleCallback(req, res, url) {
  const error = url.searchParams.get("error");
  if (error) {
    return sendHtml(
      res,
      400,
      "Microsoft 登录失败",
      `<p>${escapeHtml(error)}: ${escapeHtml(url.searchParams.get("error_description") || "")}</p>`
    );
  }

  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  if (!code || !state) {
    return sendHtml(res, 400, "回调无效", "<p>Microsoft 回调缺少 code 或 state。</p>");
  }

  let pending = pendingStates.get(state);
  if (pending) {
    // gen/get 模式下 state 就是 token，必须保留映射，等待插件 /verify/get 查询。
  } else if (config.clientSecret) {
    try {
      const payload = parseChallengeToken(state);
      pending = {
        codeVerifier: "",
        challengeToken: state,
        payload,
        createdAt: Date.now(),
      };
    } catch (error) {
      return sendHtml(res, 400, "回调无效", `<p>${escapeHtml(error.message)}</p>`);
    }
  } else {
    pending = pendingStates.get(state);
    if (!pending) {
      return sendHtml(res, 400, "回调无效", "<p>登录状态已过期，请回到 Minecraft 服务器重新点击新的验证链接。</p>");
    }
  }

  try {
    const verified = await verifyMinecraftOwnership(
      code,
      pending.codeVerifier,
      pending.payload.minecraftUuid,
      pending.payload.minecraftName
    );
    pending.status = "verified";
    pending.verified = verified;
    pending.completedAt = Date.now();
    if (!pending.apiGenerated) {
      completions.push({
        id: nextCompletionId++,
        serverId: pending.payload.serverId,
        challengeId: pending.payload.challengeId,
        minecraftUuid: verified.minecraftUuid,
        minecraftName: verified.minecraftName,
        xuid: verified.xuid,
        verifiedAt: verified.verifiedAt,
      });
    }

    return sendHtml(
      res,
      200,
      "验证完成",
      `<p>Minecraft 玩家：<strong>${escapeHtml(verified.minecraftName)}</strong></p>
       <p>Microsoft 邮箱：<strong>${escapeHtml(verified.email || "微软未返回邮箱")}</strong></p>
       ${config.acceptXboxOnly ? "<p>临时模式已通过 Microsoft OAuth、Xbox Live 和 XSTS 验证；此模式不会进行 Minecraft 官方所有权校验。</p>" : ""}
       <p>现在可以回到服务器，插件会在下一次轮询后解除冻结。</p>`
    );
  } catch (error) {
    if (pending && pending.apiGenerated) {
      pending.status = "error";
      pending.error = error.message;
      pending.completedAt = Date.now();
    }
    return sendHtml(res, 500, "验证失败", `<p>${escapeHtml(error.message)}</p>`);
  }
}

function verifyPollSignature(req, url) {
  const serverId = url.searchParams.get("serverId") || "";
  const since = url.searchParams.get("since") || "0";
  const timestamp = req.headers["x-msverify-timestamp"] || "";
  const signature = req.headers["x-msverify-signature"] || "";
  const signedServer = req.headers["x-msverify-server"] || "";
  if (serverId !== config.serverId || signedServer !== config.serverId) {
    return false;
  }
  if (!/^\d+$/.test(timestamp) || Math.abs(Date.now() - Number(timestamp)) > 300_000) {
    return false;
  }
  const canonical = `GET\n${req.url}\n${serverId}\n${since}\n${timestamp}`;
  return constantTimeEqual(hmacBase64Url(canonical), signature);
}

function handleCompleted(req, res, url) {
  if (!verifyPollSignature(req, url)) {
    return sendJson(res, 401, { error: "invalid_signature" });
  }

  const since = Number(url.searchParams.get("since") || "0");
  const items = completions
    .filter((item) => item.serverId === config.serverId && item.id > since)
    .map(({ id, serverId, ...item }) => item);
  const nextCursor =
    completions
      .filter((item) => item.serverId === config.serverId && item.id > since)
      .map((item) => item.id)
      .at(-1) ?? since;
  return sendJson(res, 200, { items, nextCursor: String(nextCursor) });
}

function cleanupStates() {
  const cutoff = Date.now() - 10 * 60_000;
  for (const [state, pending] of pendingStates) {
    if (pending.status === "verified" && pending.completedAt && pending.completedAt < cutoff) {
      pendingStates.delete(state);
      continue;
    }
    if (pending.status !== "verified" && (pending.createdAt < cutoff || Number(pending.payload.expiresAt) < Date.now())) {
      pendingStates.delete(state);
    }
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  cleanupStates();

  if (req.method === "GET" && url.pathname === "/verify/gen") {
    return handleGenerate(req, res, url);
  }
  if (req.method === "GET" && url.pathname === "/verify/get") {
    return handleGet(req, res, url);
  }
  if (req.method === "GET" && url.pathname === "/verify") {
    return handleVerifyPage(req, res, url);
  }
  if (req.method === "GET" && url.pathname === "/start") {
    return handleStart(req, res, url);
  }
  if (req.method === "GET" && (url.pathname === "/verify" || url.pathname === "/oauth/microsoft/callback")) {
    handleCallback(req, res, url).catch((error) =>
      sendHtml(res, 500, "验证失败", `<p>${escapeHtml(error.message)}</p>`)
    );
    return;
  }
  if (req.method === "GET" && url.pathname === "/api/verifications/completed") {
    return handleCompleted(req, res, url);
  }
  if (req.method === "GET" && url.pathname === "/health") {
    return sendJson(res, 200, {
      ok: true,
      pendingStates: pendingStates.size,
      completions: completions.length,
      redirectUri: config.redirectUri,
      clientId: config.clientId,
      apiMode: "gen-get",
      acceptXboxOnly: config.acceptXboxOnly,
    });
  }

  return sendHtml(res, 404, "页面不存在", "<p>没有找到对应的验证服务页面。</p>");
});

server.listen(config.port, config.host, () => {
  console.log(`MsVerify 本地验证服务正在监听：http://${config.host}:${config.port}`);
  console.log(`Azure 重定向 URI 必须完全一致：${config.redirectUri}`);
  console.log(`客户端 ID：${config.clientId}`);
  console.log(`验证 API：/verify/gen 与 /verify/get`);
  console.log(`客户端密钥已配置：${config.clientSecret ? "是" : "否"}`);
  console.log(`临时仅 Xbox 验证模式：${config.acceptXboxOnly ? "已启用" : "已停用"}`);
});

function loadDotEnv(filePath) {
  if (!fs.existsSync(filePath)) {
    return;
  }
  const content = fs.readFileSync(filePath, "utf8");
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      continue;
    }
    const equalsIndex = trimmed.indexOf("=");
    if (equalsIndex < 1) {
      continue;
    }
    const key = trimmed.slice(0, equalsIndex).trim();
    let value = trimmed.slice(equalsIndex + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!process.env[key]) {
      process.env[key] = value;
    }
  }
}
