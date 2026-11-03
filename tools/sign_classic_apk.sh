#!/usr/bin/env bash
set -euo pipefail
: "${CLASSIC_SIGNING_KEYSTORE:?Set the encrypted repository signing secret}"
apk_path=${1:?Usage: sign_classic_apk.sh path/to/app.apk}
repo_root=$(cd "$(dirname "$0")/.." && pwd)
apksigner_path="${ANDROID_HOME:?}/build-tools/35.0.0/apksigner"
signing_tmp=$(mktemp -d)
trap 'rm -rf "$signing_tmp"' EXIT
umask 077
printf '%s' "$CLASSIC_SIGNING_KEYSTORE" | base64 --decode > "$signing_tmp/classic.p12"
unset CLASSIC_SIGNING_KEYSTORE
"$apksigner_path" sign --ks "$signing_tmp/classic.p12" --ks-type PKCS12 \
  --ks-key-alias classic-era --ks-pass pass:android --key-pass pass:android \
  --v4-signing-enabled false --out "$signing_tmp/signed.apk" "$apk_path"
"$apksigner_path" verify --verbose --print-certs "$signing_tmp/signed.apk" > "$signing_tmp/verification.txt"
expected_cert=$(tr -d '\r\n' < "$repo_root/tools/classic-signing-cert.sha256")
actual_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$signing_tmp/verification.txt")
[[ "$actual_cert" == "$expected_cert" ]] || { echo 'Signing certificate does not match the pinned identity' >&2; exit 1; }
mv "$signing_tmp/signed.apk" "$apk_path"
echo "Verified persistent signing certificate: $actual_cert"
