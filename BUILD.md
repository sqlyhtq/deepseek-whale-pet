# 本地构建说明

> 上游仓库 README 里写的 `bash build/build.sh` **在源码中不存在**：`build/` 被 `.gitignore` 排除，
> 作者没有把打包脚本提交上来。本文件与 `build-local.sh` 是照着源码与依赖关系重建的等价流程。

## 环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `javac` / `keytool` |
| Android SDK Platform | 34 | 提供 `android.jar` |
| Android Build-Tools | 34.0.0 | 提供 `aapt2` / `d8` / `zipalign` / `apksigner` |

`build-local.sh` 按以下顺序定位 SDK：环境变量 `ANDROID_SDK_ROOT` →
默认回退到 `../.android-build/sdk`（与仓库同级的 `Code/.android-build/sdk`）。

依赖 AAR 默认放 `../.android-build/deps`（可用 `DEPS=` 覆盖），缺失时自动从 Maven Central 下载。

## 依赖 AAR

从 Maven Central 取，解包后只用到 `classes.jar` 与 `jni/*.so`：

```
com.alphacephei:vosk-android:0.3.47
net.java.dev.jna:jna:5.13.0
dev.rikka.shizuku:api|provider|aidl|shared:13.1.5
```

（`build-local.sh` 会自动完成这一步。）

## 语音模型

两个变体的区别只在语音模型：

| 变体 | 命令 | 内置内容 | 体积 |
|---|---|---|---|
| 小模型版 | `bash build-local.sh` | `assets/vosk/` = 65 MB 小模型 | ~76 MB |
| 大模型版（暂停出包） | `VARIANT=big bash build-local.sh` | 小模型 + `assets/vosk-big.zip` = 1.36 GB | ~1.4 GB |

- **小模型**：`vosk-model-small-cn-0.22`，解压后约 65 MB，可直接用。
- **大模型**：`vosk-model-cn-0.22`，来自 `../.android-build/models/model-cn.zip`（可用 `BIGZIP=` 覆盖）。
  大模型版首次启动会把 `assets/vosk-big.zip` 流式解压到 `filesDir/vosk-cn-big`，**不联网**；
  需要额外约 2.0 GB 存储（解压后 2.00 GB），加载时约 3 GB 内存，低内存机型会回退到内置小模型。
- 两个模型目录都已被 `.gitignore` 排除。

## 构建

```bash
bash build-local.sh                 # 小模型版 → dafeiyu-small-debug.apk
VARIANT=big bash build-local.sh     # 大模型版 → dafeiyu-big-debug.apk
# debug.keystore 首次运行时自动生成
```

## 一个容易踩的坑

`d8` 的输入必须同时包含**依赖 jar**，否则打出的 `classes.dex` 只有应用自身类，
运行到 `org.vosk.*` / `rikka.shizuku.*` / `com.sun.jna.*` 时会直接 `NoClassDefFoundError`：

```bash
# 正确
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt" "$OUT"/libs/*.jar
```

## 本地补丁（相对上游 v1.2.0）

上游这些问题都是实测确认的，改动都带了注释：

