# 鲸鱼娘桌宠 · DeepSeek 语音助手

Android 桌面上的鲸鱼女仆：只在桌面显示，可拖动、可玩耍，双击戳她、叫她就能说话。

> **授权：AGPL-3.0-or-later。** 详见 [LICENSE](LICENSE)。
> 项目使用了 Pal AI Lab / Coopanion 的 Live2D 式骨骼素材，因此整体以 AGPL-3.0 发布，
> 引用链与贴图授权说明见 [NOTICE.md](NOTICE.md)。
>
> 「鲸鱼娘」形象源自 B 站 DeepSeek 二创，本项目是**非官方同人作品**，与 DeepSeek 官方无关，不得用于商业用途。
> **本项目不含任何 API Key。**

---

## 功能

### 桌宠
- Live2D 式分层骨骼立绘（呼吸、眨眼、发丝与尾巴摆动）
- 桌面漫游：每隔几秒换位置，上下左右都能走，窗口跟随移动
- 双击戳她：8 种随机反应 + 台词
- 快速甩动：惊讶 / 晕乎；按住拖动：被拎起
- 只在桌面显示；进应用、锁屏、息屏自动收起（缩成 1×1 透明窗，避免被 ColorOS 冻结）
- 可拖到屏幕四边和顶部（按状态栏高度夹取，保证还能抓回来）

### 对话
- **说话**：点 🎤 或唤醒词，只在桌面气泡里回答，**不弹窗**
- 唤醒后连续听，约 3.5 秒静音才收尾；边说边显示转写
- 点气泡进入聊天窗（悬浮窗，不切前台），聊天内容长按可复制
- 气泡停留 5–15 秒可调

### 能力
- **语音识别**：离线 Vosk，可在设置里选大模型（1.9GB，准）/ 小模型（66MB，省内存）
- **语音合成**：BreezeBlue「大肥鱼」→ Edge TTS → 系统 TTS 三级回退
- **对话模型**：DeepSeek 官方 API（OpenAI 兼容），界面可选 `deepseek-flash` / `deepseek-v4-pro`
- **本机指令**（不消耗 token）：时间/电量/手电/音量/闹钟/天气/开关唤醒
- **打开应用**：应用列表 + 别名 + 容错匹配，经 Shizuku/root 直接启动，不动桌面图标
- **桌面感知**：无障碍读取脚下图标，会针对该应用说一句

---

## 权限说明

| 权限 | 用途 | 授予方式 |
| --- | --- | --- |
| 悬浮窗 | 桌面显示她与聊天窗 | 应用内弹授权 |
| 麦克风 | 唤醒词与语音输入 | 应用内弹授权 |
| 通知 | 前台服务常驻 | 安卓 13+ 需手动允许 |
| 用量访问 | 判断前台应用（决定是否收起） | 设置里手动开 |
| Shizuku | 读前台、shell 启动应用 | 用户在 Shizuku 里授权 |
| root | Shizuku 不可用时兜底 | 可选 |
| 无障碍 | 感知桌面图标（只读） | 应用自动写入设置项 |

> 没有 key 会写进代码：DeepSeek Key / BreezeBlue Key 都只存在手机的 `SharedPreferences`。

---

## 构建

本项目用 JDK + aapt2 直接打包，不依赖 Android Studio。

```bash
# 依赖
#   JDK 17+
#   aapt2（Android build-tools）
#   android.jar（API 34）
#   Vosk AAR、JNA、Shizuku API/Provider/AIDL/Shared、r8.jar、apksigner.jar
# 放到 build/ 下，然后在 build/build.sh 顶部改这几个变量：
#   J  = android.jar
#   AAPT2 = aapt2 可执行文件
#   B/vosk/... = 上述 AAR 解包后的 classes.jar

bash scripts/fetch-assets.sh   # 拉取 Vosk 小模型（66MB）
bash build/build.sh            # 产出 petapp/whale-pet.apk
```

安装：

```bash
adb install -r whale-pet.apk
```

---

## 目录结构

```
src/com/dsh/whalepet/
  PetService.java     悬浮窗、桌宠窗口、漫游、唤醒调度、语音模式
  ChatPanel.java      悬浮聊天窗（拖动 / 八向缩放 / 最大化）
  ChatEngine.java     对话内核（本机指令 → 模型），历史持久化
  VoiceInput.java     离线识别（Vosk），大/小模型切换
  WakeWord.java       唤醒词匹配（同音容错）
  LocalSkills.java    本机指令与打开应用
  ShellExec.java      Shizuku / root shell 分级执行
  PetA11yService.java 只读桌面感知
  BigModel.java       大模型下载与安装
assets/
  pet.html            2D 骨骼渲染页（当前使用）
  live2d/             Live2D 式骨骼渲染器与贴图（AGPL，见 NOTICE）
  sprites/            早期精灵图（保留）
  pet3d.html + 3d/    实验性 3D 版（默认未启用）
```

---

## 已知限制

- 大模型常驻约 3GB 内存，低内存设备建议切小模型
- 唤醒是离线小模型，嘈杂环境识别率有限
- 3D 版是程序化几何体，观感一般，默认未启用
- ColorOS 等 ROM 建议把她加入电池白名单，否则后台可能被冻结

---

## 授权与致谢

- 本项目代码：**AGPL-3.0-or-later**
- Live2D 式渲染器与贴图：来自 [Pal AI Lab / Coopanion](https://github.com/Pal-AI-Lab/Coopanion)，AGPL-3.0-or-later；贴图由创作团队制作并放弃版权主张，保留署名与引用链即可
- 离线识别：[Vosk](https://alphacephei.com/vosk/)（Apache-2.0）
- 特权调用：[Shizuku](https://shizuku.rikka.app/)（Apache-2.0）
- 角色设定：B 站 DeepSeek 二创社区的「蓝发鲸鱼女仆」，非官方同人作品

**分发要求**：AGPL 是具有传染性的 copyleft 协议——你可以自由使用、修改、再分发，
但分发（包括发布 APK）时必须同时以 AGPL-3.0 提供完整源码。
