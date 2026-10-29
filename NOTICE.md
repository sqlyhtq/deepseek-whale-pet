# 第三方组件与授权

本项目**不含任何 API Key**，密钥只保存在用户手机的 `SharedPreferences` 里。

## 运行时依赖（打包进 APK）

| 组件 | 版本/来源 | 授权 | 用途 |
| --- | --- | --- | --- |
| Vosk API | `com.alphacephei:vosk-android` | Apache-2.0 | 离线语音识别 |
| Vosk 中文小模型 | `vosk-model-small-cn-0.22` | Apache-2.0 | 内置识别模型（未勾选大模型时的默认） |
| Vosk 中文大模型 | `vosk-model-cn-0.22` | Apache-2.0 | 用户可选，**不随仓库分发**，由用户在 App 内下载 |
| JNA | `net.java.dev.jna` | Apache-2.0 / LGPL-2.1（双许可） | Vosk 的 native 桥 |
| Shizuku API | `dev.rikka.shizuku` | Apache-2.0 | 读取前台应用、shell 启动应用 |
| 鲸鱼娘 Live2D 式骨骼素材 | [Pal AI Lab / Coopanion](https://github.com/Pal-AI-Lab/Coopanion) → `packages/cortico-world-desktop-pet/web/` | **AGPL-3.0-or-later** | `assets/live2d/` 的渲染器与贴图 |
| three.js | r160 | MIT | `assets/3d/`（实验性 3D 版，默认未启用） |

## Live2D 素材的特别说明

`assets/live2d/` 下的代码（`kit/rig.js`、`kit/body.js`、`whale/figure.js`）与贴图
（`whale/tex/`、`whale/feat/`、`whale/model.json`）**不是本项目原创**，来自
Pal AI Lab 的 Coopanion 项目，授权为 **AGPL-3.0-or-later**。

因此**整个项目按 AGPL-3.0-or-later 发布**。自用不触发开源义务；一旦分发（包括发布 APK），
必须同样以 AGPL-3.0 提供完整源码。

## 素材/字体

- 精灵图（`assets/sprites/`）：本项目自制。
- 未使用任何商业字体；界面字体走系统字体回退。

## 不分发的内容

- 用户自己的 API Key、BreezeBlue Key（只在运行时由用户填写）
- `vosk-model-cn-0.22` 大模型（1.36GB，由用户在 App 内下载或自行放入）
