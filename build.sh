#!/usr/bin/env bash
# Builds build/ii-tools.apk with the plain SDK tools (no Gradle).
# Usage: ./build.sh [--install]
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PLATFORM="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
KEYSTORE="${IITOOLS_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${IITOOLS_KS_PASS:-android}"
KS_ALIAS="${IITOOLS_KS_ALIAS:-androiddebugkey}"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res/compiled.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml --java "$OUT/gen" "$OUT/res/compiled.zip" --auto-add-overlay

javac -nowarn -Xlint:-options --release 17 -classpath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -u ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED" "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --out "$OUT/ii-tools.apk" "$OUT/aligned.apk"
echo "built $OUT/ii-tools.apk"

if [ "${1:-}" = "--install" ]; then
    adb install -r "$OUT/ii-tools.apk"
    # Sideloaded apps cannot flip these switches from the UI on Android 13+
    # without "Allow restricted settings"; over adb it just works.
    adb shell pm grant dev.ii.tools android.permission.POST_NOTIFICATIONS || true
    adb shell cmd notification allow_listener dev.ii.tools/dev.ii.tools.LiveListener || true
fi
