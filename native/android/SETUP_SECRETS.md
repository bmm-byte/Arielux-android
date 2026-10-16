# Android signing and secrets: no production setup yet

## Initial phone build

The manual workflow needs no repository secrets or production credentials.
Android's standard debug signing is used for the downloadable APK and debug AAB.
The release variant is compiled but remains unsigned and is not published or uploaded.
No custom keystore or password-generation step is included.

## Later release setup: explicit approval required

Before the first Play upload, approve the permanent Arielux application ID and production signing setup.
Keep encrypted, independently recoverable backups of the upload keystore, alias and both passwords; do not rely on CI secrets as the only copy.
Never paste private credentials into chat, source, workflow inputs, logs or artifacts.
When approved, signing access can use these existing names in GitHub repository Secrets:

| Secret | Purpose |
|---|---|
| SIGNING_KEYSTORE_BASE64 | Encoded upload keystore, still a secret rather than encryption |
| SIGNING_STORE_PASSWORD | Keystore password |
| SIGNING_KEY_ALIAS | Upload-key alias |
| SIGNING_KEY_PASSWORD | Upload-key password |

The test workflow does not read these secrets. Release signing will be enabled only after approval.
Use GitHub's secure Secrets form, never committed files, for the actual values.
Use Google Play App Signing; retain the final package name and public certificate fingerprints for future updates.

## Existing backend verification: separate from compilation

After approval, the existing billing backend requires GOOGLE_PLAY_PACKAGE_NAME and GOOGLE_PLAY_SERVICE_ACCOUNT_JSON in Base44's secure Secrets settings, not in the Android project or GitHub build.
Neither credential has been configured here.
Until verification is configured, an APK can demonstrate loading and native behavior but cannot establish working subscription purchases.
The existing notification destination remains https://aura-your-muse.base44.app/functions/playWebhook.
No Play Console configuration or production service-account setup is performed by the build.