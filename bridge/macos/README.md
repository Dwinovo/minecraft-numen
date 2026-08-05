# Numen Bridge（macOS）

Numen Bridge 是一个独立的 macOS 菜单栏应用。它负责两件 Java 在 macOS 上不稳定的事情：

- 通过 `AVAudioEngine` 申请系统麦克风权限并采集 PCM；
- 通过 DashScope Realtime 完成 STT/TTS，再把文字和 WAV 音频交给 Minecraft。

Minecraft 只连接本机 `127.0.0.1`，不会把麦克风暴露到局域网。Bridge 启动后会在下面的位置写入带随机令牌的发现文件，文件权限为 `0600`：

```text
~/Library/Application Support/Numen Bridge/bridge.json
```

因此，Numen 的 STT 设置里**不需要填写服务器地址或 API Key**。选择“Numen Bridge（macOS 本机）”后，模组会自动读取这个文件。TTS 的声线条目也选择同一个 Bridge 后端；模型默认是 `qwen3-tts-flash-realtime`，音色默认是 `Cherry`。

## 使用

1. 双击 `Numen Bridge.app`，或运行 `~/Applications/Numen Bridge.app`。
2. 首次启动时在 macOS 弹窗中允许麦克风；菜单栏图标也提供“请求麦克风权限”。
3. 打开 Bridge 的“设置”，在钥匙串中保存 DashScope API Key。STT 和 TTS 共用这一把 Key。
4. 启动官方 Minecraft Launcher 的 Fabric 1.21.1 实例。
5. 在 Numen 设置中：
   - 语音输入选择 `Numen Bridge（macOS 本机）`；
   - 语音输出新建或编辑声线，后端选择 `Numen Bridge（macOS 本机）`；
   - 不要再把 `wss://dashscope.aliyuncs.com/...` 填进 STT 的服务器地址。

如果菜单栏状态显示“请配置 DashScope API Key”，说明 Minecraft 端已经能发现 Bridge，但 Bridge 钥匙串里还没有 Key。若显示“麦克风权限已拒绝”，到“系统设置 → 隐私与安全性 → 麦克风”打开 Numen Bridge，然后重新启动应用。

## 构建与安装

在仓库根目录执行：

```sh
cd bridge/macos
./scripts/package-app.sh
```

脚本会构建 arm64 Release、写入 `NSMicrophoneUsageDescription`、附加音频输入 entitlement，并进行代码签名和严格校验。生成的 App 在 `bridge/macos/build/Numen Bridge.app`；可复制到 `~/Applications/` 后启动。

## 协议入口

- `GET /v1/health`：本机状态和麦克风权限状态；
- `POST /v1/audio/speech`：返回 WAV；
- `POST /v1/audio/transcriptions`：接收 WAV 并返回文字；
- `WS /v1/audio/capture`：Bridge 采集麦克风并流式返回转写事件。

所有接口都要求发现文件中的 Bearer 令牌，服务只绑定 `127.0.0.1`。
