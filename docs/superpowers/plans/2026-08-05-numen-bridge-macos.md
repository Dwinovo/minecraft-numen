# Numen Bridge macOS 实施计划

> **供代理执行：** 必须使用 `superpowers:executing-plans` 逐项实施；每项使用复选框追踪，并按红灯、绿灯、重构执行。

**目标：** 构建 Apple Silicon `Numen Bridge.app`，由它取得 macOS 麦克风权限、适配 DashScope STT/TTS，并让原版 Numen 通过本机统一协议完成游戏内语音输入和播放。

**架构：** Swift 菜单栏应用使用 AVFoundation 录音，使用 Hummingbird 2.26.0 与 HummingbirdWebSocket 2.7.0 只监听 `127.0.0.1:38471`。Java 模组新增 Bridge STT/TTS 后端；Bridge STT 自行管理录音，绕过 Java Sound，TTS 返回标准 WAV 后继续使用现有 Minecraft 声音引擎。

**技术栈：** Swift 6.3、SwiftUI、AVFoundation、Security/Keychain、Hummingbird、XCTest、Java 21、JUnit 5、JDK HttpClient/WebSocket、Gradle/Architectury、Fabric 1.21.1。

---

## 文件结构

- `bridge/macos/Package.swift`：SwiftPM 依赖、可执行目标和测试目标。
- `bridge/macos/Sources/NumenBridge/App/NumenBridgeApp.swift`：菜单栏应用入口和服务生命周期。
- `bridge/macos/Sources/NumenBridge/App/BridgeState.swift`：可观察的权限、录音和服务状态。
- `bridge/macos/Sources/NumenBridge/App/SettingsView.swift`：Keychain 凭据、模型和音色设置。
- `bridge/macos/Sources/NumenBridge/Audio/MicrophoneAuthorizing.swift`：权限接口和 AVFoundation 实现。
- `bridge/macos/Sources/NumenBridge/Audio/MicrophoneCapture.swift`：AVAudioEngine 采集与生命周期。
- `bridge/macos/Sources/NumenBridge/Audio/Pcm16Converter.swift`：Float PCM 到 16 kHz mono Int16 LE。
- `bridge/macos/Sources/NumenBridge/Provider/SpeechProvider.swift`：服务商中立接口和事件类型。
- `bridge/macos/Sources/NumenBridge/Provider/DashScopeSttClient.swift`：DashScope 实时转写。
- `bridge/macos/Sources/NumenBridge/Provider/DashScopeTtsClient.swift`：DashScope 实时合成和 WAV 封装。
- `bridge/macos/Sources/NumenBridge/Server/BridgeServer.swift`：健康、录音、转写、合成路由。
- `bridge/macos/Sources/NumenBridge/Server/ApiModels.swift`：本机 JSON 协议结构。
- `bridge/macos/Sources/NumenBridge/Security/KeychainStore.swift`：API Key 读写。
- `bridge/macos/Sources/NumenBridge/Security/DiscoveryFile.swift`：端口和本机 Token 发现文件。
- `bridge/macos/Resources/Info.plist`：应用身份和麦克风用途说明。
- `bridge/macos/Resources/NumenBridge.entitlements`：音频输入 entitlement。
- `bridge/macos/scripts/package-app.sh`：构建、组装和本机签名 `.app`。
- `bridge/macos/Tests/NumenBridgeTests/*Tests.swift`：Swift 单元和协议样本测试。
- `api/common/src/client/java/com/dwinovo/numen/client/stt/SttBackend.java`：声明是否由后端自行录音。
- `api/common/src/client/java/com/dwinovo/numen/client/stt/SttListener.java`：增加录音开始事件。
- `api/common/src/client/java/com/dwinovo/numen/client/stt/VoiceInputController.java`：区分 Java Sound 和 Bridge 录音。
- `api/common/src/client/java/com/dwinovo/numen/client/stt/BridgeStt.java`：本机录音 WebSocket 客户端。
- `api/common/src/client/java/com/dwinovo/numen/client/voice/BridgeTts.java`：本机 `/v1/audio/speech` 客户端。
- `api/common/src/client/java/com/dwinovo/numen/client/bridge/BridgeDiscovery.java`：读取发现文件。
- `api/common/src/test/java/com/dwinovo/numen/client/{bridge,stt,voice}/*Test.java`：Java 回归测试。
- `api/common/src/main/resources/numen_stt.json`：增加 Bridge 预设。

