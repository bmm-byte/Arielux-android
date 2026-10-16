# Arielux custom Android wrapper (Google Play Billing)

A standalone Android WebView host for the published Arielux web app
(`https://aura-your-muse.base44.app`) that injects the native Google Play
Billing bridge (`window.PlayBilling`) the web app expects via
`src/lib/playBillingBridge.js`. The Base44 managed wrapper does **not** expose
native billing, so this custom wrapper is required for in-app Play Billing.

This project is **separate** from the Base44 app. It touches no Base44 source,
no product IDs, no prices, no user data, and no subscription records.

## Project layout

```
native/android/
  settings.gradle.kts
  build.gradle.kts              (root — plugin versions)
  gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/
    build.gradle.kts            (applicationId, billing 9.1.0, signing from env)
    proguard-rules.pro
    src/main/AndroidManifest.xml
    src/main/res/xml/network_security_config.xml   (cleartext off)
    src/main/res/values/{strings,themes}.xml
    src/main/java/com/arielux/wrapper/MainActivity.kt     (secure WebView + origin guard)
    src/main/java/com/arielux/billing/PlayBillingInterface.kt  (the bridge)
  PLAY_STORE_SITUATION.md       (Arielux identity + owner-approval gate)
  SETUP_SECRETS.md              (GitHub Secrets + Base44 secrets setup)
  OriginSecurity.md             (origin requirements; not a security-verification report)
```

## Shortest phone-only build path

1. From this **Arielux** Base44 project, connect GitHub repository sync and create a private repository. Do not use Ask Astra's repository. No plan upgrade is authorized by these instructions.
2. In the GitHub browser interface, copy `native/android/build-android.yml` into `.github/workflows/build-android.yml` on the default branch, using Add file → Create new file. Base44's sync cannot activate Actions workflow files itself. Chrome's Desktop site view can expose GitHub's full controls on a phone.
3. Open Actions → Build Android AAB → Run workflow. This is the ONLY trigger; saving files does not run a build. The workflow uses a standard Ubuntu runner, times out after 25 minutes, disables caches and retains artifacts for one day.
4. After a successful run, download `arielux-phone-test`, unzip it on the phone and install its `.apk`. Permit installation from that browser/file manager if Android asks. An AAB cannot be installed directly; the included debug AAB is only a build artifact, not a release for Play.

The workflow compiles both debug and release variants. Android's standard debug signing needs no production secrets. The release bundle stays unsigned and is not uploaded. Nothing is deployed, submitted or published.

Use GitHub's included free minutes and artifact-storage allowance; leave paid overages disabled and stop if no free allowance remains. A private repository avoids exposing Arielux's source publicly. GitHub Free currently includes 2,000 runner minutes/month and 500 MB shared artifact storage, subject to the account's remaining quota: https://docs.github.com/en/billing/concepts/product-billing/github-actions.

**Actual build status: NOT RUN.** No native compiler is available in this workspace and no connected cloud build has run. Source corrections and available dependencies are not a successful build. Any real compiler errors must come from the Actions run and be resolved there before claiming verification.

A sideloaded test APK can establish installation and native behavior, not successful Google Play subscription purchases. Those need later approved Play test distribution and backend verification configuration. The web app's existing code, features and customer records remain untouched; complete native feature parity is not established by loading the website or compiling the wrapper.

Debug signing may differ between cloud runs, so reinstalling a later test APK may require removing the earlier test installation. That clears that installation's local login/storage, not the server's customer records. Never use debug signing as the permanent upload key.

## Toolchain

JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.10.1 and Android API 36 are selected in the existing project. The target API meets Google's documented requirement for submissions from August 31, 2026: https://developer.android.com/google/play/requirements/target-sdk.
No local computer or separate Android project is needed for the cloud build.

## Security — trusted origin enforcement

`MainActivity` implements the runtime allow-list from `OriginSecurity.md`:

- `window.PlayBilling` is injected **only** for `https://aura-your-muse.base44.app`
  (add a connected custom domain to the single `trustedOrigins` set if one is
  connected later).
- `WebViewClient.shouldOverrideUrlLoading` / `onPageStarted` strip the interface
  for any off-origin URL before the page can call it.
- File/content access is disabled (`allowFileAccess = false`,
  `allowContentAccess = false`, `allowFileAccessFromFileURLs = false`,
  `allowUniversalAccessFromFileURLs = false`); cleartext traffic is off
  (`network_security_config.xml`).
