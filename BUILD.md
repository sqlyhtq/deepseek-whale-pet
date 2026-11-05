# 本地构建说明

仓库自带 `build-local.sh`，不依赖 Android Studio，从源码直接打出可安装的 APK。

## 环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `javac` / `keytool` |
| Android SDK Platform | 34 | 提供 `android.jar` |
| Build-Tools | 34.0.0 | 提供 `aapt2` / `d8` / `zipalign` / `apksigner` |

SDK 定位顺序：环境变量 `ANDROID_SDK_ROOT` → 默认回退到 `../.android-build/sdk`。

依赖 AAR（Vosk、JNA、Shizuku）默认放 `../.android-build/deps`（可用 `DEPS=` 覆盖），
缺失时脚本会自动从 Maven Central 下载。

## 用法

```bash
bash scripts/fetch-assets.sh     # 拉取 Vosk 小模型（66MB）
bash build-local.sh              # 小模型版 → dafeiyu-small-debug.apk
bash build-local.sh big          # 大模型版（需 assets/vosk-big.zip 或 BIGZIP=...）
```

构建中间产物在 `build-local/`。

## 签名与覆盖安装

脚本首次运行会生成 `debug.keystore`（别名 `androiddebugkey`，密码 `android`），
它与官方 Release 的 keystore **不同**：

- 官方版 → 自编译版：**签名冲突**，必须先 `adb uninstall com.dsh.whalepet`
  （会丢本地设置、聊天记录与记忆）
- 自编译版之间互相覆盖：可以，只要 `debug.keystore` 没被删
- 想无缝覆盖官方版：把官方 keystore 放到脚本同级，并改签名参数：

```bash
# 修改脚本末尾的 apksigner 调用
--ks pet.keystore --ks-pass pass:android --ks-key-alias pet
```

## 语音模型

- **小模型**：`vosk-model-small-cn-0.22`，约 65MB，直接内置在 `assets/vosk/`
- **大模型**：`vosk-model-cn-0.22`，约 1.36GB 压缩包；大模型版 APK 会把
  `assets/vosk-big.zip` 在首次启动时流式解压到 `filesDir/vosk-cn-big`（不联网），
  解压后约 2.0GB 存储、加载约 3GB 内存；低内存机型会自动回退到内置小模型
- 两个模型目录都不进仓库（体积原因）

## 一个容易踩的坑

`d8` 的输入必须**同时包含依赖 jar**，否则 `classes.dex` 里只有应用自身的类，
运行到 `org.vosk.*` / `rikka.shizuku.*` / `com.sun.jna.*` 时会 `NoClassDefFoundError`：

```bash
# 正确写法
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt" "$OUT"/libs/*.jar
```

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名与已安装版本不一致 | 先卸载旧版 |
| `NoClassDefFoundError: org.vosk.*` | d8 漏了依赖 jar | 见上一节 |
| 大模型下载不动 | 官方源限速 | 用镜像或本地 `vosk-big.zip` |
| 首次说话等很久 | 模型未预解压 | 新版本已在启动时预解压 |