### 任务 1：建立可测试的 Swift Bridge 骨架

- [ ] 新建 `Package.swift`，固定 Hummingbird `2.26.0` 和 HummingbirdWebSocket `2.7.0`，目标平台设为 macOS 14。
- [ ] 先写 `HealthResponseTests.swift`，断言版本、平台、权限和就绪状态编码后的 JSON 字段稳定。

```swift
@Test func healthResponseUsesStableWireKeys() throws {
    let value = HealthResponse(version: "0.1.0", apiVersion: 1,
        platform: "macos-arm64", permission: .notDetermined, providerReady: false)
    let object = try #require(JSONSerialization.jsonObject(
        with: JSONEncoder().encode(value)) as? [String: Any])
    #expect(object["api_version"] as? Int == 1)
    #expect(object["permission"] as? String == "not_determined")
}
```

- [ ] 运行 `cd bridge/macos && swift test --filter HealthResponseTests`，确认因类型不存在而失败。
- [ ] 实现 `ApiModels.swift`、最小 `BridgeServer.swift` 和 SwiftUI 菜单栏入口，使 `/v1/health` 返回上述结构。
- [ ] 重跑 Swift 测试，预期通过；提交 `feat(bridge): add macOS service skeleton`。

### 任务 2：本机发现、鉴权与 Keychain

- [ ] 先写 `DiscoveryFileTests.swift`：临时目录首次加载生成 32 字节随机 Token、文件权限为 `0600`、二次加载保持 Token 不变。
- [ ] 先写 `KeychainStoreTests.swift`，通过内存 `CredentialStore` 验证空 Key 不就绪、保存后可读取、删除后不可读取。
- [ ] 运行这两个测试，确认缺少实现而失败。
- [ ] 实现 `DiscoveryFile`，真实路径为 `~/Library/Application Support/Numen Bridge/bridge.json`；JSON 固定包含整数 `api_version=1`、字符串 `base_url=http://127.0.0.1:38471`，以及由 32 字节随机数进行 Base64URL 编码得到的字符串 `token`。

- [ ] 实现 `KeychainStore`，service 固定为 `com.dwinovo.numen.bridge`，account 固定为 `dashscope-api-key`。
- [ ] 为所有 `/v1/*` 路由增加 Bearer Token 校验；健康接口也必须鉴权，避免暴露本机服务状态。
- [ ] 运行 `swift test`，预期通过；提交 `feat(bridge): secure local discovery and credentials`。

### 任务 3：macOS 权限和 PCM 采集

- [ ] 先写 `MicrophonePermissionTests.swift`，验证 AVFoundation 的 `.notDetermined/.denied/.restricted/.authorized` 映射到本机协议值。
- [ ] 先写 `Pcm16ConverterTests.swift`，输入 48 kHz 双声道 Float32 样本，断言输出为 16 kHz 单声道 Int16 LE、帧数缩小为三分之一且不会削波溢出。
- [ ] 先写 `MicrophoneCaptureTests.swift`，使用假输入源验证只允许一条录音、停止后不再产生样本、零样本停止返回 `capture_no_samples`。
- [ ] 分别运行三个测试，确认因实现缺失而失败。
- [ ] 实现 `MicrophoneAuthorizing`，生产实现使用 `AVCaptureDevice.authorizationStatus(for: .audio)` 和 `requestAccess(for: .audio)`。
- [ ] 实现 `Pcm16Converter`，使用 `AVAudioConverter` 转到 `AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true)`。
- [ ] 实现 `MicrophoneCapture`，使用 `AVAudioEngine.inputNode.installTap`，开始成功后才发出 started，停止时移除 tap 并停止 engine。
- [ ] 运行 `swift test`，预期通过；提交 `feat(bridge): capture macOS microphone audio`。