- Media permissions (`RECORD_AUDIO`, `CAMERA`) are requested at runtime and
  granted to the WebView only when the app holds them.

The Google service-account private key stays server-side only. No production credential is supplied to this test build. The package name is public app metadata, not a secret. The existing backend later uses `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` and `GOOGLE_PLAY_PACKAGE_NAME` after separate approval.

The legacy `@JavascriptInterface` is not independently isolated to the top frame; navigation checks alone do not prove that an embedded third-party frame cannot call it. This wrapper has NOT been verified as production-secure. External login, uploads, location and native notifications also require actual-device validation, not assumptions of full parity.

## Billing Library version (REQUIRED: 8+)

Google requires **Billing Library 8.0.0+** for all new apps/updates submitted
on/after **Aug 31 2026**. This project pins `com.android.billingclient:billing:9.1.0`.
The bridge uses the v8+ API (`ProductDetails`, `queryProductDetailsAsync`,
`BillingClient.ProductType`, `Purchase.products`).

> The bridge file notes that its exact v8/v9 accessors were not compiled in this
> project. The GitHub Actions build is the verification step — the first
> successful CI run confirms it compiles against the pinned version.

## Product IDs (must match Play Console + the server catalog)

These IDs are defined in `base44/shared/iapFulfillment.ts` and must be created
**identically** in the Google Play Console (Monetize → Products). **Do not
change them** — they match existing Stripe products and customer records.

| Play product ID            | Type          | Price    | Grants            |
|----------------------------|---------------|----------|-------------------|
| arielux_premium_monthly    | Subscription  | $9.99/mo | Premium tier      |
| arielux_plus_monthly       | Subscription  | $19.99/mo| Plus tier         |
| arielux_intimate_monthly   | Subscription  | $49.99/mo| Intimate tier     |
| arielux_credits_299        | One-time      | $2.99    | 300 credits       |
| arielux_credits_499        | One-time      | $4.99    | 550 credits       |
| arielux_credits_999        | One-time      | $9.99    | 1200 credits      |
| arielux_credits_1999       | One-time      | $19.99   | 2600 credits      |

## Server-side fulfillment (unchanged, all server-side)

The JS bridge (`useBilling` / `usePlayBilling`) calls `iapFulfill` with the
purchase token **before** acknowledging/consuming. `iapFulfill` verifies the
token against the Google Play Developer API and grants the tier/credits
(idempotent on the order id), also checking the obfuscated account id against
the session user. The client acknowledges (subscriptions) or consumes (credit
packs) only after the server returns `verified: true`. `playWebhook` receives
RTDN via Cloud Pub/Sub push and handles renewals, cancellations, expirations,
grace periods, holds, pauses, revocations, and refunds idempotently.

Two Base44 secrets are required for `iapFulfill` / `playWebhook` to function
(set in Base44 → Settings → Secrets, **not** GitHub):

- `GOOGLE_PLAY_PACKAGE_NAME` — the app's final applicationId
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` — the Play service-account JSON

Until both are set, both functions return 503 (no entitlement granted).

## Real-time Developer Notifications (Pub/Sub) setup

1. Create a Pub/Sub topic + a **push subscription** in Google Cloud.
2. Set the push endpoint to
   `https://aura-your-muse.base44.app/functions/playWebhook`.
3. Configure authenticated push with OIDC tokens, using the Play service
   account as the token creator; set the push **audience** to the endpoint URL.
4. In the Google Play Console (Monetize → Developer notifications), point RTDN
   at the Pub/Sub topic.

`playWebhook` validates the OIDC JWT (issuer, audience, `email_verified`,
pinned service-account email) before processing any notification.

## Status / next actions

- Existing project reused; known source-level build blockers corrected, with real compilation still pending.
- Arielux is separate from Ask Astra; no Arielux Play listing is assumed or created.
- `com.arielux.android` remains provisional and unchanged. Approve the permanent ID before the first Play upload; retain it for all updates to that listing.
- After separate approval only: production signing, Play products, backend verification credentials, notification configuration and Play test/release submission.
- Retain an encrypted backup of the upload keystore, alias and passwords outside CI, plus the final package name and public signing-certificate fingerprints. GitHub Secrets are not the only backup.