| # | 文件 | 改动 | 原因 |
|---|---|---|---|
| 1 | `build-local.sh` | `--target-sdk-version 33` | 与官方发布包一致；34 在 Android 14 上会触发麦克风前台服务限制，实测可让后台录音被静音（全 0 数据） |
| 2 | `BigModel.java` | 镜像优先 + 官方源兜底 | 官方源实测 9.8 KB/s，1.36 GB 要 40 小时 |
| 3 | `BigModel.java` | HTTP Range 断点续传 | 上游直接截断重写，断一次从 0 开始 |
| 4 | `BigModel.java` | 解压后按“含 `am/` 的顶层目录”识别 | 官方包顶层叫 `vosk-model-cn-0.22`，镜像叫 `model-cn`，上游写死找 `vosk-cn-big`，**下载完也装不上** |
| 5 | `BigModel.java` + `PetService.java` | 支持 `assets/vosk-big.zip` 首次启动流式解压 | 大模型版 APK，免下载 |
| 6 | `MainActivity.java` + `PetService.java` | 启停按钮即时反馈 + 状态回调 + 400 ms 轮询 | 上游在 `stopService()` 后立刻读静态 `isRunning`，此时服务还没销毁，按钮文案会滞后/与实际不符 |
| 7 | `MainActivity.java` | `setEnabled` 改由服务 `onCreate` 落定；`startPet` 加异常兜底 | 上游先写 `enabled=true` 再启动，启动失败会留下“已开启”的假状态；`onResume` 还会反复自动拉起 |
| 8 | `MainActivity.java` | 关闭时先 `setEnabled(false)` 再 `stopService` | 避免 `onResume` 的自动拉起和关闭操作打架 |
| 9 | `WakeWord.java` | 唤醒词改为「大肥鱼」，逐字同音容错（打/达、非/飞/菲、于/余/渝…） | 小模型识别同音字很常见，只认字面会喊不动 |
| 10 | `MainActivity.java` | 大模型下载完成后自动切 `asrModel=big` + `VoiceInput.reset()` + 重启唤醒 | 上游只改按钮文案，下完不重载，等于没生效 |
| 11 | `AndroidManifest.xml` | `versionCode=2`、`versionName=1.3.0-beta1` | 预发布版本号，支持覆盖安装更新 |
| 12 | `ChatPanel.java` | 关闭按钮 44dp、右移 30dp；缩放手柄 40→26dp | 右上角缩放手柄 elevation 更高，正好盖住 ✕，实测几乎点不到 |
| 13 | `VoiceInput.java` + `MainActivity`/`PetService` | 启动即预解压内置语音模型（加锁防并发） | 首次说话才解压要等几十秒 |
| 14 | `MainActivity.java` | 大模型未就绪时自动回落小模型；未下载时禁止选大模型 | 默认值是 big，小模型版会显示错误状态 |
| 15 | `WakeWord.java` + `MainActivity.java` | 自定义唤醒词 `主词\|别名`，存本机 SharedPreferences | 小模型识别差，用户可换词并补同音别名 |
| 16 | `AndroidManifest.xml` | `versionCode=3`、`versionName=1.3.0-beta2` | 覆盖更新 |
| 17 | 目录清理 | 删除 1.3 GB 大模型 APK、`build-local/`、`.idsig` | 无效产物 |
| 18 | `VoiceInput` + `ChatPanel` | 启动预热加载小模型；已加载时显示“正在听…”而非“正在加载模型” | 聊天窗点麦克风才加载，提示误导 |
| 19 | `MainActivity` | 设置页与自检新增“当前生效：大/小模型 · 已加载” | 无法确认实际用哪个模型 |
| 20 | `WakeWord` | 标点/空格归一 + 编辑距离 ≤1 模糊匹配（3 字以上） | 唤醒词识别不稳定 |
| 21 | `AndroidManifest.xml` | `versionCode=4`、`versionName=1.3.0-beta3` | 覆盖更新 |

验证：未改版本号时 `AndroidManifest.xml` 与官方 APK **逐字节相同**（CRC32 `789422618`，7936 字节）；
第 11 条改版本号后长度变为 7952 字节，差异仅 versionCode/versionName。
`classes.dex` 因重新编译而不同。

## 与原版 APK 的差异

- 本地 debug 签名（`CN=Android Debug`），与官方 Release 签名不同，
  **安装前需先卸载已安装的官方版本**，否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 包含 `arm64-v8a` 与 `armeabi-v7a` 两套原生库；只保留 `arm64-v8a` 可再小约 8 MB。
- 官方 APK 里还有 29 张 `assets/live2d/whale/outfits/**` 换装贴图（被 `.gitignore` 排除，
  源码里拿不到）。运行时只用默认 `maid` 服装，缺这些不影响显示。
