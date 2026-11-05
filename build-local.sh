#!/usr/bin/env bash
# 从源码打包 APK。
#
# 上游 README 里写的 `bash build/build.sh` 在仓库中并不存在（build/ 被 .gitignore 排除），
# 本脚本是一个等价实现，产物与官方 Release 的 manifest 逐字节一致。
#
# 用法:
#   bash build-local.sh              # 小模型版（assets/vosk/ 内置 65MB 小模型）
#   bash build-local.sh big          # 大模型版（额外内置 assets/vosk-big.zip）
#
# 依赖: JDK 17、Android SDK Platform 34、Build-Tools 34.0.0
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

VARIANT="${1:-${VARIANT:-small}}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HERE/../.android-build/sdk}}"
DEPS="${DEPS:-$HERE/.deps}"
BIGZIP="${BIGZIP:-$HERE/assets/vosk-big.zip}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
OUT="build-local"
# 官方 Release 的 AndroidManifest 是 targetSdk=33，这里保持一致才能复现同样的包
TARGET_SDK="${TARGET_SDK:-33}"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

for f in "$BT/aapt2" "$AJ"; do
  [ -e "$f" ] || { echo "缺少: $f（用 ANDROID_SDK_ROOT 指定 SDK）"; exit 1; }
done

case "$VARIANT" in
  small) APK="dafeiyu-small-debug.apk" ;;
  big)   APK="dafeiyu-big-debug.apk" ;;
  *)     echo "VARIANT 只能是 small 或 big"; exit 1 ;;
esac

# 依赖 AAR：缺失时从 Maven Central 下载
AARS="vosk-android-0.3.47 jna-5.13.0 api-13.1.5 provider-13.1.5 aidl-13.1.5 shared-13.1.5"
need_deps=0
for a in $AARS; do [ -f "$DEPS/$a.aar" ] || need_deps=1; done
if [ "$need_deps" = "1" ]; then
  echo "[0/6] 下载依赖 AAR 到 $DEPS"
  M=https://repo1.maven.org/maven2
  mkdir -p "$DEPS"
  dl() { [ -f "$DEPS/$2" ] || curl -sfL -o "$DEPS/$2" "$1"; }
  dl "$M/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar" vosk-android-0.3.47.aar
  dl "$M/net/java/dev/jna/jna/5.13.0/jna-5.13.0.aar" jna-5.13.0.aar
  for m in api provider aidl shared; do
    dl "$M/dev/rikka/shizuku/$m/13.1.5/$m-13.1.5.aar" "$m-13.1.5.aar"
  done
fi

LINK_EXTRA=()
if [ "$VARIANT" = "big" ]; then
  [ -f "$BIGZIP" ] || { echo "缺少大模型 zip: $BIGZIP"; exit 1; }
  echo "[big] 内置大模型 $(du -h "$BIGZIP" | cut -f1)"
  cp "$BIGZIP" assets/vosk-big.zip
  trap 'rm -f "$HERE/assets/vosk-big.zip"' EXIT
  LINK_EXTRA+=(-0 .zip)
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/libs"

echo "[1/6] 编译资源"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "[2/6] 链接 APK 与生成 R.java (targetSdk=$TARGET_SDK, variant=$VARIANT)"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  -A assets \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version "$TARGET_SDK" \
  ${LINK_EXTRA[@]+"${LINK_EXTRA[@]}"} \
  "$OUT/res.zip"

echo "[3/6] 解出依赖 classes.jar"
for a in $AARS; do
  unzip -p "$DEPS/$a.aar" classes.jar > "$OUT/libs/$a.jar"
done

echo "[4/6] 编译 Java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
CP="$AJ"
for j in "$OUT"/libs/*.jar; do CP="$CP:$j"; done
"$JAVAC" -source 17 -target 17 -encoding UTF-8 -nowarn -classpath "$CP" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[5/6] dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# 注意：依赖 jar 必须一起作为输入，否则 dex 里只有应用自身的类
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt" "$OUT"/libs/*.jar

echo "[6/6] 打包 / 对齐 / 签名"
cp "$OUT/base.apk" "$OUT/app.apk"
(cd "$OUT" && zip -q -j app.apk dex/classes*.dex)

for abi in arm64-v8a armeabi-v7a; do
  mkdir -p "$OUT/lib/$abi"
  unzip -p "$DEPS/vosk-android-0.3.47.aar" "jni/$abi/libvosk.so" > "$OUT/lib/$abi/libvosk.so"
  unzip -p "$DEPS/jna-5.13.0.aar" "jni/$abi/libjnidispatch.so" > "$OUT/lib/$abi/libjnidispatch.so"
done
(cd "$OUT" && zip -q -0 -r app.apk lib)

"$BT/zipalign" -p -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"

if [ ! -f debug.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
rm -f "$APK"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/app-aligned.apk"
"$BT/apksigner" verify --min-sdk-version 26 "$APK" >/dev/null && echo "签名校验通过"

echo "产物: $HERE/$APK  ($(du -h "$APK" | cut -f1))"