### 任务 4：DashScope TTS 正确输出

- [ ] 先写 `DashScopeTtsClientTests.swift`，验证请求顺序严格为 `session.update`、`input_text_buffer.append`、`input_text_buffer.commit`，默认模型为 `qwen3-tts-flash-realtime`、音色为 `Cherry`、输出为 24 kHz PCM。
- [ ] 增加事件样本测试：多个 `response.audio.delta` 合并；`response.done` 生成非空 WAV；`error`、异常关闭、空 PCM、错误 Base64 都失败。
- [ ] 运行 `swift test --filter DashScopeTtsClientTests`，确认缺少实现而失败。
- [ ] 实现 `DashScopeTtsClient`：在 `wss://dashscope.aliyuncs.com/api-ws/v1/realtime` 后添加 `model` 查询参数，其值为配置模型经过 URL 百分号编码后的结果；Authorization 只放握手头。
- [ ] 实现独立 `PcmWaveEncoder`，写入正确的 RIFF 长度、24 kHz、mono、16-bit；禁止把异常关闭时的部分 PCM 当成功。
- [ ] 给 `POST /v1/audio/speech` 接入 TTS 客户端，成功返回 `Content-Type: audio/wav`，失败返回结构化 JSON 错误。
- [ ] 运行全部 Swift 测试，预期通过；提交 `feat(bridge): adapt DashScope realtime TTS`。

### 任务 5：DashScope STT 和录音 WebSocket

- [ ] 先写 `DashScopeSttClientTests.swift`，验证 session 配置、16 kHz PCM Base64 append、stop 后 commit/create、delta 累积和 completed 最终文本。
- [ ] 增加握手前音频排队、鉴权失败、协议 error、异常关闭和空转写测试。
- [ ] 先写 `CaptureSocketTests.swift`，验证 `capture.start` 只有在权限和 AVAudioEngine 均成功后才返回 `capture.started`；`capture.stop` 返回 `transcript.done`；断线停止录音。
- [ ] 运行相关测试，确认缺少实现而失败。
- [ ] 实现 `DashScopeSttClient` 和 `SpeechProvider` 流事件；每个 PCM 块复制后再跨并发边界，避免 AVAudioBuffer 生命周期问题。
- [ ] 实现 `/v1/audio/capture` WebSocket；单实例锁拒绝第二条录音，60 秒自动停止。
- [ ] 实现 `/v1/audio/transcriptions` WAV 路由，复用同一个 STT 适配器而不是复制协议。
- [ ] 运行全部 Swift 测试，预期通过；提交 `feat(bridge): stream microphone audio to DashScope STT`。

### 任务 6：Mac 菜单栏、应用元数据与打包

- [ ] 先写 `BridgeStateTests.swift`，验证权限拒绝、服务商 Key 缺失、服务启动、录音中和错误状态的显示模型。
- [ ] 运行测试，确认缺少状态实现而失败。
- [ ] 实现菜单栏：状态图标、权限状态、服务状态、开始权限测试、打开设置、退出；设置页只包含 DashScope Key、区域、STT 模型、TTS 模型和音色。
- [ ] 创建 `Info.plist`，Bundle ID 为 `com.dwinovo.numen.bridge`，包含中文麦克风用途说明；创建 entitlements，启用 `com.apple.security.device.audio-input`。
- [ ] 创建 `package-app.sh`：`swift build -c release`、组装 `.app/Contents/{MacOS,Resources}`、复制 Info.plist、用 entitlements 本机签名，并用 `codesign --verify --deep --strict` 验证。
- [ ] 运行 `swift test` 和 `./scripts/package-app.sh`，预期测试通过且 `build/Numen Bridge.app` 签名验证成功；提交 `feat(bridge): package macOS menu bar app`。

### 任务 7：原版 Numen Bridge STT 适配

