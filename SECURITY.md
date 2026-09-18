# Security Policy

Security issues in APK App Store should be handled privately whenever possible.

## Supported code

The latest `main` branch and the latest distributed APK are the versions intended to receive security fixes.

## Reporting a vulnerability

Please do not publish working exploits, credentials, private tokens, payment details, or user data in a public issue.

If GitHub private vulnerability reporting / Security Advisories are enabled for this repository, use that channel. Otherwise contact the repository owner privately and include:

- a clear description of the issue;
- affected version/commit;
- reproduction steps;
- impact;
- any suggested mitigation.

## Secrets and signing keys

Never commit any of the following to the repository:

- Cloudflare API tokens or `ADMIN_KEY` values;
- Google OAuth client secrets;
- production Android keystores;
- keystore passwords or signing passwords;
- private payment-provider credentials;
- database exports containing user or payment information.

Production secrets must be stored in the appropriate Cloudflare secret store or GitHub Actions Secrets. The production Android signing key must be backed up securely and reused for every future APK App Store update.

## Download security

Published APK releases should include a SHA-256 checksum. The Android client verifies the checksum when provided before handing the file to Android's installer.

## Backend security

The production backend should use HTTPS, authenticated customer sessions, role-protected admin endpoints, server-side purchase authorization, rate limiting where appropriate, and short-lived download authorization for paid apps.