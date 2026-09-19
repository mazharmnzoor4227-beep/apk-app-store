import core from "./index.js";

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});
const bad = (message, status = 400) => json({ error: message }, status);
const encoder = new TextEncoder();
const SESSION_SECONDS = 60 * 60 * 24 * 30;
const PBKDF2_ITERATIONS = 120000;

function isAdmin(request, env) {
  if (!env.ADMIN_KEY) return false;
  const direct = request.headers.get("x-admin-key") || "";
  const auth = request.headers.get("authorization") || "";
  return direct === env.ADMIN_KEY || (auth.toLowerCase().startsWith("bearer ") && auth.slice(7) === env.ADMIN_KEY);
}
function safeName(value) { return String(value || "file").replace(/[^a-zA-Z0-9._-]/g, "_").slice(-120); }
function safeKind(value) { const v = String(value || "").toLowerCase(); return ["apk", "icon", "screenshot"].includes(v) ? v : ""; }
function fileUrl(request, key) { return `${new URL(request.url).origin}/files/${encodeURIComponent(key).replaceAll("%2F", "/")}`; }
function emailValue(value) { return String(value || "").trim().toLowerCase().slice(0, 254); }
function validEmail(value) { return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value); }
function hex(bytes) { return [...bytes].map(b => b.toString(16).padStart(2, "0")).join(""); }
function fromHex(value) {
  const clean = String(value || "");
  const out = new Uint8Array(Math.floor(clean.length / 2));
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}
function randomHex(size = 16) { const b = new Uint8Array(size); crypto.getRandomValues(b); return hex(b); }
function randomToken() {
  const b = new Uint8Array(32); crypto.getRandomValues(b);
  let s = ""; for (const x of b) s += String.fromCharCode(x);
  return btoa(s).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/g, "");
}
async function sha256Hex(value) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(String(value)))));
}
async function passwordHash(password, saltHex) {
  const key = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({
    name: "PBKDF2",
    salt: fromHex(saltHex),
    iterations: PBKDF2_ITERATIONS,
    hash: "SHA-256"
  }, key, 256);
  return hex(new Uint8Array(bits));
}
function safeEqual(a, b) {
  const aa = encoder.encode(String(a));
  const bb = encoder.encode(String(b));
  if (aa.length !== bb.length) return false;
  let diff = 0; for (let i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
  return diff === 0;
}
function bearerToken(request) {
  const auth = request.headers.get("authorization") || "";
  return auth.toLowerCase().startsWith("bearer ") ? auth.slice(7).trim() : "";
}
let authSchemaReady = null;
async function ensureAuthSchema(env) {
  if (!authSchemaReady) authSchemaReady = (async () => {
    await env.DB.prepare(`CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      email TEXT NOT NULL UNIQUE COLLATE NOCASE,
      display_name TEXT NOT NULL,
      password_salt TEXT NOT NULL,
      password_hash TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )`).run();
    await env.DB.prepare(`CREATE TABLE IF NOT EXISTS sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      expires_at INTEGER NOT NULL,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )`).run();
    await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_sessions_token_hash ON sessions(token_hash)").run();
    await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id)").run();
    await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions(expires_at)").run();
  })();
  return authSchemaReady;
}
async function authStatus(env) {
  await ensureAuthSchema(env);
  return json({ ok: true, auth: true, email_login: true, google_login_configured: !!env.GOOGLE_WEB_CLIENT_ID });
}
async function sessionUser(request, env) {
  await ensureAuthSchema(env);
  const token = bearerToken(request);
  if (!token) return null;
  const tokenHash = await sha256Hex(token);
  return env.DB.prepare(`
    SELECT u.id,u.email,u.display_name,s.id AS session_id,s.expires_at
    FROM sessions s JOIN users u ON u.id=s.user_id
    WHERE s.token_hash=? AND s.expires_at>? LIMIT 1
  `).bind(tokenHash, Math.floor(Date.now() / 1000)).first();
}
async function issueSession(env, userId) {
  const token = randomToken();
  const tokenHash = await sha256Hex(token);
  const expiresAt = Math.floor(Date.now() / 1000) + SESSION_SECONDS;
  await env.DB.prepare("INSERT INTO sessions(user_id,token_hash,expires_at) VALUES(?,?,?)")
    .bind(userId, tokenHash, expiresAt).run();
  return { token, expiresAt };
}
function publicUser(row) {
  return { id: Number(row.id), email: String(row.email), name: String(row.display_name || "") };
}
async function register(request, env) {
  await ensureAuthSchema(env);
  const body = await request.json().catch(() => null);
  if (!body) return bad("Invalid JSON");
  const name = String(body.name || "").trim().slice(0, 80);
  const email = emailValue(body.email);
  const password = String(body.password || "");
  if (name.length < 2) return bad("Enter your name");
  if (!validEmail(email)) return bad("Enter a valid email address");
  if (password.length < 8 || password.length > 200) return bad("Password must be at least 8 characters");
  const existing = await env.DB.prepare("SELECT id FROM users WHERE email=? LIMIT 1").bind(email).first();
  if (existing) return bad("An account with this email already exists", 409);
  const salt = randomHex(16);
  const hashValue = await passwordHash(password, salt);
  const result = await env.DB.prepare("INSERT INTO users(email,display_name,password_salt,password_hash) VALUES(?,?,?,?)")
    .bind(email, name, salt, hashValue).run();
  const userId = Number(result.meta.last_row_id);
  const session = await issueSession(env, userId);
  return json({ ok: true, token: session.token, expires_at: session.expiresAt, user: { id: userId, email, name } }, 201);
}
async function login(request, env) {
  await ensureAuthSchema(env);
  const body = await request.json().catch(() => null);
  if (!body) return bad("Invalid JSON");
  const email = emailValue(body.email);
  const password = String(body.password || "");
  if (!validEmail(email) || !password) return bad("Invalid email or password", 401);
  const user = await env.DB.prepare("SELECT id,email,display_name,password_salt,password_hash FROM users WHERE email=? LIMIT 1").bind(email).first();
  if (!user) return bad("Invalid email or password", 401);
  const candidate = await passwordHash(password, user.password_salt);
  if (!safeEqual(candidate, user.password_hash)) return bad("Invalid email or password", 401);
  const session = await issueSession(env, user.id);
  return json({ ok: true, token: session.token, expires_at: session.expiresAt, user: publicUser(user) });
}
async function me(request, env) {
  const user = await sessionUser(request, env);
  if (!user) return bad("Unauthorized", 401);
  return json({ ok: true, user: publicUser(user) });
}
async function logout(request, env) {
  const token = bearerToken(request);
  if (token) {
    const tokenHash = await sha256Hex(token);
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash=?").bind(tokenHash).run();
  }
  return json({ ok: true });
}
async function withAccountIdentity(request, env) {
  const user = await sessionUser(request, env);
  if (!user) return request;
  const headers = new Headers(request.headers);
  headers.set("x-customer-key", `account:${user.id}`);
  return new Request(request, { headers });
}