- [ ] 先写 `BridgeDiscoveryTest.java`，用临时 `user.home` 读取发现文件，验证只接受 loopback HTTP 地址、API v1 和非空 Token。
- [ ] 先写 `BridgeSttTest.java`，用假 WebSocket 传入 `capture.started`、delta、done 和 error，验证监听器调用顺序。
- [ ] 先扩展 `VoiceInputControllerTest.java`：Bridge 后端不得调用 `MicrophoneManager`；收到 started 前不进入 active；停止调用 Bridge session finish。
- [ ] 运行 `:api:common:test --tests '*Bridge*' --tests '*VoiceInputController*'`，确认因实现缺失而失败。
- [ ] 在 `SttBackend` 增加默认 `capturesMicrophone()` 返回 false，在 `SttListener` 增加默认 `onCaptureStarted()`；实现 `BridgeStt` 返回 true 并连接本机 WebSocket。
- [ ] 修改 `VoiceInputController`，直连后端继续使用 Java Sound；Bridge 后端等待 started 事件并在 stop 时直接结束远端录音。
- [ ] 在 `SttProviders` 和 `numen_stt.json` 增加 `bridge` 预设，默认地址由发现文件提供，设置页不要求服务商 Key。
- [ ] 运行相关测试及全部 `:api:common:test`，预期通过；提交 `feat(stt): use Numen Bridge capture on macOS`。

### 任务 8：原版 Numen Bridge TTS 与播放防护

- [ ] 先写 `BridgeTtsTest.java`，使用本机假 HTTP 服务验证请求字段、Bearer Token、`audio/wav` 返回、401 错误、空 Body 和超时。
- [ ] 扩展 `DashScopeTtsTest.java`，确保异常关闭与空 PCM 不再生成可播放 WAV。
- [ ] 写 `PcmAudioStreamTest.java`，验证 direct ByteBuffer 中的数据与生成 PCM 完全一致，读取结束后返回空 buffer。
- [ ] 运行三个测试，确认新增行为失败。
- [ ] 实现 `BridgeTts`，调用发现地址的 `/v1/audio/speech`；在 `VoiceLibrary` 注册 `bridge` backend。
- [ ] 加强直连 `DashScopeTts`：只有 `response.done` 且 PCM 非空才成功，异常 close 返回失败。
- [ ] 在 `VoicePipeline` 记录 WAV 字节数、解码时长和开播状态；保持日志不包含 Token、Key 或完整回复文本。
- [ ] 运行 `:api:common:test`、`:api:fabric:build -x test` 和 `:api:neoforge:build -x test`，预期全部退出码为 0；提交 `feat(voice): play speech through Numen Bridge`。

### 任务 9：安装和官方启动器端到端验证

- [ ] 运行 `bridge/macos/scripts/package-app.sh`，把构建结果安装到 `~/Applications/Numen Bridge.app`，保留任何被替换版本的时间戳备份。
- [ ] 启动 Bridge，触发麦克风权限请求；用户允许后用只读 TCC 查询确认 `com.dwinovo.numen.bridge` 为已授权。
- [ ] 构建 Fabric JAR，备份并替换 `~/Library/Application Support/minecraft/mods` 中的 Numen JAR。
- [ ] 启动官方 Minecraft Launcher 和 Fabric 1.21.1；Bridge 缺 Key 时先确认游戏显示明确错误。
- [ ] 在用户明确授权“把当前 DashScope STT/TTS Key 发送到 `dashscope.aliyuncs.com`”后配置 Keychain 并进行联网测试。
- [ ] 在游戏中说普通话，确认日志依次出现 Bridge capture started、非零 PCM 字节、非空 final transcript。
- [ ] 触发同伴回复，确认日志出现非零 WAV 字节、有效音频时长和播放开始，并由用户确认实际听到声音。
- [ ] 重新运行 Swift 全测试、Gradle common 全测试、Fabric/NeoForge 构建和 `git diff --check`，记录准确结果。
- [ ] 更新中文使用说明与 PR 内容；只有在用户明确授权读取 GitHub PAT 并发送到 `github.com` 后才推送分支。

## 完成条件

必须同时满足设计文档的七条验收标准、所有自动化测试通过、官方启动器实机 STT/TTS 成功，才允许把任务标记为完成。仅协议单测、录制 WAV 或 Bridge 自测成功都不能替代游戏内端到端结果。
