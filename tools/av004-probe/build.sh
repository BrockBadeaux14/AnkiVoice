#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
sdk=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
jdk=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
export JAVA_HOME="$jdk"
bt="$sdk/build-tools/36.0.0"
out="$repo/build/av004/probe"
mkdir -p "$out/classes" "$out/dex"
"$jdk/bin/javac" --release 8 -classpath "$sdk/platforms/android-36/android.jar" \
  -d "$out/classes" "$repo/tools/av004-probe/ProbeActivity.java"
"$bt/d8" --lib "$sdk/platforms/android-36/android.jar" --output "$out/dex" "$out"/classes/org/ankivoice/av004/*.class
"$bt/aapt2" link -I "$sdk/platforms/android-36/android.jar" --manifest "$repo/tools/av004-probe/AndroidManifest.xml" -o "$out/unsigned.apk"
(cd "$out/dex" && zip -q -u "$out/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
if [ ! -f "$out/debug.keystore" ]; then
  "$jdk/bin/keytool" -genkeypair -keystore "$out/debug.keystore" -storepass android -keypass android \
    -alias av004 -dname 'CN=AV004 Disposable Probe' -keyalg RSA -validity 3650
fi
"$bt/apksigner" sign --ks "$out/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$out/av004-probe.apk" "$out/aligned.apk"
"$bt/apksigner" verify "$out/av004-probe.apk"
printf '%s\n' "$out/av004-probe.apk"