async function startMultipart(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const body = await request.json().catch(() => null);
  if (!body) return bad("Invalid JSON");
  const kind = safeKind(body.kind);
  if (!kind) return bad("Invalid upload kind");
  const filename = safeName(body.filename || `${kind}.bin`);
  const key = `${kind}/${crypto.randomUUID()}-${filename}`;
  const upload = await env.FILES.createMultipartUpload(key, {
    httpMetadata: { contentType: String(body.content_type || "application/octet-stream") },
    customMetadata: { sha256: String(body.sha256 || "").toLowerCase(), fileSize: String(Number(body.file_size || 0)) }
  });
  return json({ ok: true, key, upload_id: upload.uploadId, url: fileUrl(request, key) }, 201);
}
async function uploadPart(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const url = new URL(request.url);
  const key = url.searchParams.get("key") || "";
  const uploadId = url.searchParams.get("upload_id") || "";
  const partNumber = Number(url.searchParams.get("part_number") || 0);
  if (!key || !uploadId || !partNumber || !request.body) return bad("Missing multipart parameters");
  const upload = env.FILES.resumeMultipartUpload(key, uploadId);
  const part = await upload.uploadPart(partNumber, request.body);
  return json({ ok: true, part_number: part.partNumber, etag: part.etag });
}
async function completeMultipart(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const body = await request.json().catch(() => null);
  if (!body?.key || !body?.upload_id || !Array.isArray(body.parts)) return bad("Invalid multipart completion payload");
  const upload = env.FILES.resumeMultipartUpload(body.key, body.upload_id);
  const object = await upload.complete(body.parts.map(p => ({ partNumber: Number(p.partNumber), etag: String(p.etag) })));
  return json({ ok: true, key: body.key, etag: object.httpEtag });
}
async function abortMultipart(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const body = await request.json().catch(() => null);
  if (!body?.key || !body?.upload_id) return bad("Invalid abort payload");
  const upload = env.FILES.resumeMultipartUpload(body.key, body.upload_id);
  await upload.abort();
  return json({ ok: true });
}

