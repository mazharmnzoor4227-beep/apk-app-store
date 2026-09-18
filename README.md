# APK App Store

A self-hosted Android app store for publishing, selling, downloading and updating your own APKs. The Android client targets Android 10+ and the backend is designed for Cloudflare Workers + D1 + R2.

## What this project includes

- Native Android 10+ APK App Store client
- Home, Search, Updates, Downloads and Settings screens
- Account/Profile screen with Google sign-in and email sign-in entry points
- My Purchases area prepared for account-backed purchase restoration
- In-app Privacy Policy, Terms of Use, Security and About screens
- Free and paid app support with PKR pricing
- Manual payment methods such as Easypaisa, JazzCash, NayaPay, Raast and bank transfer
- Payment submission, admin approval/rejection/refund states and paid-download locking
- Installed-version detection and update comparison
- Background update checks with Android notifications
- Optional auto-download on Wi-Fi (installation still requires Android user confirmation)
- SHA-256 verification before installing downloaded APKs
- Cloudflare Worker REST API
- D1 database for apps, releases, purchases, payment methods and download counts
- R2 storage for APKs, icons and screenshots
- R2 multipart/chunked upload for large APK files
- Private mobile-friendly `/admin` publishing dashboard
- GitHub Actions for debug APK builds, backend validation, Cloudflare deployment and fixed-key production signing

## Account status

The account/profile UI is included in the Android app. Google and email authentication are intentionally not activated until the production backend and Google OAuth configuration are connected. Do not fake or locally trust purchase ownership; production entitlement checks must come from the backend.

When Google sign-in is enabled, use a proper Google OAuth / Android identity configuration and verify the returned identity token on the backend before creating a store session.

## Important Android update rule

An APK can update an installed app only when its package name and signing certificate match the installed version and its version code is higher. Keep each app's production signing key safe and reuse the same key for every update.

The store can automatically detect updates and optionally download them in the background on Wi-Fi. Normal sideloaded Android apps cannot silently install an update on every consumer phone: Android normally shows a final installation/update confirmation to the user.

## Repository layout

- `app/` Android client
- `worker/` Cloudflare Worker API
- `public/` admin web panel
- `migrations/` D1 schema
- `PRIVACY.md` store privacy policy
- `TERMS.md` store terms of use
- `SECURITY.md` security and secret-handling policy
- `wrangler.jsonc` Cloudflare configuration
- `.github/workflows/` CI, build, production-signing and deploy workflows

## Backend setup later

1. Create a Cloudflare account.
2. Create a D1 database named `apk-app-store-db` and an R2 bucket named `apk-app-store-files`.
3. Replace the placeholder D1 `database_id` in `wrangler.jsonc` with the real ID and configure the R2 bucket binding.
4. Create a strong Worker secret for admin authentication and later add customer-session/authentication secrets.
5. Run the D1 migrations, including the payments migration.
6. Deploy the Worker and open `/admin` to configure payment methods and publish apps.
7. Set the production backend URL at build/deployment configuration level. The public APK does not expose a Worker URL field to normal users.
8. Configure Google OAuth and backend token verification before enabling live Google sign-in.
9. Add email account creation/login endpoints with secure password hashing and protected session tokens before enabling live email sign-in.

## Publishing an app

The admin dashboard accepts app name, package name, descriptions, icon, screenshots, release information, free/paid status and PKR price. APKs over the normal single-request size are uploaded to R2 in multipart chunks. The browser calculates SHA-256 before publishing and Android verifies that checksum after downloading.

For every update, use the same package name and signing key, and increase `versionCode`.

## Paid app flow

A paid app stays locked until the backend confirms an approved purchase for the current customer/account. Payment methods can be configured in the admin panel. Manual methods may require a transaction/reference ID and admin approval.

Do not expose permanent direct R2 URLs for paid APKs. Production paid downloads should be authorized by the backend and delivered only after entitlement checks.

## Production signing for APK App Store

The normal CI build is debug-signed for testing. Before distributing APK App Store to other people, create one permanent Android signing key and add these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then run **Production APK Release** manually in GitHub Actions. Keep that keystore backed up permanently; later APK App Store versions must use the same key to update over the installed copy.

## Privacy and repository safety

Do not commit API tokens, Google OAuth secrets, production signing keys, payment-provider credentials or user/payment database exports. Keep those in Cloudflare secrets or GitHub Actions Secrets.

If the project is intended to remain proprietary, making the repository private before production backend/signing work is recommended.

## Current milestone

The current milestone includes the Android catalog/install/update experience, free/paid UI and payment flow, account/profile UI, in-app legal pages, verified APK handling, admin publishing and backend foundations. The next milestone is production backend provisioning plus real Google/email authentication and end-to-end account/purchase testing.
