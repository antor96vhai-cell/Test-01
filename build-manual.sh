#!/usr/bin/env bash
# Manual APK build pipeline for KUTU Voice (com.antor.kutuvoice).
# Same pipeline as the KUTU Phone Agent build (~/workspace/phone-agent/app/
# build-manual.sh): Gradle's daemon cannot run in this VM (sandbox loopback
# restriction), so only sequential, file-based tool invocations are used.
#
# Signing: reuses the shared KUTU keystore from the phone-agent project
# (~/workspace/phone-agent/hello/kutu-release.keystore).
# Credentials come from env: KUTU_STORE_PW, KUTU_KEY_PW, KUTU_KEY_ALIAS.
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/home/hatch/workspace/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-/home/hatch/workspace/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
JAVAC="$JAVA_HOME/bin/javac"

APP_ID="com.antor.kutuvoice"
MIN_SDK=26
TARGET_SDK=34
VERSION_CODE=1
VERSION_NAME="1.0"
KS="${KUTU_KS:-/home/hatch/workspace/phone-agent/hello/kutu-release.keystore}"
BUILD="$PROJ/build/manual"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

# 1) Compile resources
"$BT/aapt2" compile --dir "$PROJ/src/main/res" -o "$BUILD/res.zip"

# 2) Manifest copy with the legacy package attribute injected
#    (aapt2 link needs it; the source manifest keeps the AGP style)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'"$APP_ID"'">|' \
    "$PROJ/src/main/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
grep -q "package=\"$APP_ID\"" "$BUILD/AndroidManifest.xml"

# 3) Link APK (resources + manifest), generating R.java
"$BT/aapt2" link \
    -o "$BUILD/app-unsigned.apk" \
    -I "$AJAR" \
    --manifest "$BUILD/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD/res.zip"

# 4) Compile Java sources (app code + generated R) against android.jar
"$JAVAC" -source 17 -target 17 -encoding UTF-8 \
    -cp "$AJAR" -d "$BUILD/classes" \
    $(find "$PROJ/src/main/java" "$BUILD/gen" -name '*.java')

# 5) Dex
"$BT/d8" --lib "$AJAR" --min-api "$MIN_SDK" \
    --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class')

# 6) Add classes.dex to the APK
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/app-with-dex.apk" classes.dex)

# 7) Align
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

# 8) Sign with the shared KUTU keystore (credentials from env)
: "${KUTU_STORE_PW:?set KUTU_STORE_PW} ${KUTU_KEY_PW:?set KUTU_KEY_PW}"
OUT="$PROJ/build/outputs"
mkdir -p "$OUT"
"$BT/apksigner" sign \
    --ks "$KS" \
    --ks-key-alias "${KUTU_KEY_ALIAS:-kutu}" \
    --ks-pass "env:KUTU_STORE_PW" \
    --key-pass "env:KUTU_KEY_PW" \
    --out "$OUT/kutu-voice.apk" \
    "$BUILD/app-aligned.apk"

echo "BUILT: $OUT/kutu-voice.apk"
