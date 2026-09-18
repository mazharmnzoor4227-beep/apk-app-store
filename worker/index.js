const json = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers }
});

const bad = (message, status = 400) => json({ error: message }, status);

function isAdmin(request, env) {
  if (!env.ADMIN_KEY) return false;
  const direct = request.headers.get("x-admin-key") || "";
  const auth = request.headers.get("authorization") || "";
  const bearer = auth.toLowerCase().startsWith("bearer ") ? auth.slice(7) : "";
  return direct === env.ADMIN_KEY || bearer === env.ADMIN_KEY;
}

function publicFileUrl(request, key) {
  if (!key) return "";
  return `${new URL(request.url).origin}/files/${encodeURIComponent(key).replaceAll("%2F", "/")}`;
}

function safeSlug(value) {
  return String(value || "").toLowerCase().trim().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 80);
}

function safeName(value) {
  return String(value || "file").replace(/[^a-zA-Z0-9._-]/g, "_").slice(-120);
}

async function listApps(request, env, includeUnpublished = false) {
  const where = includeUnpublished ? "" : "WHERE a.published = 1";
  const { results = [] } = await env.DB.prepare(`
    SELECT a.*,
      r.id AS release_id, r.version_code, r.version_name, r.min_sdk, r.apk_key,
      r.file_size, r.sha256, r.changelog, r.published_at,
      (SELECT COUNT(*) FROM downloads d WHERE d.app_id = a.id) AS download_count
    FROM apps a
    LEFT JOIN releases r ON r.id = (
      SELECT r2.id FROM releases r2
      WHERE r2.app_id = a.id AND r2.published = 1
      ORDER BY r2.version_code DESC LIMIT 1
    )
    ${where}
    ORDER BY a.featured DESC, a.updated_at DESC
  `).all();

  const out = [];
  for (const row of results) {
    const shots = await env.DB.prepare("SELECT file_key FROM screenshots WHERE app_id = ? ORDER BY sort_order, id").bind(row.id).all();
    out.push({
      id: row.id,
      slug: row.slug,
      package_name: row.package_name,
      name: row.name,
      short_description: row.short_description,
      description: row.description,
      category: row.category,
      icon_url: publicFileUrl(request, row.icon_key),
      featured: !!row.featured,
      published: !!row.published,
      version_code: Number(row.version_code || 0),
      version_name: row.version_name || "",
      min_sdk: Number(row.min_sdk || 29),
      file_size: Number(row.file_size || 0),
      sha256: row.sha256 || "",
      changelog: row.changelog || "",
      published_at: row.published_at || "",
      download_url: row.release_id ? `${new URL(request.url).origin}/api/apps/${encodeURIComponent(row.slug)}/download` : "",
      screenshots: (shots.results || []).map(s => publicFileUrl(request, s.file_key)),
      download_count: Number(row.download_count || 0)
    });
  }
  return out;
}

async function getApp(request, env, slug, includeUnpublished = false) {
  const apps = await listApps(request, env, includeUnpublished);
  return apps.find(a => a.slug === slug) || null;
}

async function handleAdminApp(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const body = await request.json().catch(() => null);
  if (!body) return bad("Invalid JSON");
  const packageName = String(body.package_name || "").trim();
  const name = String(body.name || "").trim();
  const slug = safeSlug(body.slug || name || packageName);
  if (!packageName || !name || !slug) return bad("package_name, name and slug are required");

  const existing = await env.DB.prepare("SELECT id, icon_key FROM apps WHERE package_name = ? OR slug = ? LIMIT 1").bind(packageName, slug).first();
  const values = {
    short_description: String(body.short_description || "").slice(0, 240),
    description: String(body.description || ""),
    category: String(body.category || "Apps").slice(0, 80),
    icon_key: String(body.icon_key || existing?.icon_key || ""),
    featured: body.featured ? 1 : 0,
    published: body.published === false ? 0 : 1
  };

  if (existing) {
    await env.DB.prepare(`UPDATE apps SET slug=?, package_name=?, name=?, short_description=?, description=?, category=?, icon_key=?, featured=?, published=?, updated_at=CURRENT_TIMESTAMP WHERE id=?`)
      .bind(slug, packageName, name, values.short_description, values.description, values.category, values.icon_key, values.featured, values.published, existing.id).run();
    return json({ ok: true, id: existing.id, slug });
  }

  const result = await env.DB.prepare(`INSERT INTO apps(slug, package_name, name, short_description, description, category, icon_key, featured, published) VALUES(?,?,?,?,?,?,?,?,?)`)
    .bind(slug, packageName, name, values.short_description, values.description, values.category, values.icon_key, values.featured, values.published).run();
  return json({ ok: true, id: result.meta.last_row_id, slug }, 201);
}

async function handleUpload(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const url = new URL(request.url);
  const kind = safeSlug(url.searchParams.get("kind") || "file");
  if (!["apk", "icon", "screenshot"].includes(kind)) return bad("Invalid upload kind");
  const filename = safeName(url.searchParams.get("filename") || `${kind}.bin`);
  const key = `${kind}/${crypto.randomUUID()}-${filename}`;
  const contentType = request.headers.get("content-type") || "application/octet-stream";
  const sha256 = (request.headers.get("x-sha256") || "").toLowerCase();
  const fileSize = Number(request.headers.get("x-file-size") || 0);
  if (!request.body) return bad("Missing file body");
  await env.FILES.put(key, request.body, {
    httpMetadata: { contentType },
    customMetadata: { sha256, fileSize: String(fileSize) }
  });
  return json({ ok: true, key, url: publicFileUrl(request, key), sha256, file_size: fileSize }, 201);
}