async function updateAdminAppState(request, env, id) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const body = await request.json().catch(() => null);
  if (!body || typeof body.published !== "boolean") return bad("published must be true or false");
  const appId = Number(id);
  const result = await env.DB.prepare("UPDATE apps SET published=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")
    .bind(body.published ? 1 : 0, appId).run();
  if (!result.meta.changes) return bad("App not found", 404);
  return json({ ok: true, id: appId, published: body.published });
}

async function deleteAdminApp(request, env, id) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const appId = Number(id);
  const app = await env.DB.prepare("SELECT id,name,icon_key FROM apps WHERE id=? LIMIT 1").bind(appId).first();
  if (!app) return bad("App not found", 404);

  const [releaseRows, screenshotRows] = await Promise.all([
    env.DB.prepare("SELECT apk_key FROM releases WHERE app_id=?").bind(appId).all(),
    env.DB.prepare("SELECT file_key FROM screenshots WHERE app_id=?").bind(appId).all()
  ]);
  const keys = new Set();
  if (app.icon_key) keys.add(String(app.icon_key));
  for (const row of releaseRows.results || []) if (row.apk_key) keys.add(String(row.apk_key));
  for (const row of screenshotRows.results || []) if (row.file_key) keys.add(String(row.file_key));

  await Promise.allSettled([...keys].map(key => env.FILES.delete(key)));
  await env.DB.prepare("DELETE FROM downloads WHERE app_id=?").bind(appId).run();
  await env.DB.prepare("DELETE FROM purchases WHERE app_id=?").bind(appId).run();
  await env.DB.prepare("DELETE FROM screenshots WHERE app_id=?").bind(appId).run();
  await env.DB.prepare("DELETE FROM releases WHERE app_id=?").bind(appId).run();
  await env.DB.prepare("DELETE FROM apps WHERE id=?").bind(appId).run();
  return json({ ok: true, deleted_id: appId, name: String(app.name || "") });
}

export default {
  async fetch(request, env, ctx) {
    const path = new URL(request.url).pathname;
    try {
      if ((path === "/admin" || path === "/admin/") && (request.method === "GET" || request.method === "HEAD")) {
        const url = new URL(request.url); url.pathname = "/admin.html";
        return env.ASSETS.fetch(new Request(url.toString(), request));
      }
      if (path === "/api/auth/status" && request.method === "GET") return authStatus(env);
      if (path === "/api/auth/register" && request.method === "POST") return register(request, env);
      if (path === "/api/auth/login" && request.method === "POST") return login(request, env);
      if (path === "/api/auth/me" && request.method === "GET") return me(request, env);
      if (path === "/api/auth/logout" && request.method === "POST") return logout(request, env);
      if (path === "/api/admin/multipart/start" && request.method === "POST") return startMultipart(request, env);
      if (path === "/api/admin/multipart/part" && request.method === "PUT") return uploadPart(request, env);
      if (path === "/api/admin/multipart/complete" && request.method === "POST") return completeMultipart(request, env);
      if (path === "/api/admin/multipart/abort" && request.method === "POST") return abortMultipart(request, env);
      const adminAppMatch = path.match(/^\/api\/admin\/apps\/(\d+)$/);
      if (adminAppMatch && request.method === "PUT") return updateAdminAppState(request, env, adminAppMatch[1]);
      if (adminAppMatch && request.method === "DELETE") return deleteAdminApp(request, env, adminAppMatch[1]);
      const effective = (path.startsWith("/api/") || path.startsWith("/files/")) ? await withAccountIdentity(request, env) : request;
      return core.fetch(effective, env, ctx);
    } catch (error) {
      console.error(error);
      return bad("Server error", 500);
    }
  }
};
