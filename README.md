# APK App Store

A self-hosted Android app store for publishing and updating your own APKs. The Android client targets Android 10+ and the backend is designed for Cloudflare Workers + D1 + R2.

## What this project includes

- Native Android 10+ APK App Store client
- Home, Search, Updates, Downloads and Settings screens
- Installed-version detection and update comparison
- Background update checks with Android notifications
- Optional auto-download on Wi-Fi (installation still requires Android user confirmation)
- SHA-256 verification before installing downloaded APKs
- Cloudflare Worker REST API
- D1 database for apps, releases and download counts
- R2 storage for APKs, icons and screenshots
- R2 multipart/chunked upload for large APK files
- Private mobile-friendly `/admin` publishing dashboard
- GitHub Actions for debug APK builds, backend validation, Cloudflare deployment and fixed-key production signing

## Important Android update rule

An APK can update an installed app only when its package name and signing certificate match the installed version and its version code is higher. Keep each app's production signing key safe and reuse the same key for every update.

The store can automatically detect updates and optionally download them in the background on Wi-Fi. Normal sideloaded Android apps cannot silently install an update on every consumer phone: Android normally shows a final installation/update confirmation to the user.

## Repository layout

- `app/` Android client
- `worker/` Cloudflare Worker API
- `public/` admin web panel
- `migrations/` D1 schema
- `wrangler.jsonc` Cloudflare configuration
- `.github/workflows/` CI, build, production-signing and deploy workflows

## Backend setup later

1. Create a free Cloudflare account.
2. Create a D1 database named `apk-app-store-db` and an R2 bucket named `apk-app-store-files`.
3. Replace the placeholder D1 `database_id` in `wrangler.jsonc` with the real ID.
4. Create a strong Worker secret: `npx wrangler secret put ADMIN_KEY`.
5. Run `npm install`, `npm run db:remote`, then `npm run deploy` (or configure the GitHub Cloudflare secrets and use the manual deploy workflow).
6. Open `/admin` on the Worker URL, enter `ADMIN_KEY`, and publish the first app/release.
7. In the Android app Settings screen, paste the Worker URL once. No Android rebuild is required when the backend domain changes.

## Publishing an app

The admin dashboard accepts app name, package name, descriptions, icon, screenshots and release information. APKs over the normal single-request size are uploaded to R2 in multipart chunks. The browser calculates SHA-256 before publishing and Android verifies that checksum after downloading.

For every update, use the same package name and signing key, and increase `versionCode`.

## Production signing for APK App Store

The normal CI build is debug-signed for testing. Before distributing APK App Store to other people, create one permanent Android signing key and add these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then run **Production APK Release** manually in GitHub Actions. Keep that keystore backed up permanently; later APK App Store versions must use the same key to update over the installed copy.

## Current milestone

Milestone 1 is a working personal app-store foundation: catalog, install/update flow, update notifications, Wi-Fi auto-download, verified APKs, large-file publishing, download counts and private admin publishing. Cloudflare account provisioning and real-device end-to-end testing are the next steps.
