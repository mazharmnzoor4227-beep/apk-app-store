import core from "./index.js";

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});
const bad = (message, status = 400) => json({ error: message }, status);

function isAdmin(request, env) {
  if (!env.ADMIN_KEY) return false;
  const direct = request.headers.get("x-admin-key") || "";
  const auth = request.headers.get("authorization") || "";
  return direct === env.ADMIN_KEY || (auth.toLowerCase().startsWith("bearer ") && auth.slice(7) === env.ADMIN_KEY);
}
function safeName(value) { return String(value || "file").replace(/[^a-zA-Z0-9._-]/g, "_").slice(-120); }
function safeKind(value) { const v = String(value || "").toLowerCase(); return ["apk", "icon", "screenshot"].includes(v) ? v : ""; }
function fileUrl(request, key) { return `${new URL(request.url).origin}/files/${encodeURIComponent(key).replaceAll("%2F", "/")}`; }

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
    customMetadata: {
      sha256: String(body.sha256 || "").toLowerCase(),
      fileSize: String(Number(body.file_size || 0))
    }
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

export default {
  async fetch(request, env, ctx) {
    const path = new URL(request.url).pathname;
    try {
      if (path === "/api/admin/multipart/start" && request.method === "POST") return startMultipart(request, env);
      if (path === "/api/admin/multipart/part" && request.method === "PUT") return uploadPart(request, env);
      if (path === "/api/admin/multipart/complete" && request.method === "POST") return completeMultipart(request, env);
      if (path === "/api/admin/multipart/abort" && request.method === "POST") return abortMultipart(request, env);
      return core.fetch(request, env, ctx);
    } catch (error) {
      console.error(error);
      return bad("Upload server error", 500);
    }
  }
};
