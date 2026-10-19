#!/usr/bin/env bash
# Creates Pacer's fixed signing key and stores it as GitHub repository secrets.
# Needs only openssl and an authenticated gh CLI. Nothing secret is printed.
#
# The key is also kept in ~/.pacer-signing so you can restore the secrets later
# (re-running this script re-uploads the existing key rather than making a new one).
# Losing the key means the next APK will not install over the old one, and
# uninstalling to get past that deletes your run history.
set -euo pipefail

REPO="${1:-arkanemystic/custom-run-app}"
DIR="${PACER_KEY_DIR:-$HOME/.pacer-signing}"
P12="$DIR/pacer-debug.p12"
PASSFILE="$DIR/password"
ALIAS="pacer"

mkdir -p "$DIR"
chmod 700 "$DIR"
umask 077

if [[ -f "$P12" && -f "$PASSFILE" ]]; then
  echo "Reusing existing key in $DIR"
else
  openssl rand -hex 24 | tr -d '\n' > "$PASSFILE"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  openssl req -x509 -newkey rsa:2048 -nodes -days 10000 -subj "/CN=Pacer Debug" \
    -keyout "$TMP/key.pem" -out "$TMP/cert.pem" 2>/dev/null
  PACER_PASS="$(cat "$PASSFILE")" openssl pkcs12 -export -name "$ALIAS" \
    -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -out "$P12" -passout env:PACER_PASS
  echo "Created new key in $DIR (back this folder up somewhere safe)"
fi

openssl base64 -A -in "$P12" | gh secret set DEBUG_KEYSTORE_BASE64 -R "$REPO"
gh secret set DEBUG_KEYSTORE_PASSWORD -R "$REPO" < "$PASSFILE"
printf '%s' "$ALIAS" | gh secret set DEBUG_KEY_ALIAS -R "$REPO"
echo "Secrets DEBUG_KEYSTORE_BASE64, DEBUG_KEYSTORE_PASSWORD, DEBUG_KEY_ALIAS set on $REPO"
