# Numen Bridge macOS 适配方案

## 目标

发布一个独立的 macOS 辅助程序，为 Numen 提供可靠的麦克风权限，并把不同语音
服务商的协议封装为稳定的本机接口。Minecraft 模组仍然是用户直接操作的客户端，
Windows、Linux 以及现有的服务商直连方式保持不变。

第一版支持 Apple Silicon 和 Minecraft 官方启动器。本机接口不依赖 Numen 内部实现，
其他 Minecraft 模组也可以复用同一个 Bridge。

## 为什么必须使用独立进程

Minecraft 由官方启动器以 Java 子进程运行。在当前机器上，Java 可执行文件虽然带有
音频输入 entitlement，但麦克风用途说明没有绑定到实际运行的可执行文件签名信息。
macOS 的 TCC 数据库中没有 Minecraft 或 Java 的麦克风授权记录，Java Sound 打开输入
设备时也没有触发系统授权弹窗。

模组无法修复宿主进程的签名身份。修改或重新签名官方启动器、Java Runtime 很脆弱，
也会在启动器更新后失效。独立的原生 Mac 应用可以合法持有麦克风权限，再通过本机
回环接口把识别结果交给模组。

## 总体架构

### Numen Bridge.app

在仓库的 `bridge/macos` 目录新增 Apple Silicon Swift 应用，负责：

- 声明 `NSMicrophoneUsageDescription` 和音频输入 entitlement；
- 在开始录音前显式请求麦克风权限；
- 使用 AVFoundation 采集声音并转换为 16 kHz、16-bit、有符号、单声道 PCM；
- 只在 `127.0.0.1` 启动本机服务；
- 把 DashScope STT/TTS 转换成统一的本机协议；
- 对外只返回转写文本和标准 PCM WAV，不暴露服务商原始事件；
- 使用 macOS 钥匙串保存服务商 API Key；
- 通过一个精简的菜单栏界面显示权限、录音、服务商和错误状态。

开发版可以在本机签名后立即测试。对外分发版本需要 Developer ID 签名和公证；没有
证书时，仓库仍然可以提交完整源码和未签名的 CI 构建产物。

### 本机接口

默认地址为 `http://127.0.0.1:38471`。服务不得绑定局域网地址或通配地址。首次启动时
生成随机 Bearer Token，并写入仅当前用户可读的发现文件。

接口如下：

- `GET /v1/health`：返回 Bridge 版本、接口版本、平台、麦克风权限状态和服务商就绪
  状态，不返回任何凭据。
- `WS /v1/audio/capture`：接收 `capture.start` 和 `capture.stop`；返回
  `capture.started`、`transcript.delta`、`transcript.done` 和结构化 `error` 事件。
  麦克风由 Bridge 打开，不再由 Minecraft 打开。
- `POST /v1/audio/transcriptions`：接收标准 WAV 文件，供已经能自行采集声音的客户端
  使用，兼容 OpenAI 风格的调用方。
- `POST /v1/audio/speech`：接收 OpenAI 兼容的 `model`、`voice`、`input` 和
  `response_format` 字段，返回 `audio/wav`。

标准转写接口假设调用者已经取得麦克风音频，而这正是 Minecraft 在 macOS 上失败的
位置，因此需要额外提供 WebSocket 录音接口。

### 服务商适配

第一版提供：

- DashScope 实时 STT：`qwen-audio-3.0-realtime-flash`；
- DashScope 实时 TTS：`qwen3-tts-flash-realtime` 及兼容快照版本；
- 通用 OpenAI 兼容文件转写和语音合成接口。

DashScope 的事件名、完成条件、音频格式和地域地址全部封装在 Bridge 适配器中，并用
真实协议录制样本验证。Ollama 可以用于 LLM 路由，但不把它当作语音服务商，因为
Ollama 本身不能解决麦克风采集，也没有等价的 STT/TTS 接口。

新增模型只需配置适配器、地址、模型和音色，不需要再次修改 Minecraft 模组。

### 原版 Numen 模组改动

在原版 Numen 中新增 `bridge` STT/TTS 后端：

- macOS 检测到 Bridge 已安装且健康时，优先使用 Bridge；
- 保留所有现有直连后端，不影响已有用户；
- 通过录音 WebSocket 发送开始和停止命令，并把转写事件送入现有
  `VoiceInputController` 回调；
