# Arielux identity and release approval gate

## Confirmed project identity

- Arielux is this separate Base44 project, published at https://aura-your-muse.base44.app.
- Ask Astra is the owner's existing published app. Its listing, application ID and signing keys must not be used for Arielux.
- An existing Arielux Google Play listing has NOT been established. Earlier statements assuming one, or claiming that it could never be updated, were unsupported and are superseded by this document.
- No listing has been created or altered by this work.

## Current application ID

`com.arielux.android` remains PROVISIONAL and unchanged in `app/build.gradle.kts`.
It is suitable for an initial local phone test, not a final identity decision.
Choose and approve Arielux's permanent application ID before the first Play upload.
After Play accepts that package name, all updates to that listing must retain it.
The website URL, subscription tiers and product IDs are separate from that choice.

## Signing information to keep for future updates

After explicit approval, enroll the final app in Google Play App Signing.
Google then manages the app-signing key and CI signs uploads with the upload key.
Keep an encrypted backup of the upload keystore, its alias, store password and key password, separate from the repository and CI artifacts.
Also retain the final package name and the public upload/app-signing certificate fingerprints in the owner's release records.
GitHub Secrets are for the build's access, not the only backup.
Use increasing version codes for future releases.
Android's automatically generated debug/test key is NOT the permanent upload key and must not be registered for production.

## Approval gate

The current workflow compiles the existing project and provides a debug-signed APK and debug AAB for verification only.
It does not require production secrets, create a listing, upload to Google Play or publish.
A successful compilation is not verification of native feature behavior, purchase fulfillment or store acceptance.
Final application ID, production signing, Play products and backend verification credentials remain deferred until the owner explicitly approves them.