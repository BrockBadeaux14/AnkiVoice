#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
sdk=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
jdk=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
export JAVA_HOME="$jdk"
bt="$sdk/build-tools/36.0.0"
out="$repo/build/av006/probe"
mkdir -p "$out/classes" "$out/dex" "$out/assets"
cp "$repo/fixtures/providers/av006-corpus.json" "$out/assets/av006-corpus.json"
"$jdk/bin/javac" --release 8 -classpath "$sdk/platforms/android-36/android.jar" \
  -d "$out/classes" "$repo/tools/av006-probe/ProbeActivity.java"
"$bt/d8" --lib "$sdk/platforms/android-36/android.jar" --output "$out/dex" "$out"/classes/org/ankivoice/av006/*.class
"$bt/aapt2" link -I "$sdk/platforms/android-36/android.jar" -A "$out/assets" --manifest "$repo/tools/av006-probe/AndroidManifest.xml" -o "$out/unsigned.apk"
(cd "$out/dex" && zip -q -u "$out/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
if [ ! -f "$out/debug.keystore" ]; then
  "$jdk/bin/keytool" -genkeypair -keystore "$out/debug.keystore" -storepass android -keypass android \
    -alias av006 -dname 'CN=AV006 Disposable Probe' -keyalg RSA -validity 3650
fi
"$bt/apksigner" sign --ks "$out/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$out/av006-probe.apk" "$out/aligned.apk"
"$bt/apksigner" verify "$out/av006-probe.apk"
printf '%s\n' "$out/av006-probe.apk"