- 调用本机语音合成接口，再把返回的 WAV 交给现有 `WavCodec` 和 Minecraft 声音引擎；
- 分别提示 Bridge 未安装、麦克风权限拒绝、服务商鉴权失败、空音频和播放失败；
- 收到 `capture.started` 之前，不得把界面显示为“正在录音”。

Minecraft 进程不加载原生库，也不修改启动器、Java Runtime、TCC 数据库或系统安全
设置。

## 音频输出约定

Bridge 把 TTS 输出统一转换为 RIFF/WAVE：16-bit、有符号、小端、单声道 PCM，采样率
写入 WAV 文件头。返回成功前必须拒绝空音频、错误的 Base64、奇数长度 PCM 和
Minecraft 解码器不支持的采样率。

Numen 再次校验 WAV，记录合成耗时和字节数，然后使用现有试听音源或跟随同伴实体的
空间音源播放。Fabric 播放测试必须证明声音引擎实际读取了生成的 PCM，而不是
`sounds.json` 中的占位声音。

## 安全和隐私

- 服务商 API Key 保存在 macOS 钥匙串中，只发送给用户配置的服务商。
- 本机 Bearer Token 不写日志，所有请求日志必须隐藏 Authorization。
- 同一时间只允许一条麦克风录音。
- 客户端断开、主动停止、权限失败或达到现有 60 秒上限时，立即停止录音。
- 除非用户显式打开调试选项，否则不保存录音文件。
- 健康接口和菜单栏只显示状态，不显示 Key 或原始音频。

## 错误处理

每一层都必须返回可见且可操作的错误：

- macOS 权限：未询问、已拒绝、受限制、已授权；
- 录音：设备不可用、格式转换失败、没有采到样本；
- 服务商：连接失败、鉴权失败、协议错误、超时、返回空结果；
- 播放：WAV 无效、同伴实体不存在、声音引擎拒绝播放。

所有网络会话都有连接超时和完成超时。除非已经收到完整转写或完整音频，否则
WebSocket 异常关闭必须作为失败处理，不能把残缺的 TTS 字节当成成功结果。

## 测试方案

所有实现按红灯、绿灯、重构的 TDD 流程开发：

- Swift 单元测试：权限状态映射、PCM 转换、录音生命周期、本机鉴权、服务商事件解析；
- Java 单元测试：Bridge 发现、录音事件映射、TTS 请求、错误传递、WAV 拒绝和平台选择；
- 使用虚假本机 Bridge 和 DashScope 事件样本进行集成测试；
- 重新运行 Gradle common 测试以及 Fabric、NeoForge 构建；
- 启动本机签名的 Bridge，确认 macOS 弹出麦克风授权，并在 TCC 中生成记录；
- 使用官方启动器完成端到端实测：游戏内说话、得到非空文字、生成回复、取得非空
  TTS WAV，并在游戏中实际听到声音；
- 检查日志，证明后端选择、录音字节数、服务商结果、解码时长和播放启动均正确，
  同时确保日志不泄露凭据。

## 验收标准

只有在当前 Mac 上同时满足以下条件，才算完成：

1. 第一次录音时，macOS 为 Numen Bridge 显示正常的麦克风权限弹窗；授权后 TCC 中
   出现对应记录。
2. Bridge 模式启用后，Minecraft 不再直接打开麦克风。
3. 在游戏内说普通话，Numen 输入流程得到非空的最终转写文本。
4. DashScope TTS 返回非空且有效的 WAV，Minecraft 声音引擎实际播放回复。
5. 权限拒绝、API Key 错误、Bridge 未运行和空音频分别显示不同错误，不再静默失败。
6. 现有非 macOS 和服务商直连测试继续通过。
7. Apple Silicon 应用和匹配的 Fabric 模组 JAR 可以从仓库稳定复现构建；签名和公证
   状态单独说明。

## 第一版不包含

- Intel Mac 和 Universal Binary；
- 修改 CC Switch 本身；
- 安装虚拟麦克风或音频驱动；
- 允许局域网或远程访问 Bridge；
- 重做一套与 CC Switch 重复的完整 LLM 路由界面；
- 在没有仓库证书 Secret 的情况下自动进行 Developer ID 签名。
