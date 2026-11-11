#!/bin/bash
# 莫奈备忘录手动构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 无 Gradle / 无外部依赖，全部使用已安装的 Android SDK 工具链离线构建。
set -e
cd "$(dirname "$0")"
# 工具链：优先使用仓库旁的便携工具链（开发机），否则使用环境变量 JAVA_HOME / ANDROID_HOME
if [ -f ~/workspace/android-toolchain/env.sh ]; then
  source ~/workspace/android-toolchain/env.sh
fi
: "${JAVA_HOME:?请设置 JAVA_HOME（JDK 17）}"
: "${ANDROID_HOME:?请设置 ANDROID_HOME（Android SDK，含 platform 34 与 build-tools 34.0.0）}"
export PATH="$JAVA_HOME/bin:$PATH"

BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1. aapt2 compile + link =="
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml "$OUT/res.zip" \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 43 --version-name 1.42

echo "== 2. javac =="
find java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 \
    -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 3. d8 =="
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== 4. 打入 dex 并对齐 =="
cd "$OUT"
zip -j app-unsigned.apk dex/classes.dex > /dev/null
cd ..
"$BT/zipalign" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== 5. 签名 (debug keystore) =="
if [ ! -f debug.keystore ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore debug.keystore -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/monet-notes.apk" "$OUT/app-aligned.apk"

cp "$OUT/monet-notes.apk" "莫奈备忘录-debug.apk"
cp "$OUT/monet-notes.apk" "monet-notes-debug.apk"
echo "== 构建成功 =="
ls -lh "莫奈备忘录-debug.apk"
"$BT/apksigner" verify --print-certs "$OUT/monet-notes.apk" | head -3
"$BT/aapt2" dump badging "$OUT/monet-notes.apk" | head -4
