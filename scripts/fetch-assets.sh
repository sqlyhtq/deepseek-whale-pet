#!/usr/bin/env bash
# 拉取构建所需、但不放进仓库的依赖（第三方二进制 + 模型）
set -euo pipefail
cd "$(dirname "$0")/.."
B="${B:-$(pwd)/../build}"
mkdir -p "$B/vosk"

M=https://repo1.maven.org/maven2

fetch_aar() {  # $1=url $2=target-dir
  local url="$1" dir="$2" name; name="$(basename "$url")"
  mkdir -p "$dir"
  if [ -f "$dir/classes.jar" ]; then echo "已有 $dir"; return; fi
  echo "下载 $name"
  curl -fsSL "$url" -o "$B/vosk/$name"
  local tmp; tmp="$(mktemp -d)"
  unzip -q -o "$B/vosk/$name" -d "$tmp"
  [ -f "$tmp/classes.jar" ] && cp "$tmp/classes.jar" "$dir/classes.jar"
  [ -d "$tmp/jni" ] && cp -r "$tmp/jni" "$dir/jni"
  rm -rf "$tmp"
}

# 语音识别
fetch_aar "$M/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar" "$B/vosk/aar"
# JNA（Vosk 的 native 桥）
fetch_aar "$M/net/java/dev/jna/jna/5.13.0/jna-5.13.0.aar" "$B/vosk/jna"
# Shizuku 权限调用
for m in api provider aidl shared; do
  fetch_aar "$M/dev/rikka/shizuku/$m/13.1.5/$m-13.1.5.aar" "$B/vosk/shizuku/$m"
done

# r8（D8 dexer）与 apksigner
[ -f "$B/r8.jar" ] || curl -fsSL "$M/com/android/tools/r8/8.2.42/r8-8.2.42.jar" -o "$B/r8.jar" || true
[ -f "$B/apksigner.jar" ] || echo "提示：apksigner 可用 Android build-tools 自带的 lib/apksigner.jar，路径在 build.sh 里改 APKSIGNER"

# Vosk 中文小模型（66MB，Apache-2.0）
if [ ! -d assets/vosk ]; then
  echo "下载 Vosk 中文小模型…"
  curl -fL https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip -o /tmp/vosk-small.zip
  unzip -q /tmp/vosk-small.zip -d /tmp/vosk-small
  mv /tmp/vosk-small/vosk-model-small-cn-0.22 assets/vosk
  rm -rf /tmp/vosk-small.zip /tmp/vosk-small
fi

echo "完成：$B/vosk 与 assets/vosk"
