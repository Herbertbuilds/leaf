#!/usr/bin/env bash
#
# Build Leaf without Gradle: aapt2 -> kotlinc -> d8 -> zipalign -> apksigner.
# Needs a JRE/JDK 17, Android build-tools 34, platform 34 and the Kotlin compiler.
# See README.md for the one-time toolchain setup.
#
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TC="${LEAF_TOOLCHAIN:-$HOME/.cache/leaf-toolchain}"
BUILD="$PROJ/build"
DIST="$PROJ/dist"

JAVA_HOME_DIR="$(ls -d "$TC"/jdk-* 2>/dev/null | head -1 || true)"
BT="$TC/sdk/build-tools/34.0.0"
PLATFORM="$TC/sdk/platforms/android-34/android.jar"
KOTLINC="$TC/kotlinc/bin/kotlinc"
STDLIB="$TC/kotlinc/lib/kotlin-stdlib.jar"

for p in "$JAVA_HOME_DIR" "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$PLATFORM" "$KOTLINC" "$STDLIB"; do
  if [ -z "$p" ] || [ ! -e "$p" ]; then
    echo "Missing toolchain piece: ${p:-JRE/JDK}" >&2
    echo "See README.md -> 'Building it yourself'." >&2
    exit 1
  fi
done

export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME_DIR/bin:$PATH"

# Single source of truth for the version: the manifest. Keeping the numbers here as well
# meant the APK name, the info sheet and the README could (and did) drift apart.
MANIFEST="$PROJ/AndroidManifest.xml"
VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "Could not read versionCode/versionName from $MANIFEST" >&2
  exit 1
fi
APK="$DIST/Leaf-$VERSION_NAME.apk"

echo "==> resources"
rm -rf "$BUILD"
mkdir -p "$BUILD/dex" "$DIST"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$BUILD/res.zip"

echo "==> link manifest"
"$BT/aapt2" link \
  -o "$BUILD/app.unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$MANIFEST" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "==> kotlin"
"$KOTLINC" "$PROJ/src" \
  -classpath "$PLATFORM" \
  -jvm-target 11 \
  -nowarn \
  -d "$BUILD/classes.jar"

echo "==> dex (d8)"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" \
  --output "$BUILD/dex" \
  "$BUILD/classes.jar" "$STDLIB"

echo "==> package"
python3 - "$BUILD/app.unsigned.apk" "$BUILD/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

echo "==> align"
"$BT/zipalign" -f 4 "$BUILD/app.unsigned.apk" "$BUILD/app.aligned.apk"

echo "==> sign"
KS="$PROJ/leaf-release.keystore"
KS_TYPE=""
if [ ! -f "$KS" ]; then
  if command -v keytool >/dev/null 2>&1; then
    keytool -genkeypair -keystore "$KS" -alias leaf \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass leaf-leaf-leaf -keypass leaf-leaf-leaf \
      -dname "CN=Leaf, O=Personal, C=US" >/dev/null 2>&1
  else
    # No JDK keytool around: build the same self-signed key with openssl.
    tmp="$(mktemp -d)"
    openssl req -x509 -newkey rsa:2048 -nodes \
      -keyout "$tmp/key.pem" -out "$tmp/cert.pem" -days 10000 \
      -subj "/CN=Leaf/O=Personal/C=US" >/dev/null 2>&1
    openssl pkcs12 -export -inkey "$tmp/key.pem" -in "$tmp/cert.pem" \
      -out "$KS" -name leaf -passout pass:leaf-leaf-leaf >/dev/null 2>&1
    rm -rf "$tmp"
    KS_TYPE="PKCS12"
  fi
  echo "    generated $KS (alias: leaf, password: leaf-leaf-leaf) — keep this file"
fi

SIGN_ARGS=(--ks "$KS" --ks-key-alias leaf
  --ks-pass pass:leaf-leaf-leaf --key-pass pass:leaf-leaf-leaf
  --min-sdk-version 26)
if [ -n "$KS_TYPE" ]; then SIGN_ARGS+=(--ks-type "$KS_TYPE"); fi

"$BT/apksigner" sign "${SIGN_ARGS[@]}" --out "$APK" "$BUILD/app.aligned.apk"

echo "==> verify"
"$BT/apksigner" verify --print-certs "$APK" | head -4
"$BT/aapt2" dump badging "$APK" | head -6
ls -lh "$APK"
echo "done: $APK"
