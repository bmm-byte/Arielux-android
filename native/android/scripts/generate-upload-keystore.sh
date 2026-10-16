#!/usr/bin/env bash
# One-time local helper (macOS/Linux). Generates the Arielux Google Play UPLOAD
# keystore, base64-encodes it, and registers the four signing secrets the
# build-signed-aab workflow job reads from GitHub.
#
# GATE: run ONLY after confirming in the Google Play Console that no app listing
# exists for package com.arielux.android. Internal testing listings are not
# visible publicly, so the Console is the only authoritative check.
#
# The private key never leaves this machine. Passwords are read silently and are
# never printed. Keep arielux-upload.jks as your permanent offline backup.

set -uo pipefail

REPO="bmm-byte/Arielux-android"
KEYSTORE="arielux-upload.jks"
ALIAS="arielux"

command -v keytool >/dev/null 2>&1 || { echo "keytool (JDK) is required. Install a JDK first."; exit 1; }

echo "GATE: Have you confirmed in the Google Play Console that no listing"
echo "exists for com.arielux.android (including Internal testing)?"
read -r -p "Type 'verified' to continue: " gate
if [ "$gate" != "verified" ]; then
  echo "Aborting. Verify in the Play Console first, then re-run."
  exit 1
fi

read -r -s -p "Keystore password: " STORE_PASS; echo
read -r -s -p "Key password: " KEY_PASS; echo
if [ -z "$STORE_PASS" ] || [ -z "$KEY_PASS" ]; then
  echo "Passwords cannot be empty."
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=Arielux Upload, O=Arielux, C=US" || { echo "keytool failed."; exit 1; }

echo
echo "Keystore generated: $KEYSTORE (keep this file as your permanent backup)."
B64="$(base64 "$KEYSTORE" | tr -d '\n')"

GH_OK=0
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  printf '%s' "$B64"         | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$REPO" && \
  printf '%s' "$STORE_PASS"  | gh secret set STORE_PASSWORD          --repo "$REPO" && \
  printf '%s' "$KEY_PASS"    | gh secret set KEY_PASSWORD            --repo "$REPO" && \
  printf '%s' "$ALIAS"       | gh secret set KEY_ALIAS               --repo "$REPO" && GH_OK=1
fi

if [ "$GH_OK" = 1 ]; then
  echo "All four signing secrets registered for $REPO."
else
  echo "gh could not set the secrets. Add these four repository secrets manually"
  echo "in GitHub (Settings -> Secrets and variables -> Actions):"
  echo "  SIGNING_KEYSTORE_BASE64, STORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS (= $ALIAS)"
  echo "SIGNING_KEYSTORE_BASE64 (copy the whole line):"
  echo "$B64"
fi

echo
echo "Next: build the signed AAB:"
echo "  gh workflow run build-android.yml --repo $REPO -f signed_release=true"
echo "Then download the signed-aab-latest release and upload it to the Play Console"
echo "Internal testing track (NOT Production)."