async function handleRelease(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const body = await request.json().catch(() => null);
  if (!body) return bad("Invalid JSON");
  const appId = Number(body.app_id || 0);
  const versionCode = Number(body.version_code || 0);
  const versionName = String(body.version_name || "").trim();
  const apkKey = String(body.apk_key || "").trim();
  if (!appId || !versionCode || !versionName || !apkKey) return bad("app_id, version_code, version_name and apk_key are required");
  const app = await env.DB.prepare("SELECT id FROM apps WHERE id=?").bind(appId).first();
  if (!app) return bad("App not found", 404);
  const exists = await env.FILES.head(apkKey);
  if (!exists) return bad("APK file not found in R2", 404);

  await env.DB.prepare(`INSERT INTO releases(app_id, version_code, version_name, min_sdk, apk_key, file_size, sha256, changelog, published)
    VALUES(?,?,?,?,?,?,?,?,?)
    ON CONFLICT(app_id, version_code) DO UPDATE SET version_name=excluded.version_name, min_sdk=excluded.min_sdk,
      apk_key=excluded.apk_key, file_size=excluded.file_size, sha256=excluded.sha256, changelog=excluded.changelog,
      published=excluded.published, published_at=CURRENT_TIMESTAMP`)
    .bind(appId, versionCode, versionName, Number(body.min_sdk || 29), apkKey, Number(body.file_size || 0), String(body.sha256 || ""), String(body.changelog || ""), body.published === false ? 0 : 1).run();
  await env.DB.prepare("UPDATE apps SET updated_at=CURRENT_TIMESTAMP WHERE id=?").bind(appId).run();
  return json({ ok: true }, 201);
}

async function handleScreenshot(request, env) {
  if (!isAdmin(request, env)) return bad("Unauthorized", 401);
  const body = await request.json().catch(() => null);
  const appId = Number(body?.app_id || 0);
  const fileKey = String(body?.file_key || "");
  if (!appId || !fileKey) return bad("app_id and file_key are required");
  await env.DB.prepare("INSERT INTO screenshots(app_id, file_key, sort_order) VALUES(?,?,?)")
    .bind(appId, fileKey, Number(body.sort_order || 0)).run();
  return json({ ok: true }, 201);
}

async function serveR2(request, env, key, download = false) {
  const object = await env.FILES.get(key);
  if (!object) return new Response("Not found", { status: 404 });
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("cache-control", download ? "private, no-store" : "public, max-age=86400");
  if (download) headers.set("content-disposition", `attachment; filename="${safeName(key.split('/').pop())}"`);
  return new Response(object.body, { headers });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,PUT,DELETE,OPTIONS", "access-control-allow-headers": "content-type,authorization,x-admin-key,x-sha256,x-file-size" } });
    }

    try {
      if (path === "/api/health" && request.method === "GET") return json({ ok: true, service: "APK App Store" });
      if (path === "/api/apps" && request.method === "GET") return json({ apps: await listApps(request, env, false) });

      const appMatch = path.match(/^\/api\/apps\/([^/]+)$/);
      if (appMatch && request.method === "GET") {
        const app = await getApp(request, env, decodeURIComponent(appMatch[1]), false);
        return app ? json({ app }) : bad("App not found", 404);
      }

      const downloadMatch = path.match(/^\/api\/apps\/([^/]+)\/download$/);
      if (downloadMatch && request.method === "GET") {
        const slug = decodeURIComponent(downloadMatch[1]);
        const row = await env.DB.prepare(`SELECT a.id AS app_id, r.id AS release_id, r.apk_key FROM apps a JOIN releases r ON r.id=(SELECT r2.id FROM releases r2 WHERE r2.app_id=a.id AND r2.published=1 ORDER BY r2.version_code DESC LIMIT 1) WHERE a.slug=? AND a.published=1`).bind(slug).first();
        if (!row) return bad("Release not found", 404);
        await env.DB.prepare("INSERT INTO downloads(app_id, release_id) VALUES(?,?)").bind(row.app_id, row.release_id).run();
        return serveR2(request, env, row.apk_key, true);
      }

      if (path.startsWith("/files/") && request.method === "GET") return serveR2(request, env, decodeURIComponent(path.slice(7)), false);

      if (path === "/api/admin/apps" && request.method === "GET") {
        if (!isAdmin(request, env)) return bad("Unauthorized", 401);
        return json({ apps: await listApps(request, env, true) });
      }
      if (path === "/api/admin/apps" && request.method === "POST") return handleAdminApp(request, env);
      if (path === "/api/admin/upload" && request.method === "POST") return handleUpload(request, env);
      if (path === "/api/admin/releases" && request.method === "POST") return handleRelease(request, env);
      if (path === "/api/admin/screenshots" && request.method === "POST") return handleScreenshot(request, env);

      if (path === "/admin" || path === "/admin/") {
        const adminUrl = new URL("/admin.html", url.origin);
        return env.ASSETS.fetch(new Request(adminUrl, request));
      }

      if (path.startsWith("/api/")) return bad("Not found", 404);
      return env.ASSETS.fetch(request);
    } catch (error) {
      console.error(error);
      return bad("Server error", 500);
    }
  }
};
