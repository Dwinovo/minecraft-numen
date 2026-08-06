# Numen Bridge（macOS）

Numen Bridge 是一个独立的 macOS 菜单栏应用。它负责两件 Java 在 macOS 上不稳定的事情：

- 通过 `AVAudioEngine` 申请系统麦克风权限并采集 PCM；
- 完成 STT/TTS，再把文字和 WAV 音频交给 Minecraft。

语音服务二选一，在 Bridge 的“设置”里切换：

- **DashScope**（默认）——阿里云百炼 Realtime，中国大陆直连；
- **Custom**——任意 OpenAI 兼容端点：填 Base URL 与 API Key，STT 走 `POST /v1/audio/transcriptions`（multipart WAV 上传），TTS 走 `POST /v1/audio/speech`（要求返回 WAV）。

Minecraft 只连接本机 `127.0.0.1`，不会把麦克风暴露到局域网。Bridge 启动后会在下面的位置写入带随机令牌的发现文件，文件权限为 `0600`：

```text
~/Library/Application Support/Numen Bridge/bridge.json
```

因此，Numen 的 STT 设置里**不需要填写服务器地址或 API Key**。选择“Numen Bridge（macOS 本机）”后，模组会自动读取这个文件。TTS 的声线条目也选择同一个 Bridge 后端。

## 使用

1. 把 `Numen Bridge.app` 拖进“应用程序”并双击启动（见 [Releases](https://github.com/Guojiz/numen-macos/releases) 里的 `Numen-Bridge-macos.zip`）。
2. 首次启动时在 macOS 弹窗中允许麦克风；菜单栏图标也提供“请求麦克风权限”。
3. 打开 Bridge 的“设置”：
   - **DashScope**：保存 API Key 即可。STT 和 TTS 共用这一把 Key；STT 默认 `qwen-audio-3.0-realtime-flash`，TTS 默认 `qwen3-tts-flash-realtime` / 音色 `Cherry`。
   - **Custom**：切到 Custom，填 Base URL（域名、`/v1` 或完整路径都行）与 API Key，再填 STT/TTS 模型与音色（如 `whisper-1`、`tts-1`、`alloy`）。API Key 同样存进系统钥匙串。
4. 启动官方 Minecraft Launcher 的 Fabric 1.21.1 实例。
5. 在 Numen 设置中：
   - 语音输入选择 `Numen Bridge（macOS 本机）`；
   - 语音输出新建或编辑声线，后端选择 `Numen Bridge（macOS 本机）`；
   - 不要再把 `wss://dashscope.aliyuncs.com/...` 之类的地址填进 STT 的服务器地址。

菜单栏状态提示对应的问题：

- “请配置 DashScope API Key” / “请配置 Custom 服务的 Base URL 与 API Key”——Minecraft 端已能发现 Bridge，但 Bridge 里还没配好当前 provider；
- “麦克风权限已拒绝”——到“系统设置 → 隐私与安全性 → 麦克风”打开 Numen Bridge，然后重新启动应用。

## 构建与安装

```sh
cd bridge/macos
./scripts/package-app.sh
```

脚本会构建 arm64 Release、附带图标资源、写入 `NSMicrophoneUsageDescription`、附加音频输入 entitlement，并进行代码签名和严格校验。签名身份优先使用名为 `Numen Bridge` 的代码签名证书（身份稳定，钥匙串与麦克风授权跨构建保持有效；可用 `NUMEN_SIGNING_IDENTITY` 覆盖），找不到时回退 ad-hoc。生成的 App 在 `bridge/macos/build/Numen Bridge.app`。

## 协议入口

- `GET /v1/health`：本机状态和麦克风权限状态；
- `POST /v1/audio/speech`：返回 WAV；
- `POST /v1/audio/transcriptions`：接收 WAV 并返回文字；
- `WS /v1/audio/capture`：Bridge 采集麦克风并流式返回转写事件（Custom 下为缓冲后一次性转写）。

所有接口都要求发现文件中的 Bearer 令牌，服务只绑定 `127.0.0.1`。
