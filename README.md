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
- Private web admin panel for publishing apps and releases
- GitHub Actions for Android APK builds and backend validation

## Important Android update rule

An APK can update an installed app only when its package name and signing certificate match the installed version and its version code is higher. Keep each app's production signing key safe and reuse the same key for every update.

## Repository layout

- `app/` Android client
- `worker/` Cloudflare Worker API
- `public/` admin web panel
- `migrations/` D1 schema
- `wrangler.jsonc` Cloudflare configuration
- `.github/workflows/` CI/build workflows

## Backend setup later

1. Create a free Cloudflare account.
2. Create a D1 database named `apk-app-store-db` and an R2 bucket named `apk-app-store-files`.
3. Put the D1 database ID into `wrangler.jsonc`.
4. Create a strong Worker secret with `npx wrangler secret put ADMIN_KEY`.
5. Run `npm install`, `npm run db:remote`, then `npm run deploy`.
6. Open `/admin` on the Worker URL and publish your first app.
7. In the Android app Settings screen, paste your Worker URL once. No APK rebuild is required when your backend domain changes.

## Current milestone

Milestone 1 is a working personal app-store foundation. It supports normal Android sideload updates: update detection and download can be automatic, but Android normally requires the user to confirm installation unless the device/app has special privileged management rights.
